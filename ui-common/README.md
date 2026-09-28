# Shared MaterialDesignTmpl framework

Derived from `zhangyxXyz/MaterialDesignTmpl` commit
`80b1f00e18bbb0d6940e1b3db037db5975ffd792` (the user-supplied `E:/diy/MaterialDesignTmpl`).
The MIT license is retained in `LICENSE.MaterialDesignTmpl`.

`ShellTheme`, appearance models, grouped settings/help and the liquid-glass
navigation implementation are retained from the template. `AnlandShell` adapts
its responsive `MainShell` navigation into feature slots used by both APKs.
Its 840 dp navigation-rail breakpoint, maximum content width and system insets
are layout policies, not device model or physical-resolution assumptions.

The template's sample workflow data, repository credentials, signing keys,
update provider, backup accounts and sample permissions are not business logic
for these apps and are not copied. App package IDs and CI signing keys remain
those of the original apps. Native Wayland windows still use libawl; this
Compose framework only hosts management/launcher screens.

Each consuming Gradle project gets separate generated output directories, so
building the two apps concurrently cannot replace each other's shared classes.
