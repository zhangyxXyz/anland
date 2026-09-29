# Shared UI

Shared Compose themes, responsive navigation and grouped settings for the Shell and Wayland Apps. The navigation rail breakpoint is 840 dp; content width and insets adapt to the current window.

Native Wayland windows use `libawl`; this module provides launcher and management UI. Each consuming Gradle project uses separate generated output directories so parallel builds do not overwrite shared classes.

The MaterialDesignTmpl UI components retain their [MIT license](LICENSE.MaterialDesignTmpl).
