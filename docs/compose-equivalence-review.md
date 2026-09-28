# Compose migration: equivalence review and device validation

Baseline: Anland dev `5232d0c` and Anland Shell upstream
`920fcfc8159690f5232e2d9c7c3294d4c1481145`. Framework:
MaterialDesignTmpl `80b1f00e18bbb0d6940e1b3db037db5975ffd792`.

This is a verification checklist, not a statement that all device tests passed.

| Capability | Migrated implementation / preserved contract | Source review | Device |
|---|---|---|---|
| Host window list / bring forward | MainActivity, WindowsPage, unchanged Awl.attachWindow | checked | pending |
| Window lifecycle events | subscribe + resumed snapshot; 60 ms event coalescing; restart re-subscribe | checked | pending |
| Explicit close / window info | overflow dialog; unchanged daemon Close request | checked | pending |
| Minimize keeps client alive | unchanged libawl/daemon policy | checked | pending |
| IME inset / overlay | original `awl.ime_mode` preference | checked | pending |
| Scale 50–300%, five presets | 200 ms debounce, immediate release/preset; daemon source of truth | checked | pending |
| Stretch / fit / centered | original scale_mode values 0/1/2 | checked | pending |
| XWayland scaling | original xwayland_scale config | checked | pending |
| Auto window launch | original auto_attach; clearer title and home switch | checked | pending |
| Initial size | same bounds, arbitrary input + four original presets; explicit Apply for text input | checked | pending |
| SC / GL backend | same sc_enabled config; new attachments only | checked | pending |
| Open settings must not mutate config | read-only initial state; handlers only on user actions | checked | pending |
| Config write failure | re-read actual daemon config and show failure | checked | pending |
| Container listing / active selection | unchanged DsCli; original `shell.active_container` preference | checked | pending |
| Auto start on opening launcher | retained once per instance | checked | pending |
| Start / stop / stop confirmation | unchanged DsCli + awaitRunning; explicit stop confirmation | checked | pending |
| Console + history / font / environment | original ConsoleActivity and Prefs retained | checked | pending |
| Desktop app enumeration / localization / icons | unchanged DesktopEntry, DsCli and IconLoader | checked | pending |
| Launch / pinned shortcuts | original AppLaunchActivity, intent extras, shortcut IDs, icon wait | checked | pending |
| User selection / XWayland probe | original per-container preference; re-probe on user change | checked | pending |
| Environment defaults, override, clear, validation | original EnvVars/Prefs; invalid lines keep editor open | checked | pending |
| Upgrade compatibility | same package IDs, preference names and CI signing cache paths | checked | pending |
| Theme + navigation | template components; phone bar / wide-window rail; persistent appearance | compiled | pending |
| GPU / audio / fullscreen / rounded corners | prior native fixes retained; new module not installed automatically | reviewed | pending module update |

Review corrections made before device testing: restored initial-size presets,
auto-start, per-user XWayland detection, complete app-info fields, ENV clear,
waiting for shortcut icons, event coalescing and zoom debounce. Preserved the
old signing-cache path when moving the Shell build into this repository.

Intentional UI changes: overflow replaces hidden long-press for window actions;
initial dimensions use validated numeric inputs with Apply rather than a long
NumberPicker. The terminal renderer and translucent launch progress screen
remain native views. No full desktop session has been installed or claimed.

Do not stop an existing container, reboot, or install a root module as part of
an unattended test. Mark those checks pending until explicitly authorized.
