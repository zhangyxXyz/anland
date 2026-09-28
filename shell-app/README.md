# Anland Shell

Launcher business logic imported from `SuperTurtleDev/anland-shell` commit
`920fcfc8159690f5232e2d9c7c3294d4c1481145`; upstream GPL license retained.
Changes are maintained with the Anland `dev` branch to build the two APKs and
their shared MaterialDesignTmpl framework from the same source revision.

* `ShellState.kt`: container selection, lifecycle operations, users and app inventory.
* `AppsScreen.kt`: app search/grid, generic desktop icons and pinned shortcuts.
* `ContainersScreen.kt`: select/start/stop/console, with confirmation before stop.
* `ShellSettingsScreen.kt`: appearance, launch user/environment, Wayland entry.
* `ds/`, `Prefs`, `Shortcuts`, `AppLaunchActivity`, `ConsoleActivity`, `IconLoader`:
  original business interfaces and comments retained. The terminal renderer and
  transient launch screen remain native Android views.

The app keeps package `com.anland.shell`, preference file `shell`, shortcut IDs,
explicit intent extras and the existing CI signing key. Opening the launcher
retains upstream's once-per-instance auto-start behavior; opening a shortcut
independently ensures the selected container is running.
