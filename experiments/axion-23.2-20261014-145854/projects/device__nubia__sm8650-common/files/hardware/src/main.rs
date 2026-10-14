/*
 * SPDX-FileCopyrightText: 2025-2026 GAME-OVER-op
 * SPDX-License-Identifier: MIT
 */

//! Tiro hardware coordination service for fan control, shoulder triggers,
//! and the external and fan RGB controllers.

mod triggers;

use std::{
    fs,
    path::{Path, PathBuf},
    thread,
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};

use triggers::{ActiveConfig, SideConfig, TriggerManager};

const CONFIG_PATH: &str = "/data/system/tiro_hardware/state.conf";
const FAN_ENABLE: &str = "/sys/kernel/fan/fan_enable";
const FAN_LEVEL: &str = "/sys/kernel/fan/fan_speed_level";
const TRIGGER_LEFT_MODE: &str = "/proc/nubia_key/sar0/mode_operation";
const TRIGGER_RIGHT_MODE: &str = "/proc/nubia_key/sar1/mode_operation";
const LED_BASE: &str = "/sys/class/leds/aw22xxx_led";
const CPU_POLICY_ROOT: &str = "/sys/devices/system/cpu/cpufreq";
const CPU_PROFILE_INTERVAL: Duration = Duration::from_secs(5);
const HEARTBEAT_TIMEOUT_MS: u64 = 10_000;
const LOOP_INTERVAL: Duration = Duration::from_millis(200);
const FAN_INTERVAL: Duration = Duration::from_secs(2);
const LED_COMPOSE_DELAY: Duration = Duration::from_millis(800);

const CPU_ZONE_IDS: &[u32] = &[
    10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 25, 26, 27, 28, 29,
];

#[derive(Clone, Debug, PartialEq, Eq)]
struct RuntimeConfig {
    timestamp_ms: u64,
    screen_on: bool,
    trigger_active: bool,
    left: SideConfig,
    right: SideConfig,
    fan_enabled: bool,
    fan_auto: bool,
    fan_manual_level: u8,
    fan_curve_mc: [i32; 5],
    fan_led_enabled: bool,
    fan_led_code: String,
    trigger_led_enabled: bool,
    trigger_led_code: String,
    notification_led_enabled: bool,
    notification_led_code: String,
    notification_active: bool,
    // 0=inactive, 100=battery, 200=standard, 300=performance.
    performance_mode: u16,
}

impl Default for RuntimeConfig {
    fn default() -> Self {
        Self {
            timestamp_ms: 0,
            screen_on: true,
            trigger_active: false,
            left: SideConfig::off(),
            right: SideConfig::off(),
            fan_enabled: false,
            fan_auto: false,
            fan_manual_level: 3,
            fan_curve_mc: [50_000, 60_000, 70_000, 80_000, 90_000],
            fan_led_enabled: false,
            fan_led_code: "a4".to_string(),
            trigger_led_enabled: false,
            trigger_led_code: "64".to_string(),
            notification_led_enabled: false,
            notification_led_code: "74".to_string(),
            notification_active: false,
            performance_mode: 0,
        }
    }
}

impl SideConfig {
    fn off() -> Self {
        Self {
            enabled: false,
            x_px: 0,
            y_px: 0,
            rot: 0,
            disp_w: 0,
            disp_h: 0,
        }
    }
}

fn as_bool(value: &str) -> Option<bool> {
    match value {
        "0" => Some(false),
        "1" => Some(true),
        _ => None,
    }
}

fn parse_side(values: &[&str]) -> Option<SideConfig> {
    if values.len() != 6 {
        return None;
    }
    Some(SideConfig {
        enabled: as_bool(values[0])?,
        x_px: values[1].parse().ok()?,
        y_px: values[2].parse().ok()?,
        rot: values[3].parse::<i32>().ok()?.clamp(0, 3),
        disp_w: values[4].parse::<i32>().ok()?.max(0),
        disp_h: values[5].parse::<i32>().ok()?.max(0),
    })
}

fn valid_fan_code(code: &str) -> bool {
    matches!(
        code,
        "21" | "22" | "23" | "24" | "25" | "26" | "27" | "28" | "29"
            | "2a" | "2b" | "2c" | "2d" | "2f"
            | "31" | "32" | "33" | "34" | "35" | "36" | "37" | "38" | "39"
            | "3a" | "3b" | "3c" | "3d" | "3f"
            | "41" | "42" | "43" | "44" | "45" | "46" | "47" | "48" | "49"
            | "4a" | "4b" | "4c" | "4d" | "4f"
            | "90" | "91" | "92" | "93" | "94" | "95" | "96" | "97"
            | "a0" | "a2" | "a3" | "a4" | "a5" | "a6" | "a7"
    )
}

fn valid_external_code(code: &str) -> bool {
    matches!(
        code,
        "60" | "61" | "62" | "63" | "64" | "65" | "66" | "67"
            | "70" | "71" | "72" | "73" | "74" | "75" | "76" | "77"
            | "80" | "81" | "82" | "83" | "84" | "85" | "86" | "87"
    )
}

fn parse_config(raw: &str) -> Option<RuntimeConfig> {
    let p: Vec<&str> = raw.split_whitespace().collect();
    // Backwards-compatible with the original 31-field hardware state.
    if !(p.len() == 31 || p.len() == 32) || p[0] != "v1" {
        return None;
    }

    let mut curve = [0_i32; 5];
    for (index, output) in curve.iter_mut().enumerate() {
        let celsius = p[19 + index].parse::<i32>().ok()?.clamp(20, 110);
        *output = celsius * 1000;
    }
    if !curve.windows(2).all(|pair| pair[0] < pair[1]) {
        return None;
    }

    let fan_code = p[25].to_ascii_lowercase();
    let trigger_code = p[27].to_ascii_lowercase();
    let notification_code = p[29].to_ascii_lowercase();
    if !valid_fan_code(&fan_code)
        || !valid_external_code(&trigger_code)
        || !valid_external_code(&notification_code)
    {
        return None;
    }

    Some(RuntimeConfig {
        timestamp_ms: p[1].parse().ok()?,
        screen_on: as_bool(p[2])?,
        trigger_active: as_bool(p[3])?,
        left: parse_side(&p[4..10])?,
        right: parse_side(&p[10..16])?,
        fan_enabled: as_bool(p[16])?,
        fan_auto: as_bool(p[17])?,
        fan_manual_level: p[18].parse::<u8>().ok()?.clamp(1, 5),
        fan_curve_mc: curve,
        fan_led_enabled: as_bool(p[24])?,
        fan_led_code: fan_code,
        trigger_led_enabled: as_bool(p[26])?,
        trigger_led_code: trigger_code,
        notification_led_enabled: as_bool(p[28])?,
        notification_led_code: notification_code,
        notification_active: as_bool(p[30])?,
        performance_mode: p
            .get(31)
            .and_then(|s| s.parse::<u16>().ok())
            .filter(|mode| matches!(*mode, 100 | 200 | 300 | 400))
            .unwrap_or_default(),
    })
}

fn read_config() -> Option<RuntimeConfig> {
    fs::read_to_string(CONFIG_PATH)
        .ok()
        .and_then(|raw| parse_config(raw.trim()))
}

fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis()
        .min(u64::MAX as u128) as u64
}

fn runtime_fresh(config: &RuntimeConfig) -> bool {
    now_ms().saturating_sub(config.timestamp_ms) <= HEARTBEAT_TIMEOUT_MS
}

fn read_i32(path: &Path) -> Option<i32> {
    fs::read_to_string(path).ok()?.trim().parse().ok()
}

fn read_cpu_temp_mc() -> Option<i32> {
    let mut total = 0_i64;
    let mut count = 0_i64;
    for id in CPU_ZONE_IDS {
        let path = format!("/sys/class/thermal/thermal_zone{}/temp", id);
        if let Some(value) = read_i32(Path::new(&path)) {
            if (0..=150_000).contains(&value) {
                total += value as i64;
                count += 1;
            }
        }
    }
    if count == 0 {
        None
    } else {
        Some((total / count) as i32)
    }
}

fn auto_fan_level(temp_mc: i32, curve: &[i32; 5], current: u8) -> u8 {
    let mut target = 0_u8;
    for (index, threshold) in curve.iter().enumerate() {
        if temp_mc >= *threshold {
            target = (index + 1) as u8;
        }
    }

    if target < current && current > 0 {
        let current_threshold = curve[(current - 1) as usize];
        if temp_mc >= current_threshold - 2_000 {
            return current;
        }
    }
    target
}

fn write_value(path: &str, value: &str) -> bool {
    match fs::write(path, format!("{}\n", value)) {
        Ok(()) => true,
        Err(error) => {
            eprintln!("tiro_hardware_service: write {}={} failed: {}", path, value, error);
            false
        }
    }
}

fn apply_fan(level: u8) -> bool {
    let level = level.min(5);
    if level == 0 {
        let level_ok = write_value(FAN_LEVEL, "0");
        let enable_ok = write_value(FAN_ENABLE, "0");
        level_ok && enable_ok
    } else {
        let first_enable_ok = write_value(FAN_ENABLE, "1");
        let level_ok = write_value(FAN_LEVEL, &level.to_string());
        let second_enable_ok = write_value(FAN_ENABLE, "1");
        first_enable_ok && level_ok && second_enable_ok
    }
}

fn led_path(name: &str) -> String {
    format!("{}/{}", LED_BASE, name)
}

fn write_effect(code: &str) -> bool {
    write_value(&led_path("effect"), code) && write_value(&led_path("cfg"), "1")
}

fn apply_leds(fan_code: Option<&str>, external_code: Option<&str>) -> bool {
    if external_code.is_some() {
        let _ = write_value(&led_path("hwen"), "1");
    }

    if let Some(fan) = fan_code {
        let external_ok = write_effect(external_code.unwrap_or("00"));
        thread::sleep(LED_COMPOSE_DELAY);
        external_ok && write_effect(fan)
    } else {
        let fan_ok = write_effect("2");
        thread::sleep(LED_COMPOSE_DELAY);
        fan_ok && write_effect(external_code.unwrap_or("00"))
    }
}


#[derive(Debug)]
struct CpuPolicy {
    max_path: PathBuf,
    baseline_max: u64,
    hardware_max: u64,
    hardware_min: u64,
    available: Vec<u64>,
}

impl CpuPolicy {
    fn read_u64(path: &Path) -> Option<u64> {
        fs::read_to_string(path).ok()?.trim().parse::<u64>().ok()
    }

    fn discover(dir: &Path) -> Option<Self> {
        let max_path = dir.join("scaling_max_freq");
        let baseline_max = Self::read_u64(&max_path)?;
        let hardware_max = Self::read_u64(&dir.join("cpuinfo_max_freq"))?;
        let hardware_min = Self::read_u64(&dir.join("cpuinfo_min_freq"))?;
        if hardware_min == 0 || hardware_max < hardware_min || baseline_max == 0 {
            return None;
        }
        let mut available = fs::read_to_string(dir.join("scaling_available_frequencies"))
            .unwrap_or_default()
            .split_whitespace()
            .filter_map(|s| s.parse::<u64>().ok())
            .filter(|hz| *hz >= hardware_min && *hz <= hardware_max)
            .collect::<Vec<_>>();
        available.sort_unstable();
        available.dedup();
        Some(Self { max_path, baseline_max, hardware_max, hardware_min, available })
    }

    fn desired_max(&self, profile: u16) -> u64 {
        if profile == 300 {
            return self.baseline_max;
        }
        let pct = if profile == 100 { 30 } else { 70 };
        let target = ((self.hardware_max * pct) / 100)
            .clamp(self.hardware_min, self.baseline_max);
        // The kernel uses discrete OPPs. Round down rather than silently
        // raising the limit above the requested percentage.
        self.available.iter().copied().filter(|f| *f <= target).max()
            .unwrap_or(target)
            .max(self.hardware_min)
            .min(self.baseline_max)
    }

    fn write_max(&self, value: u64) -> bool {
        // Nothing in this service is allowed to change scaling_min_freq.
        write_value(&self.max_path.to_string_lossy(), &value.to_string())
    }
}

#[derive(Default)]
struct CpuCaps {
    policies: Vec<CpuPolicy>,
    applied_mode: u16,
}

impl CpuCaps {
    fn activate(&mut self, mode: u16) -> bool {
        if mode == 0 {
            let mut ok = true;
            for policy in &self.policies {
                ok = policy.write_max(policy.baseline_max) && ok;
            }
            if ok { self.policies.clear(); self.applied_mode = 0; }
            return ok;
        }
        if self.policies.is_empty() {
            let entries = match fs::read_dir(CPU_POLICY_ROOT) {
                Ok(entries) => entries,
                Err(err) => {
                    eprintln!("tiro_hardware_service: no CPU policies: {err}");
                    return false;
                }
            };
            self.policies = entries.filter_map(Result::ok)
                .filter(|e| e.file_name().to_string_lossy().starts_with("policy"))
                .filter_map(|e| CpuPolicy::discover(&e.path()))
                .collect();
            self.policies.sort_by(|a, b| a.max_path.cmp(&b.max_path));
        }
        if self.policies.is_empty() { return false; }
        let mut ok = true;
        for policy in &self.policies {
            let current_min = CpuPolicy::read_u64(
                &policy.max_path.with_file_name("scaling_min_freq"))
                .unwrap_or(policy.hardware_min);
            let max_value = policy.desired_max(mode).max(current_min);
            // A concurrent power/thermal controller can raise scaling_min_freq.
            // Never lower max below that live minimum; never raise the minimum.
            ok = policy.write_max(max_value) && ok;
        }
        if ok { self.applied_mode = mode; }
        ok
    }
}

fn trigger_config(config: &RuntimeConfig, fresh: bool) -> ActiveConfig {
    ActiveConfig {
        enabled: fresh && config.screen_on && config.trigger_active,
        left: config.left,
        right: config.right,
    }
}

fn main() {
    println!("tiro_hardware_service: starting");

    // Never inherit a stale global trigger mode from an interrupted session.
    // It is enabled again only after a fresh per-application config arrives.
    let _ = write_value(TRIGGER_LEFT_MODE, "2");
    let _ = write_value(TRIGGER_RIGHT_MODE, "2");

    let mut trigger_manager: Option<TriggerManager> = None;
    let mut last_trigger_init = Instant::now() - Duration::from_secs(30);
    let mut last_trigger_config: Option<ActiveConfig> = None;
    let mut config = RuntimeConfig::default();
    let mut last_fan_tick = Instant::now() - FAN_INTERVAL;
    let mut fan_level = 0_u8;
    let mut fan_initialized = false;
    let mut last_led_state: Option<(Option<String>, Option<String>)> = None;
    let mut cpu_caps = CpuCaps::default();
    let mut last_cpu_profile_tick = Instant::now() - CPU_PROFILE_INTERVAL;

    loop {
        if let Some(next) = read_config() {
            config = next;
        }
        let fresh = runtime_fresh(&config);

        if trigger_manager.is_none() && last_trigger_init.elapsed() >= Duration::from_secs(5) {
            last_trigger_init = Instant::now();
            match TriggerManager::init() {
                Ok(manager) => {
                    println!("tiro_hardware_service: trigger manager ready");
                    trigger_manager = Some(manager);
                    last_trigger_config = None;
                }
                Err(error) => {
                    eprintln!("tiro_hardware_service: trigger init failed: {}", error);
                }
            }
        }

        let desired_trigger = trigger_config(&config, fresh);
        if last_trigger_config.as_ref() != Some(&desired_trigger) {
            if let Some(manager) = trigger_manager.as_ref() {
                manager.set_config(desired_trigger);
                last_trigger_config = Some(desired_trigger);
            }
        }

        if last_fan_tick.elapsed() >= FAN_INTERVAL {
            last_fan_tick = Instant::now();
            let desired = if !config.fan_enabled {
                0
            } else if config.fan_auto {
                read_cpu_temp_mc()
                    .map(|temp| auto_fan_level(temp, &config.fan_curve_mc, fan_level))
                    .unwrap_or(fan_level)
            } else {
                config.fan_manual_level
            };

            if (!fan_initialized || desired != fan_level) && apply_fan(desired) {
                fan_initialized = true;
                fan_level = desired;
                println!("tiro_hardware_service: fan level={}", fan_level);
            }
        }

        let fan_led = if fan_level > 0 && config.fan_led_enabled {
            Some(config.fan_led_code.clone())
        } else {
            None
        };
        // Lighting is independent of shoulder-trigger touch mapping. A user
        // may enable the external LEDs without assigning any trigger taps.
        let external_led = if fresh
            && config.screen_on
            && config.trigger_led_enabled
        {
            Some(config.trigger_led_code.clone())
        } else if fresh
            && !config.screen_on
            && config.notification_active
            && config.notification_led_enabled
        {
            Some(config.notification_led_code.clone())
        } else {
            None
        };
        let desired_led_state = (fan_led, external_led);
        if last_led_state.as_ref() != Some(&desired_led_state)
            && apply_leds(
                desired_led_state.0.as_deref(),
                desired_led_state.1.as_deref(),
            )
        {
            last_led_state = Some(desired_led_state);
        }

        let active_profile = if fresh && config.screen_on {
            config.performance_mode
        } else {
            0
        };
        if (active_profile != cpu_caps.applied_mode || !cpu_caps.policies.is_empty())
            && last_cpu_profile_tick.elapsed() >= CPU_PROFILE_INTERVAL
        {
            last_cpu_profile_tick = Instant::now();
            if (active_profile != cpu_caps.applied_mode
                || (active_profile == 0 && !cpu_caps.policies.is_empty()))
                && cpu_caps.activate(active_profile)
            {
                println!("tiro_hardware_service: CPU maximum profile={active_profile}");
            }
        }

        thread::sleep(LOOP_INTERVAL);
    }
}
