# Runtime fixes on dev

These changes use window, application and session data, not a device model,
display resolution, username or hardcoded list of application names.

* xdg fullscreen/maximize configures use the measured Android surface converted
  by the current fractional scale. Only an unattached window uses the initial
  placeholder size. Fullscreen/maximized/activated state survives later focus
  and resize events; each configure receives a fresh serial.
* The host measures Android rounded corners, display cutouts, system bars and
  the keyboard, then resizes the content surface to its safe rectangle. Insets
  are recalculated on layout/rotation; input stays in the same surface coordinates.
* PulseAudio creates its runtime directory before staging its libraries, and
  records copy errors. Session clients receive the host endpoint even when
  PulseAudio starts later. The Debian image also provides a PipeWire PulseAudio
  tunnel with reconnection for clients outside the Anland session.
  On module startup, audio staging and the Android-sink readiness probe run
  before the graphical daemon is launched. Reconnection is only recovery from
  a later connection loss, not the fix for missing runtime directories or
  incorrect initial routing.
* Module updates preserve existing renderer, zoom and runtime-directory settings.
* Recents identity uses xdg app_id and .desktop metadata from the client's own
  filesystem root. XDG data directory precedence, localized Name values and
  unambiguous StartupWMClass matches are supported. Unknown clients retain
  their window title. Window icons take precedence over desktop PNG/WebP/JPEG
  or SVG icons from an absolute Icon path, hicolor or pixmaps. This is not a
  complete implementation of user-selected icon-theme inheritance.
* Metadata file access uses openat2 with RESOLVE_IN_ROOT and NO_MAGICLINKS,
  bounded reads and traversal. Kernels without openat2 fall back to the
  window's title/icon. No shell commands from desktop files are executed.
* Icon fetches are ordered; stale or failed replies cannot erase a valid icon.
  A protocol icon reset still clears it and re-resolves desktop metadata.
* Debian's Chrome launcher uses native Wayland and ANGLE GL by default. It
  accepts ANLAND_CHROME_ANGLE for a different backend and passes through user
  arguments. It no longer forces a particular DRM node or unverified VAAPI
  features. Hardware video decoding remains a separate capability to verify.

The rootfs builder stays pinned to its upstream revision. The workflow applies
reviewed overrides from this repository's dev commit and publishes directly to
draft Releases. main remains untouched.

The AAR now needs `com.caverock:androidsvg-aar:1.4` for desktop SVG icons when
consumed outside this Gradle project. The project APK includes it transitively.
AndroidSVG is Apache-2.0: https://bigbadaboom.github.io/androidsvg/ .

Validation includes Android APK/AAR compilation, metadata regression fixtures
(localization, user overrides, action groups, nested IDs, ambiguous WM classes,
absolute container symlinks, missing IDs, raster/SVG icons), and on-device
metadata lookups against actual installed applications. Full end-to-end
fullscreen/rotation/zoom and Recents verification requires installing the new
host APK and daemon together. Building does not replace the running module.

## IME and transient windows (2026-09-29)

The host restores Wayland keyboard/text-input focus after Android document
re-entry. Attach generations reject delayed pause/focus reports from obsolete
surfaces. A parent Activity keeps its render surface while a floating child
is visible, and releases it when actually stopped.

InputConnection preserves text when finishing composition, treats composing
regions as metadata until replacement, and sends reconversion as one atomic
Wayland delete+commit/preedit transaction. Selection/anchor queries use Android
UTF-16 positions; protocol deletion lengths use UTF-8 bytes.

Native dialogs use xdg parent/size hints and xdg-foreign-v2 imported parent
relationships, including GTK dialogs exported by another client. The Android
dialog shares its parent's task, observes available display bounds, and closes
without removing the parent. It does not classify applications by their name.

Validation on the installed APK/daemon: InputConnection contract probe (11
cases), 10 real document re-entry rounds, injected stale pause/focus reports,
native foreign-parent contract checks under UBSan, actual VS Code save-dialog
size/task/cancel checks, and portrait/landscape rotation. The APK was installed
with the existing signing identity and the native daemon updated without a
device reboot or replacing the Debian container.

A separate VS Code 1.139.1 EditContext cache bug polluted surrounding text
despite a correct visible editor. Its root-cause source patch and guarded,
reversible installed-package patcher are in `patches/vscode/`. Sogou English
candidate selection, subsequent input, word recomposition and delete/retype
were checked against both the real EditContext and Wayland trace. The same
sequence also passed in GTK Entry. See that directory's README for package
upgrade and rollback details. This incremental container patch does not
change the previously downloaded rootfs image.
