#pragma once
#include <string>
#include <vector>
#include <sys/types.h>

struct DesktopMetadata {
    std::string name;
    std::vector<unsigned char> icon;
};

// Resolve a client's desktop ID inside its own filesystem namespace.
DesktopMetadata desktop_metadata(pid_t pid, const std::string& app_id,
                                 const std::string& locale);
// The root-fd entry point also allows isolated regression fixtures.
DesktopMetadata desktop_metadata_at(int root, const std::string& app_id,
        const std::string& locale, const std::vector<std::string>& data_dirs);
