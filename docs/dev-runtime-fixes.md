# Runtime fixes on dev

These changes use window, application and session data, not a device model,
display resolution, username or hardcoded list of application names.

* xdg fullscreen/maximize configures use the measured Android surface converted
  by the current fractional scale. Only an unattached window uses the initial
  placeholder size. Fullscreen/maximized/activated state survives later focus
  and resize events; each configure receives a fresh serial.
* PulseAudio creates its runtime directory before staging its libraries, and
  records copy errors. Session clients receive the host endpoint even when
  PulseAudio starts later. The Debian image also provides a PipeWire PulseAudio
  tunnel with reconnection for clients outside the Anland session.
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
