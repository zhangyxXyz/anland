# Testing and runtime behavior

## Build and packaging checks

```sh
python3 -m unittest discover -s tests -p 'test_ci_*.py' -v
actionlint
```

The release tests cover tag/version agreement, component and distribution selection, retry identity, draft ownership, partial failures, skipped components, asset verification, split-image completeness, generated module versions and exported RootFS bytes. RootFS integration tests cover each target's native package installer and isolated build/export stages. They do not call GitHub or create Releases.

The local build wrapper and CI both execute `assembleRelease`, `assembleReleaseAndroidTest`, `lintRelease` and `:ui-common:testReleaseUnitTest`. Wayland additionally produces the client AAR. Final APKs are checked against public certificate fingerprints and `version.properties`. The module ZIP is checked for its version, boot scripts, audio binary and matching daemon.

App backup and update checks:

```sh
cd shell-app
./gradlew :ui-common:testDebugUnitTest
adb -s DEVICE_SERIAL shell am instrument -w -e mode maintenance \
  com.anland.shell.test/com.anland.shell.ui.IconInstrumentation
```

The unit tests cover encrypted archives, path traversal, WebDAV authentication and app-specific retention, restored preference types, and release selection against component manifests. The instrumentation requires the matching Shell app and test APK; it uses disposable preferences to verify backup restoration, preserved command history, rejection of another app's backup, and Keystore-backed secrets. It does not replace the user's settings or connection vault. Publishing and installing a real GitHub update require a public stable Release with its completed manifest and matching signed APK.

Linux-only session tests:

```sh
python3 tests/session_startup_test.py
python3 tests/desktop_session_probe_test.py
```

The first exercises session startup with a delayed audio endpoint and checks compatibility-binary precedence. The second checks full-desktop routing, stale state and private session environment selection. CI also runs `WindowSafeAreaTest` and `desktop_metadata_test.cpp` for Wayland builds.

RootFS construction checks the selected distribution, session package version, compiled Xfdesktop files, AArch64 binaries, dynamic dependencies, the packaged Xwayland path, desktop launchers and icons. Archive verification checks the final exported bytes against the build manifest and input provenance. These checks require a successful Docker build of the selected target; the unit tests alone do not confirm an image builds or runs. They do not exercise a physical GPU or touchscreen.

## Window launch and identity

Window identity is resolved from the window's application ID, desktop metadata and raster/SVG icons before creating a new Android document task. Later title/icon events update that identity. Missing metadata falls back to the available window title and host icon. Desktop launchers should declare the correct `StartupWMClass` or application ID; no application-name alias table is required.

Transient dialogs share the parent Android task. Shell snapshots existing windows and the live `auto_attach` setting before starting a Linux command. Existing windows use explicit activation; newly created auto-attached windows are presented by the daemon. Launch coordination stops when it loses foreground ownership, so leaving the launcher cannot cause a delayed focus steal.

Task identity fixture:

1. Run `tests/task_identity_fixture.py` in the container to create a disposable GTK window and desktop entry. Temporarily disable automatic attachment for this fixture and restore it after mapping.
2. Install the matching `anland-wayland-tests.apk`.
3. Run the test on Android, replacing `WINDOW_ID`:

   ```sh
   am instrument -w -e window_id WINDOW_ID -e label 'Task identity fixture' \
     com.anlandnext.test/com.anlandnext.awl.TaskIdentityInstrumentation
   ```

4. Close the fixture. Its temporary desktop metadata is removed on normal exit.

The instrumentation checks the initial task label and icon before first focus. Compiling the test APK does not execute this test.

## Device checks

Use a matched App/daemon pair when checking window activation and lifecycle. Relevant probes in `tests/` cover IME composition/digits, input focus, transient dialogs, viewport/inset animation, resize handling and task identity. Read each probe's arguments and device requirements before running it.

Check independent application launches and the full desktop separately. The full desktop has its own Xwayland display and D-Bus session; it must not replace the independent-application session environment.

Device verification includes first-launch icons, existing-window reuse, Home/Back during a pending launch, multiple-window selection, touch activation, IME composition, rotation, resize, audio reconnection and GPU rendering. Building or pushing source does not install APKs, replace a running daemon, reboot a device or restart containers.

Useful runtime locations:

| Location | Purpose |
|---|---|
| `/data/local/tmp/awl_daemon.log` | Android daemon log |
| `/data/local/tmp/awl_binder_bridge.log` | Root Binder bridge startup/errors (also logcat tag `anland-bridge`) |
| `/data/adb/modules/anland-awl/config.json` | Daemon configuration |
| `/run/anland` | Container-side host runtime mount |
| `~/.anlandx-env` | Independent-application session environment |
| `$XDG_RUNTIME_DIR/anland-desktop/` | Private full-desktop state |
| `/usr/share/anland/rootfs-components.json` | Image component provenance |

Keep recordings, screenshots, raw logs and device-specific notes in ignored local output directories.

## Binder discovery without system mounts

Install the matched Wayland APK and Root module, select `ignore` for `anland-awl`
in Hybrid Mount, and reboot. Confirm that the module has `skip_mount`, contains
no generated `system/etc/selinux/plat_service_contexts`, and contributes no mount
in `/proc/1/mountinfo`. The bridge must log publication to `com.anlandnext.binder`.
An ordinary app must not gain `find` on `default_android_service`.

Exercise Shell's container selection, independent app launch, full desktop,
existing-window activation, and Wayland settings. Kill/reopen the host App and
restart the daemon separately; discovery must recover without restarting the
container. Update the APK and verify the bridge supervisor loads the new APK.
The `publish` provider method must reject non-root callers. A third-party libawl
client must receive the native Binder and still see only its own windows, with
host-only configuration/container transactions denied by the daemon.

The native service remains registered as `anland.host` for root discovery; it
uses `default_android_service` without a contexts overlay. No app-side
ServiceManager lookup is used. The root bridge is discovery-only, not a proxy
for privileged window/configuration transactions.

After installing the matching signed test APKs, run the production discovery
checks after boot (the first additionally verifies that direct app lookup is
denied):

```sh
adb -s DEVICE_SERIAL shell am instrument -w -e mode binder-discovery -e expect_private 1 \
  com.anlandnext.test/com.anlandnext.awl.TaskIdentityInstrumentation
adb -s DEVICE_SERIAL shell am instrument -w -e mode binder-discovery \
  com.anland.shell.test/com.anland.shell.ui.IconInstrumentation
```

Instrumentation terminates its target process on completion. Allow the root
bridge to republish before the next cross-app check; HyperOS may deny a provider
cold start with `Unknown authority`. The normal Shell launch handles this through
its existing foreground preflight activity.

For a separate UID security probe, build `tests/binder_bridge/build.py` with
`ANDROID_HOME` and `JAVA_HOME`, install `outputs/binder-bridge-probe/probe.apk`,
then run its `com.anlandnext.RootBinderBridge` entry point as root via
`app_process`, with the APK as `CLASSPATH` and
`com.anland.bridgeprobe.binder` as its authority argument. Execute
`am instrument -w com.anland.bridgeprobe/.BridgeProbe`. The probe uses production
provider/bridge sources and verifies publication rejection, native Binder
delivery, window isolation and host-only configuration denial. Stop that helper
and uninstall the disposable probe package afterward.
