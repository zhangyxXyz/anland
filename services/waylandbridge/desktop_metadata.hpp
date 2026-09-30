#pragma once
#include <string>
#include <vector>
#include <sys/types.h>

struct DesktopMetadata {
    std::string name;
    std::vector<unsigned char> icon;
};

// Match the client's PID namespace and root to a registered Droidspaces init.
// Unknown or ambiguous registrations deliberately return no label.
std::string window_container_name(pid_t pid, const std::string& proc_dir = "/proc",
        const std::string& pids_dir = "/data/local/Droidspaces/Pids");

std::vector<std::string> registered_containers(
        const std::string& containers_dir = "/data/local/Droidspaces/Containers",
        const std::string& pids_dir = "/data/local/Droidspaces/Pids");
// Socket routing is independent of the human-readable container label.
std::string window_session_key(pid_t pid, const std::string& proc_dir = "/proc");
bool same_window_session(pid_t client, pid_t peer, const std::string& proc_dir = "/proc");

// Resolve a client's desktop ID inside its own filesystem namespace.
DesktopMetadata desktop_metadata(pid_t pid, const std::string& app_id,
                                 const std::string& locale);
// The root-fd entry point also allows isolated regression fixtures.
DesktopMetadata desktop_metadata_at(int root, const std::string& app_id,
        const std::string& locale, const std::vector<std::string>& data_dirs);
