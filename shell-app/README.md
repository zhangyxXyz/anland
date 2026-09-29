# Anland Shell

Android package: `com.anland.shell`.

The launcher manages Droidspaces containers, Linux application entries, shortcuts, console access, local/SSH logins and display-backend selection. Its Compose screens share `ui-common` with the Wayland host. Each Gradle project uses a separate shared-UI output directory.

Build with `../scripts/build.ps1 -Component shell` on the configured Windows machine, or `python3 ../scripts/build-apps.py --component shell`. Version and signing configuration are documented in the [repository README](../README.md#signing-and-local-apk-builds).
