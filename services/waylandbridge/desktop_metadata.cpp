#include "desktop_metadata.hpp"
#include <algorithm>
#include <cstdint>
#include <dirent.h>
#include <fcntl.h>
#include <fstream>
#include <linux/openat2.h>
#include <map>
#include <sstream>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

namespace {
// Leave room for metadata and other transactions in Binder's shared buffer.
// Desktop icon files commonly exceed 128 KiB; Android downsamples the decoded
// image to the task-icon size, so the encoded file budget is a separate limit.
constexpr size_t kMaxIconBytes = 512 * 1024;

// Resolve absolute symlinks against the container root, never Android's root.
// Older kernels without openat2 safely fall back to the window-provided icon/title.
int open_in_root(int root, const std::string& path, int flags) {
    struct open_how how = {};
    how.flags = flags | O_CLOEXEC;
    how.resolve = RESOLVE_IN_ROOT | RESOLVE_NO_MAGICLINKS;
    return (int)syscall(SYS_openat2, root, path.c_str(), &how, sizeof(how));
}

std::string read_file(int root, const std::string& path, size_t limit) {
    int fd = open_in_root(root, path, O_RDONLY | O_NONBLOCK);
    if (fd < 0) return {};
    struct stat st = {};
    if (fstat(fd, &st) || !S_ISREG(st.st_mode) || st.st_size < 0 ||
            (uint64_t)st.st_size > limit) { close(fd); return {}; }
    std::string data;
    char buf[4096];
    ssize_t n;
    while ((n = read(fd, buf, sizeof(buf))) > 0) {
        data.append(buf, (size_t)n);
        if (data.size() > limit) { data.clear(); break; }
    }
    close(fd);
    return n < 0 ? std::string() : data;
}

std::string trim(std::string s) {
    auto first = s.find_first_not_of(" \t\r\n");
    if (first == std::string::npos) return {};
    return s.substr(first, s.find_last_not_of(" \t\r\n") - first + 1);
}

std::string unescape(const std::string& s) {
    std::string out;
    for (size_t i = 0; i < s.size(); ++i) {
        if (s[i] == '\\' && i + 1 < s.size()) {
            switch (s[++i]) {
                case 's': out += ' '; break;
                case 'n': out += '\n'; break;
                case 't': out += '\t'; break;
                case 'r': out += '\r'; break;
                default: out += s[i]; break;
            }
        } else out += s[i];
    }
    return out;
}

using Entry = std::map<std::string, std::string>;
Entry parse(const std::string& text) {
    Entry out;
    bool main = false;
    std::istringstream lines(text);
    std::string line;
    while (std::getline(lines, line)) {
        line = trim(line);
        if (line.empty() || line[0] == '#') continue;
        if (line[0] == '[') { main = line == "[Desktop Entry]"; continue; }
        auto eq = line.find('=');
        if (main && eq != std::string::npos)
            out[trim(line.substr(0, eq))] = unescape(trim(line.substr(eq + 1)));
    }
    return out;
}

std::vector<std::string> locales(std::string locale) {
    auto dot = locale.find('.');
    if (dot != std::string::npos) {
        auto modifier = locale.find('@', dot);
        locale.erase(dot, modifier == std::string::npos ? modifier : modifier - dot);
    }
    std::replace(locale.begin(), locale.end(), '-', '_');
    std::vector<std::string> result{locale};
    auto at = locale.find('@');
    auto under = locale.find('_');
    if (at != std::string::npos && under != std::string::npos)
        result.push_back(locale.substr(0, under) + locale.substr(at));
    if (at != std::string::npos) result.push_back(locale.substr(0, at));
    if (under != std::string::npos) result.push_back(locale.substr(0, under));
    result.push_back("");
    return result;
}

void files(int root, const std::string& base, const std::string& relative,
           int depth, size_t& budget, std::vector<std::string>& result) {
    if (!budget || depth > 5) return;
    int fd = open_in_root(root, base + relative, O_RDONLY | O_DIRECTORY);
    if (fd < 0) return;
    DIR* dir = fdopendir(fd);
    if (!dir) { close(fd); return; }
    std::vector<std::string> names;
    while (auto* e = readdir(dir)) {
        if (!budget) break;
        if (e->d_name[0] == '.') continue;
        --budget;
        names.emplace_back(e->d_name);
    }
    closedir(dir);
    std::sort(names.begin(), names.end());
    for (const auto& name : names) {
        std::string rel = relative + name;
        if (name.size() > 8 && name.substr(name.size() - 8) == ".desktop")
            result.push_back(rel);
        else files(root, base, rel + "/", depth + 1, budget, result);
    }
}

bool raster(const std::string& bytes) {
    return (bytes.size() >= 8 && bytes.compare(0, 8, "\x89PNG\r\n\x1a\n", 8) == 0)
        || (bytes.size() >= 12 && bytes.compare(0, 4, "RIFF") == 0 && bytes.compare(8, 4, "WEBP") == 0)
        || (bytes.size() >= 3 && (unsigned char)bytes[0] == 0xff && (unsigned char)bytes[1] == 0xd8 && (unsigned char)bytes[2] == 0xff);
}

bool svg(const std::string& bytes) {
    return bytes.find("<svg") != std::string::npos &&
           bytes.find("<!DOCTYPE") == std::string::npos && bytes.find("<!ENTITY") == std::string::npos;
}

std::vector<unsigned char> icon(int root, const std::string& value,
                               const std::vector<std::string>& dirs) {
    if (value.empty()) return {};
    std::vector<std::string> paths;
    if (value[0] == '/') paths.push_back(value);
    else if (value.find('/') == std::string::npos && value != "..") {
        for (const auto& dir : dirs) {
            // Freedesktop's mandatory fallback theme. Prefer task-sized rasters.
            for (const auto* size : {"128x128", "64x64", "256x256", "48x48", "32x32", "512x512"})
                for (const auto* ext : {".png", ".webp", ".jpg", ""})
                    paths.push_back(dir + "/icons/hicolor/" + size + "/apps/" + value + ext);
            paths.push_back(dir + "/icons/hicolor/scalable/apps/" + value + ".svg");
            for (const auto* ext : {".png", ".webp", ".jpg", ".svg", ""})
                paths.push_back(dir + "/pixmaps/" + value + ext);
        }
    }
    for (const auto& path : paths) {
        auto bytes = read_file(root, path, kMaxIconBytes);
        if (raster(bytes) || svg(bytes)) return {bytes.begin(), bytes.end()};
    }
    return {};
}
} // namespace

DesktopMetadata desktop_metadata_at(int root, const std::string& app_id,
        const std::string& locale, const std::vector<std::string>& dirs) {
    if (app_id.empty() || app_id.size() > 255 || app_id.find('/') != std::string::npos ||
            app_id == "." || app_id == "..") return {};
    std::string id = app_id;
    if (id.size() > 8 && id.substr(id.size() - 8) == ".desktop") id.resize(id.size() - 8);
    Entry selected, wm_match;
    std::map<std::string, bool> seen;
    size_t budget = 4096;
    int matches = 0;
    bool exact = false;
    for (const auto& dir : dirs) {
        std::vector<std::string> entries;
        files(root, dir + "/applications/", "", 0, budget, entries);
        for (const auto& path : entries) {
            std::string desktop_id = path.substr(0, path.size() - 8);
            std::replace(desktop_id.begin(), desktop_id.end(), '/', '-');
            if (!seen.emplace(desktop_id, true).second) continue; // XDG precedence incl. Hidden
            auto e = parse(read_file(root, dir + "/applications/" + path, 64 * 1024));
            if (desktop_id == id) { selected = e; exact = true; break; }
            if (e["Hidden"] != "true" && e["Type"] == "Application" && e["StartupWMClass"] == app_id) {
                wm_match = e;
                ++matches;
            }
        }
        if (exact) break;
    }
    if (!exact && matches == 1) selected = wm_match;
    if (selected["Hidden"] == "true" || selected["Type"] != "Application") return {};
    DesktopMetadata result;
    for (const auto& loc : locales(locale)) {
        auto name = selected[loc.empty() ? "Name" : "Name[" + loc + "]"];
        if (!name.empty()) { result.name = name.substr(0, 4096); break; }
    }
    result.icon = icon(root, selected["Icon"], dirs);
    return result;
}

std::string window_container_name(pid_t pid, const std::string& proc_dir, const std::string& pids_dir) {
    if (pid <= 0) return {};
    struct stat client_ns{}, client_root{};
    const auto client = proc_dir + "/" + std::to_string(pid);
    if (stat((client + "/ns/pid").c_str(), &client_ns) ||
        stat((client + "/root").c_str(), &client_root)) return {};
    DIR* directory = opendir(pids_dir.c_str());
    if (!directory) return {};
    std::string match;
    bool ambiguous = false;
    size_t count = 0;
    while (auto entry = readdir(directory)) {
        if (++count > 256) { ambiguous = true; break; }
        std::string file = entry->d_name;
        if (file.size() <= 4 || file.size() > 132 || file.substr(file.size() - 4) != ".pid") continue;
        auto name = file.substr(0, file.size() - 4);
        if (std::any_of(name.begin(), name.end(), [](unsigned char c) { return c < 32 || c == 127; })) continue;
        std::ifstream registration(pids_dir + "/" + file);
        int init = 0;
        std::string extra;
        if (!(registration >> init) || init <= 0 || (registration >> extra)) continue;
        struct stat init_ns{}, init_root{};
        const auto process = proc_dir + "/" + std::to_string(init);
        if (stat((process + "/ns/pid").c_str(), &init_ns) ||
            stat((process + "/root").c_str(), &init_root)) continue;
        if (client_ns.st_dev != init_ns.st_dev || client_ns.st_ino != init_ns.st_ino ||
            client_root.st_dev != init_root.st_dev || client_root.st_ino != init_root.st_ino) continue;
        if (!match.empty()) { ambiguous = true; break; }
        match = name;
    }
    closedir(directory);
    return ambiguous ? std::string{} : match;
}

DesktopMetadata desktop_metadata(pid_t pid, const std::string& app_id, const std::string& locale) {
    if (pid <= 0) return {};
    std::string proc = "/proc/" + std::to_string(pid);
    int root = open((proc + "/root").c_str(), O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (root < 0) return {};
    std::ifstream envfile(proc + "/environ", std::ios::binary);
    std::map<std::string, std::string> env;
    std::string pair;
    size_t total = 0;
    while (std::getline(envfile, pair, '\0') && (total += pair.size()) < 128 * 1024) {
        auto eq = pair.find('=');
        if (eq != std::string::npos) env[pair.substr(0, eq)] = pair.substr(eq + 1);
    }
    std::vector<std::string> dirs;
    auto home = env["XDG_DATA_HOME"];
    if (home.empty() && !env["HOME"].empty()) home = env["HOME"] + "/.local/share";
    if (!home.empty() && home[0] == '/') dirs.push_back(home);
    auto system = env["XDG_DATA_DIRS"];
    if (system.empty()) system = "/usr/local/share:/usr/share";
    std::istringstream parts(system);
    while (std::getline(parts, pair, ':') && dirs.size() < 16)
        if (!pair.empty() && pair[0] == '/') dirs.push_back(pair);
    auto result = desktop_metadata_at(root, app_id, locale, dirs);
    close(root);
    return result;
}
