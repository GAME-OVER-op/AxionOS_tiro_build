/*
 * SPDX-FileCopyrightText: 2026 GAME-OVER-op
 * SPDX-License-Identifier: Apache-2.0
 */

#include <android/log.h>
#include <fcntl.h>
#include <poll.h>
#include <sys/inotify.h>
#include <unistd.h>

#include <cerrno>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <optional>
#include <string>
#include <utility>

namespace {

constexpr const char* kTag = "NubiaLightingService";
constexpr const char* kStateDir = "/data/system/nubia_lighting";
constexpr const char* kConfigPath = "/data/system/nubia_lighting/config.conf";
constexpr const char* kRuntimePath = "/data/system/nubia_lighting/runtime.conf";
constexpr const char* kFanStatePath = "/data/system/nubia_lighting/fan.state";

// NX769J AW22xxx device path. Use the real sysfs target rather than the
// /sys/class symlink so SELinux can grant access only to this controller.
constexpr const char* kLedBase =
        "/sys/devices/platform/soc/9c0000.qcom,qupv3_i2c_geni_se/"
        "98c000.i2c/i2c-0/0-006a/leds/aw22xxx_led";
constexpr const char* kFallbackFanLevel = "/sys/kernel/fan/fan_speed_level";
constexpr const char* kBrightnessOn = "255";
constexpr useconds_t kComposeDelayUs = 800000;

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, kTag, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, kTag, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, kTag, __VA_ARGS__)

struct LightingConfig {
    bool fanEnabled = false;
    std::string fanCode = "a4";
    bool triggerEnabled = false;
    std::string triggerCode = "64";
    bool notificationEnabled = false;
    std::string notificationCode = "74";
};

struct RuntimeState {
    bool screenOn = true;
    bool notificationActive = false;
    bool fanActive = false;
};

struct DesiredState {
    std::optional<std::string> fan;
    std::optional<std::string> external;

    bool operator==(const DesiredState& other) const {
        return fan == other.fan && external == other.external;
    }
    bool operator!=(const DesiredState& other) const { return !(*this == other); }
};

std::string trim(std::string value) {
    const auto first = value.find_first_not_of(" \t\r\n");
    if (first == std::string::npos) return {};
    const auto last = value.find_last_not_of(" \t\r\n");
    return value.substr(first, last - first + 1);
}

bool parseBool(const std::string& value, bool fallback) {
    if (value == "1" || value == "true" || value == "on") return true;
    if (value == "0" || value == "false" || value == "off") return false;
    return fallback;
}

bool validFanCode(const std::string& code) {
    static constexpr const char* kCodes[] = {
            "21", "22", "23", "24", "25", "26", "27", "28", "29",
            "2a", "2b", "2c", "2d", "2f",
            "31", "32", "33", "34", "35", "36", "37", "38", "39",
            "3a", "3b", "3c", "3d", "3f",
            "41", "42", "43", "44", "45", "46", "47", "48", "49",
            "4a", "4b", "4c", "4d", "4f",
            "90", "91", "92", "93", "94", "95", "96", "97",
            "a0", "a2", "a3", "a4", "a5", "a6", "a7",
    };
    for (const char* candidate : kCodes) {
        if (code == candidate) return true;
    }
    return false;
}

bool validExternalCode(const std::string& code) {
    static constexpr const char* kCodes[] = {
            "60", "61", "62", "63", "64", "65", "66", "67",
            "70", "71", "72", "73", "74", "75", "76", "77",
            "80", "81", "82", "83", "84", "85", "86", "87",
    };
    for (const char* candidate : kCodes) {
        if (code == candidate) return true;
    }
    return false;
}

LightingConfig readConfig() {
    LightingConfig config;
    std::ifstream input(kConfigPath);
    if (!input.is_open()) return config;

    std::string line;
    while (std::getline(input, line)) {
        line = trim(line);
        if (line.empty() || line[0] == '#') continue;
        const auto separator = line.find('=');
        if (separator == std::string::npos) continue;

        const std::string key = trim(line.substr(0, separator));
        const std::string value = trim(line.substr(separator + 1));
        if (key == "fan_enabled") {
            config.fanEnabled = parseBool(value, config.fanEnabled);
        } else if (key == "fan_code" && validFanCode(value)) {
            config.fanCode = value;
        } else if (key == "trigger_enabled") {
            config.triggerEnabled = parseBool(value, config.triggerEnabled);
        } else if (key == "trigger_code" && validExternalCode(value)) {
            config.triggerCode = value;
        } else if (key == "notification_enabled") {
            config.notificationEnabled = parseBool(value, config.notificationEnabled);
        } else if (key == "notification_code" && validExternalCode(value)) {
            config.notificationCode = value;
        }
    }
    return config;
}

RuntimeState readRuntime() {
    RuntimeState state;
    std::ifstream input(kRuntimePath);
    if (input.is_open()) {
        std::string line;
        while (std::getline(input, line)) {
            line = trim(line);
            if (line.empty() || line[0] == '#') continue;
            const auto separator = line.find('=');
            if (separator == std::string::npos) continue;

            const std::string key = trim(line.substr(0, separator));
            const std::string value = trim(line.substr(separator + 1));
            if (key == "screen_on") {
                state.screenOn = parseBool(value, state.screenOn);
            } else if (key == "notification_active") {
                state.notificationActive = parseBool(value, state.notificationActive);
            }
        }
    }

    std::ifstream fanState(kFanStatePath);
    std::string fanValue;
    if (fanState.is_open() && std::getline(fanState, fanValue)) {
        state.fanActive = parseBool(trim(fanValue), false);
    } else {
        // Only used during first boot/start before nubia_fan_service publishes its
        // state. This is a one-shot fallback, not a polling path.
        std::ifstream fanLevel(kFallbackFanLevel);
        int level = 0;
        if (fanLevel.is_open() && (fanLevel >> level)) state.fanActive = level > 0;
    }
    return state;
}

std::string ledPath(const char* name) {
    return std::string(kLedBase) + "/" + name;
}

bool writeString(const std::string& path, const std::string& value) {
    const int fd = open(path.c_str(), O_WRONLY | O_CLOEXEC);
    if (fd < 0) {
        LOGE("open %s failed: %s", path.c_str(), strerror(errno));
        return false;
    }

    const std::string payload = value + "\n";
    const ssize_t written = write(fd, payload.data(), payload.size());
    const int savedErrno = errno;
    close(fd);
    if (written != static_cast<ssize_t>(payload.size())) {
        LOGE("write %s=%s failed: %s", path.c_str(), value.c_str(), strerror(savedErrno));
        return false;
    }
    return true;
}

bool writeEffect(const std::string& code) {
    return writeString(ledPath("effect"), code) && writeString(ledPath("cfg"), "1");
}

// The two RGB zones share AW22xxx effect/cfg. Preparing the controller and
// writing brightness *after* the effects stops the running LED programs on
// NX769J. Prepare only on the first active state (and after a full shutdown),
// always before the composed effect sequence.
bool applyLeds(const DesiredState& desired, bool& hardwarePrepared) {
    const bool anyEnabled = desired.fan.has_value() || desired.external.has_value();
    if (anyEnabled && !hardwarePrepared) {
        if (!writeString(ledPath("hwen"), "1")) return false;
        usleep(20000);
        if (!writeString(ledPath("brightness"), kBrightnessOn)) return false;
        hardwarePrepared = true;
    }

    // External effects are supplied as 0xNN by the working AW22xxx control
    // script. Keep the validated LineageOS code tables unchanged.
    const std::string externalEffect =
            desired.external.has_value() ? "0x" + *desired.external : "0x00";

    bool ok = true;
    if (desired.fan.has_value()) {
        // Compose external first and FAN last, preserving both lit zones.
        ok = writeEffect(externalEffect);
        if (!ok) return false;
        usleep(kComposeDelayUs);
        ok = writeEffect(*desired.fan);
    } else {
        // With FAN off, apply external last instead.
        ok = writeEffect("2");
        if (!ok) return false;
        usleep(kComposeDelayUs);
        ok = writeEffect(externalEffect);
    }

    if (!anyEnabled) {
        // The next enable must re-initialize brightness before any effect.
        const bool disabled = writeString(ledPath("hwen"), "0");
        hardwarePrepared = false;
        ok = disabled && ok;
    }
    return ok;
}

DesiredState desiredState(const LightingConfig& config, const RuntimeState& runtime) {
    DesiredState desired;

    if (config.fanEnabled && runtime.fanActive) desired.fan = config.fanCode;

    // External light priority mirrors the old Axion behavior but is entirely
    // event driven: notifications are shown only with the screen off; trigger
    // lighting is shown only while the screen is on and triggers are enabled.
    if (!runtime.screenOn && config.notificationEnabled && runtime.notificationActive) {
        desired.external = config.notificationCode;
    } else if (runtime.screenOn && config.triggerEnabled) {
        // Physical trigger/external lighting is intentionally independent of
        // shoulder-trigger touch mapping, matching the corrected Axion logic.
        desired.external = config.triggerCode;
    }

    return desired;
}

class StateWatcher {
  public:
    StateWatcher() {
        fd_ = inotify_init1(IN_CLOEXEC | IN_NONBLOCK);
        if (fd_ < 0) {
            LOGE("inotify_init1 failed: %s", strerror(errno));
            return;
        }
        addWatch();
    }

    ~StateWatcher() {
        if (fd_ >= 0) close(fd_);
    }

    int fd() const { return fd_; }
    bool active() const { return fd_ >= 0 && watch_ >= 0; }

    void ensureWatch() {
        if (fd_ >= 0 && watch_ < 0) addWatch();
    }

    bool consumeChanged() {
        if (fd_ < 0) return false;
        bool changed = false;
        alignas(struct inotify_event) char buffer[4096];
        while (true) {
            const ssize_t length = read(fd_, buffer, sizeof(buffer));
            if (length < 0) {
                if (errno != EAGAIN && errno != EINTR) {
                    LOGW("inotify read failed: %s", strerror(errno));
                }
                break;
            }
            if (length == 0) break;

            size_t offset = 0;
            while (offset < static_cast<size_t>(length)) {
                const auto* event =
                        reinterpret_cast<const struct inotify_event*>(buffer + offset);
                if (event->mask & (IN_DELETE_SELF | IN_MOVE_SELF | IN_IGNORED)) watch_ = -1;
                if (event->len > 0) {
                    const std::string name(event->name);
                    if ((name == "config.conf" || name == "runtime.conf" || name == "fan.state") &&
                            (event->mask & (IN_CLOSE_WRITE | IN_MOVED_TO | IN_CREATE | IN_ATTRIB))) {
                        changed = true;
                    }
                }
                offset += sizeof(struct inotify_event) + event->len;
            }
        }
        return changed;
    }

  private:
    void addWatch() {
        watch_ = inotify_add_watch(fd_, kStateDir,
                                   IN_CLOSE_WRITE | IN_MOVED_TO | IN_CREATE | IN_ATTRIB |
                                           IN_DELETE_SELF | IN_MOVE_SELF);
        if (watch_ < 0) LOGW("inotify watch %s failed: %s", kStateDir, strerror(errno));
    }

    int fd_ = -1;
    int watch_ = -1;
};

}  // namespace

int main() {
    LOGI("starting");

    StateWatcher watcher;
    std::optional<DesiredState> applied;
    bool hardwarePrepared = false;

    auto reconcile = [&]() {
        const LightingConfig config = readConfig();
        const RuntimeState runtime = readRuntime();
        const DesiredState desired = desiredState(config, runtime);

        if (applied.has_value() && *applied == desired) return;
        if (applyLeds(desired, hardwarePrepared)) {
            applied = desired;
            LOGI("state applied: fan=%s external=%s",
                 desired.fan.has_value() ? desired.fan->c_str() : "off",
                 desired.external.has_value() ? desired.external->c_str() : "off");
        }
    };

    reconcile();

    while (true) {
        watcher.ensureWatch();
        const int timeoutMs = (watcher.active() && applied.has_value()) ? -1 : 1000;

        struct pollfd pfd {};
        pfd.fd = watcher.fd();
        pfd.events = POLLIN;
        const int result = (pfd.fd >= 0) ? poll(&pfd, 1, timeoutMs)
                                         : poll(nullptr, 0, 1000);
        if (result < 0) {
            if (errno == EINTR) continue;
            LOGE("poll failed: %s", strerror(errno));
            usleep(250000);
            continue;
        }

        if (result > 0 && (pfd.revents & POLLIN) && watcher.consumeChanged()) {
            reconcile();
        } else if (result == 0 && !applied.has_value()) {
            // Retry only during early boot if AW22xxx or its permissions were not
            // ready yet. After the first successful apply, the service blocks
            // indefinitely until a real state change arrives.
            reconcile();
        }
    }
}
