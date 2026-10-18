/*
 * SPDX-FileCopyrightText: 2026 GAME-OVER-op
 * SPDX-License-Identifier: Apache-2.0
 */

#include <android/log.h>
#include <fcntl.h>
#include <poll.h>
#include <sys/inotify.h>
#include <unistd.h>

#include <algorithm>
#include <array>
#include <cerrno>
#include <chrono>
#include <cstdint>
#include <cstdlib>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <limits>
#include <sstream>
#include <string>
#include <vector>

namespace {

constexpr const char* kTag = "NubiaFanService";
constexpr const char* kConfigDir = "/data/system/nubia_fan";
constexpr const char* kConfigPath = "/data/system/nubia_fan/config.conf";
constexpr const char* kFanEnable = "/sys/kernel/fan/fan_enable";
constexpr const char* kFanLevel = "/sys/kernel/fan/fan_speed_level";
constexpr const char* kLightingFanState = "/data/system/nubia_lighting/fan.state";
constexpr int kMinCurveTempC = 32;
constexpr int kMaxCurveTempC = 110;
constexpr int kHysteresisMc = 1000;
constexpr int kAutoIntervalMs = 2000;
constexpr std::array<int, 19> kCpuZoneIds = {
        10, 11, 12, 13, 14, 15, 16, 17, 18, 19,
        20, 21, 22, 23, 25, 26, 27, 28, 29,
};

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, kTag, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, kTag, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, kTag, __VA_ARGS__)

struct Config {
    bool enabled = false;
    bool automatic = false;
    int manualLevel = 3;
    std::array<int, 5> curveC = {50, 60, 70, 80, 90};

    bool operator==(const Config& other) const {
        return enabled == other.enabled && automatic == other.automatic &&
               manualLevel == other.manualLevel && curveC == other.curveC;
    }

    bool operator!=(const Config& other) const { return !(*this == other); }
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

int parseInt(const std::string& value, int fallback) {
    if (value.empty()) return fallback;

    errno = 0;
    char* end = nullptr;
    const long parsed = std::strtol(value.c_str(), &end, 10);

    if (errno == ERANGE ||
            end == value.c_str() ||
            *end != '\0' ||
            parsed < std::numeric_limits<int>::min() ||
            parsed > std::numeric_limits<int>::max()) {
        return fallback;
    }

    return static_cast<int>(parsed);
}

std::array<int, 5> normalizeCurve(std::array<int, 5> curve) {
    std::array<int, 5> result{};
    for (size_t i = 0; i < result.size(); ++i) {
        const int minValue = (i == 0) ? kMinCurveTempC : result[i - 1] + 1;
        const int maxValue = kMaxCurveTempC - static_cast<int>(result.size() - 1 - i);
        result[i] = std::clamp(curve[i], minValue, maxValue);
    }
    return result;
}

std::array<int, 5> parseCurve(const std::string& value,
                              const std::array<int, 5>& fallback) {
    std::array<int, 5> parsed = fallback;
    std::stringstream stream(value);
    std::string token;
    size_t index = 0;
    while (std::getline(stream, token, ',')) {
        if (index >= parsed.size()) return fallback;
        parsed[index] = parseInt(trim(token), fallback[index]);
        ++index;
    }
    if (index != parsed.size()) return fallback;
    return normalizeCurve(parsed);
}

Config readConfig() {
    Config config;
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

        if (key == "enabled") {
            config.enabled = parseBool(value, config.enabled);
        } else if (key == "mode") {
            config.automatic = value == "auto";
        } else if (key == "manual_level") {
            config.manualLevel = std::clamp(parseInt(value, config.manualLevel), 1, 5);
        } else if (key == "curve") {
            config.curveC = parseCurve(value, config.curveC);
        }
    }

    config.curveC = normalizeCurve(config.curveC);
    return config;
}

bool writeValue(const char* path, int value) {
    const int fd = open(path, O_WRONLY | O_CLOEXEC);
    if (fd < 0) {
        LOGE("open %s failed: %s", path, strerror(errno));
        return false;
    }

    char buffer[16];
    const int length = snprintf(buffer, sizeof(buffer), "%d\n", value);
    const ssize_t written = write(fd, buffer, static_cast<size_t>(length));
    const int savedErrno = errno;
    close(fd);
    if (written != length) {
        LOGE("write %s=%d failed: %s", path, value, strerror(savedErrno));
        return false;
    }
    return true;
}

bool publishLightingFanState(bool active) {
    const int fd = open(kLightingFanState, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0660);
    if (fd < 0) {
        LOGW("open %s failed: %s", kLightingFanState, strerror(errno));
        return false;
    }

    const char* payload = active ? "1\n" : "0\n";
    const size_t length = 2;
    const ssize_t written = write(fd, payload, length);
    const int savedErrno = errno;
    close(fd);
    if (written != static_cast<ssize_t>(length)) {
        LOGW("write %s failed: %s", kLightingFanState, strerror(savedErrno));
        return false;
    }
    return true;
}

bool applyFanLevel(int level) {
    level = std::clamp(level, 0, 5);
    if (level == 0) {
        const bool levelOk = writeValue(kFanLevel, 0);
        const bool enableOk = writeValue(kFanEnable, 0);
        return levelOk && enableOk;
    }

    const bool firstEnableOk = writeValue(kFanEnable, 1);
    const bool levelOk = writeValue(kFanLevel, level);
    const bool secondEnableOk = writeValue(kFanEnable, 1);
    return firstEnableOk && levelOk && secondEnableOk;
}

bool readIntFile(const std::string& path, int* output) {
    std::ifstream input(path);
    if (!input.is_open()) return false;
    int value = 0;
    input >> value;
    if (!input.good() && !input.eof()) return false;
    *output = value;
    return true;
}

bool readCpuTemperatureMc(int* output) {
    int64_t total = 0;
    int count = 0;
    for (const int id : kCpuZoneIds) {
        int value = 0;
        const std::string path = "/sys/class/thermal/thermal_zone" +
                                 std::to_string(id) + "/temp";
        if (readIntFile(path, &value) && value >= 0 && value <= 150000) {
            total += value;
            ++count;
        }
    }
    if (count == 0) return false;
    *output = static_cast<int>(total / count);
    return true;
}

int autoFanLevel(int tempMc, const std::array<int, 5>& curveC, int currentLevel) {
    int target = 0;
    for (size_t i = 0; i < curveC.size(); ++i) {
        if (tempMc >= curveC[i] * 1000) target = static_cast<int>(i + 1);
    }

    if (target < currentLevel && currentLevel > 0) {
        const int currentThresholdMc = curveC[static_cast<size_t>(currentLevel - 1)] * 1000;
        if (tempMc >= currentThresholdMc - kHysteresisMc) return currentLevel;
    }
    return target;
}

class ConfigWatcher {
  public:
    ConfigWatcher() {
        fd_ = inotify_init1(IN_CLOEXEC | IN_NONBLOCK);
        if (fd_ < 0) {
            LOGE("inotify_init1 failed: %s", strerror(errno));
            return;
        }
        addWatch();
    }

    ~ConfigWatcher() {
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
                const auto* event = reinterpret_cast<const struct inotify_event*>(buffer + offset);
                if (event->mask & (IN_DELETE_SELF | IN_MOVE_SELF | IN_IGNORED)) watch_ = -1;
                if (event->len > 0 && std::string(event->name) == "config.conf") {
                    if (event->mask & (IN_CLOSE_WRITE | IN_MOVED_TO | IN_CREATE | IN_ATTRIB)) {
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
        watch_ = inotify_add_watch(
                fd_, kConfigDir,
                IN_CLOSE_WRITE | IN_MOVED_TO | IN_CREATE | IN_ATTRIB |
                        IN_DELETE_SELF | IN_MOVE_SELF);
        if (watch_ < 0) LOGW("inotify watch %s failed: %s", kConfigDir, strerror(errno));
    }

    int fd_ = -1;
    int watch_ = -1;
};

int desiredLevel(const Config& config, int currentLevel) {
    if (!config.enabled) return 0;
    if (!config.automatic) return config.manualLevel;

    int tempMc = 0;
    if (!readCpuTemperatureMc(&tempMc)) {
        LOGW("no valid CPU thermal zones; keeping fan level %d", currentLevel);
        return std::max(currentLevel, 0);
    }
    return autoFanLevel(tempMc, config.curveC, std::max(currentLevel, 0));
}

}  // namespace

int main() {
    LOGI("starting");

    Config config = readConfig();
    ConfigWatcher watcher;
    int currentLevel = -1;

    auto applyDesired = [&]() {
        const int desired = desiredLevel(config, currentLevel);
        if (desired != currentLevel && applyFanLevel(desired)) {
            currentLevel = desired;
            publishLightingFanState(currentLevel > 0);
            LOGI("fan level=%d mode=%s", currentLevel,
                 config.automatic ? "auto" : "manual");
        }
    };

    applyDesired();

    while (true) {
        watcher.ensureWatch();
        int timeoutMs = (config.enabled && config.automatic) ? kAutoIntervalMs : -1;
        if (!watcher.active()) timeoutMs = 1000;

        struct pollfd pfd {};
        pfd.fd = watcher.fd();
        pfd.events = POLLIN;

        const int result = (pfd.fd >= 0) ? poll(&pfd, 1, timeoutMs) : poll(nullptr, 0, 1000);
        if (result < 0) {
            if (errno == EINTR) continue;
            LOGE("poll failed: %s", strerror(errno));
            usleep(250000);
            continue;
        }

        bool configChanged = false;
        if (result > 0 && (pfd.revents & POLLIN)) configChanged = watcher.consumeChanged();

        if (configChanged) {
            const Config updated = readConfig();
            if (updated != config) {
                config = updated;
                LOGI("configuration updated: enabled=%d mode=%s level=%d curve=%d,%d,%d,%d,%d",
                     config.enabled, config.automatic ? "auto" : "manual", config.manualLevel,
                     config.curveC[0], config.curveC[1], config.curveC[2],
                     config.curveC[3], config.curveC[4]);
            }
            applyDesired();
        } else if (result == 0 && config.enabled && config.automatic) {
            applyDesired();
        }
    }
}
