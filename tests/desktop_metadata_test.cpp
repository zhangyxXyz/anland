#include "desktop_metadata.hpp"
#include <cassert>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <fcntl.h>
#include <unistd.h>

namespace fs = std::filesystem;
static void put(const fs::path& path, const std::string& value) {
    fs::create_directories(path.parent_path());
    std::ofstream(path, std::ios::binary) << value;
}
int main(int argc, char** argv) {
    if (argc == 4) {
        auto m = desktop_metadata((pid_t)std::stoi(argv[1]), argv[2], argv[3]);
        std::cout << m.name << "\nicon bytes=" << m.icon.size() << "\n";
        return m.name.empty() ? 1 : 0;
    }
    char temp[] = "/data/local/tmp/anland-metadata-test-XXXXXX";
    // Linux CI and Android use the same tests, with no device-specific data.
    char host_temp[] = "/tmp/anland-metadata-test-XXXXXX";
    char* created = mkdtemp(access("/data/local/tmp", W_OK) == 0 ? temp : host_temp);
    assert(created);
    fs::path root(created);
    const std::vector<std::string> dirs{"/home/test/.local/share", "/usr/local/share", "/usr/share"};
    auto system = root / "usr/share/applications";
    auto user = root / "home/test/.local/share/applications";
    put(system / "org.example.Editor.desktop",
        "[Desktop Entry]\nType=Application\nName=Example Editor\nName[zh]=编辑器\nName[zh_CN]=示例编辑器\n"
        "Icon=example-editor\nStartupWMClass=ExampleWindow\n[Desktop Action Private]\nName=Wrong Action\n");
    put(root / "usr/share/icons/hicolor/128x128/apps/example-editor.png", std::string("\x89PNG\r\n\x1a\n", 8));
    int fd = open(root.c_str(), O_RDONLY | O_DIRECTORY);
    assert(fd >= 0);
    auto m = desktop_metadata_at(fd, "org.example.Editor", "zh_CN.UTF-8", dirs);
    assert(m.name == "示例编辑器" && m.icon.size() == 8);
    assert(desktop_metadata_at(fd, "org.example.Editor", "zh_TW", dirs).name == "编辑器");
    assert(desktop_metadata_at(fd, "ExampleWindow", "en_US", dirs).name == "Example Editor");
    put(user / "org.example.Editor.desktop", "[Desktop Entry]\nType=Application\nName=My\\sEditor\n");
    assert(desktop_metadata_at(fd, "org.example.Editor", "en", dirs).name == "My Editor");
    put(user / "org.example.Editor.desktop", "[Desktop Entry]\nHidden=true\n");
    assert(desktop_metadata_at(fd, "org.example.Editor", "en", dirs).name.empty());
    assert(desktop_metadata_at(fd, "ExampleWindow", "en", dirs).name.empty());
    put(system / "tools/drawing.desktop", "[Desktop Entry]\nType=Application\nName=Drawing\nStartupWMClass=SharedClass\n");
    assert(desktop_metadata_at(fd, "tools-drawing.desktop", "en", dirs).name == "Drawing");
    put(system / "other.desktop", "[Desktop Entry]\nType=Application\nName=Other\nStartupWMClass=SharedClass\n");
    assert(desktop_metadata_at(fd, "SharedClass", "en", dirs).name.empty());
    // Absolute symlinks must resolve inside the fixture/container, not host /tmp.
    put(root / "tmp/private.desktop", "[Desktop Entry]\nType=Application\nName=Container\n");
    fs::create_symlink("/tmp/private.desktop", system / "link.desktop");
    assert(desktop_metadata_at(fd, "link", "en", dirs).name == "Container");
    assert(desktop_metadata_at(fd, "../../etc/passwd", "en", dirs).name.empty());
    assert(desktop_metadata_at(fd, "missing", "en", dirs).name.empty());
    put(system / "vector.desktop", "[Desktop Entry]\nType=Application\nName=Vector App\nIcon=vector-app\n");
    put(root / "usr/share/icons/hicolor/scalable/apps/vector-app.svg", "<svg xmlns=\"http://www.w3.org/2000/svg\"/>");
    assert(!desktop_metadata_at(fd, "vector", "en", dirs).icon.empty());
    put(root / "usr/share/icons/hicolor/scalable/apps/vector-app.svg", "<!DOCTYPE svg><svg/>");
    assert(desktop_metadata_at(fd, "vector", "en", dirs).icon.empty());
    // Encoded file size is independent of the Android task icon's display size.
    // Exercise a normal large PNG, the inclusive 512 KiB bound and rejection.
    put(system / "large.desktop", "[Desktop Entry]\nType=Application\nName=Large Icon\nIcon=large-icon\n");
    std::string large_png("\x89PNG\r\n\x1a\n", 8);
    large_png.resize(216 * 1024, '\0');
    put(root / "usr/share/pixmaps/large-icon.png", large_png);
    assert(desktop_metadata_at(fd, "large", "en", dirs).icon.size() == large_png.size());
    large_png.resize(512 * 1024, '\0');
    put(root / "usr/share/pixmaps/large-icon.png", large_png);
    assert(desktop_metadata_at(fd, "large", "en", dirs).icon.size() == large_png.size());
    large_png.push_back('\0');
    put(root / "usr/share/pixmaps/large-icon.png", large_png);
    assert(desktop_metadata_at(fd, "large", "en", dirs).icon.empty());
    close(fd);
    fs::remove_all(root); // only the directory returned by mkdtemp above
    std::cout << "desktop metadata tests passed\n";
}
