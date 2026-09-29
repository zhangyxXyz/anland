# Window task identity at first launch

The first `AwlWindowActivity` previously published only its launch title.
Desktop metadata and the window icon were fetched after the Android surface
attached. On the tested tablet, Recents could retain the host APK icon even
after Android's task description contained the correct Chrome bitmap; opening
the card again made it visible. A title changed before attachment could also
remain at its early value.

`Awl.attachWindow` now resolves a window-scoped identity on a worker before
starting its Activity. `onCreate` publishes the resolved title/name and icon
together. The receiver holds its asynchronous broadcast until the launch
finishes. Existing per-window launch deduplication and transient-dialog
classification remain in place. Subsequent icon/title events still update the
identity; a snapshot cannot replace a title delivered by a newer control event.

The daemon now starts the transparent `OpenWindowActivity` with an exact
window ID. It uses the same worker-side identity prefetch before creating a
document task. The tablet's launcher cached a placeholder even for a task
initially excluded from Recents; merely delaying publication was insufficient.
Legacy direct launches/process recreation still delay card publication until
identity is loaded, but new launches use the prefetch path.

Transient dialogs continue to share their parent's task and never publish a
separate card. Reopening an existing document clears the pending-launch marker,
allowing subsequent opens to bring that same task forward.

There is no application-name table, focus-triggered retry, timed reload, client
restart or renderer change. Raster and SVG decoding share the same code for
initial and later updates. Missing icons still fall back to the host icon.

## Installed Mini Panel launcher

This device's separately installed `/usr/local/share/applications/mini-panel.desktop`
declares `Name=Mini Panel` and `Icon=mini-panel-color`, but did not declare its
window class. The live browser app window reported
`chrome-127.0.0.1__-Default`, which does not match the desktop filename.

`patches/desktop-entries/mini-panel-startup-class.patch` records the standard
`StartupWMClass` field added to that launcher. This is application installation
metadata, consumed by the existing generic resolver. It is not an application
alias in Android or the daemon. Verify the actual window app ID before applying
the patch to a different browser profile/launcher. The original device file is
retained as `mini-panel.desktop.before-task-icon`.

## Device regression

`tests/task_identity_fixture.py` creates a disposable GTK window and desktop
entry with a vector icon under a temporary XDG data directory. It installs no
application launcher. Disable daemon `auto_attach` while creating this fixture
and restore it after the window has mapped. Do not interact with the fixture
before the test; its Android task must not exist yet.

Install the signed `anland-wayland-tests.apk` from the same draft Release and run:

```sh
am instrument -w -e window_id WINDOW_ID -e label 'Task identity fixture' \
  com.anlandnext.test/com.anlandnext.awl.TaskIdentityInstrumentation
```

The test checks the real task's resolved label and non-null bitmap from the
host-create callback, before its first focus or surface attachment. This
distinguishes the initial-identity fix from a successful later refocus.
Close only the disposable fixture after testing; its temporary files are
removed when it exits normally.

## Launch handoff

Shell's container/user/session preflight stays intact. Both launch coordinators
use a transparent, non-dimming theme with no intermediate page animation. A
compact cancelable status appears only for slower launches. The caller remains
visible until the target window starts. Multiple matches and timeouts use a
dialog. Pinned shortcuts use the same entry point.

Before launching the Linux command, Shell snapshots current window IDs and the
live auto_attach setting from the host provider. Existing windows/auto_attach
off use explicit activation. New windows with auto_attach on are presented by
the daemon, without a competing manual document start. The waiter stops when it
loses the foreground, so a completed native launch or the user's Home action
cannot be followed by a delayed focus steal. Explicit activation keeps its
Activity alive until the asynchronous metadata load submits the target start.

The change requires the matching APK and daemon for automatic launches. It does
not restart Linux applications, toggle auto_attach, or change rendering/input.

## Verification in progress (2026-09-29)

- Old production APK failed the foreground instrumented fixture at its initial
  unresolved task label; 59da3d0 passed label + bitmap before first focus.
- The 59da3d0 Java launch path showed the fixture's teal check icon on its first
  trip to Recents. The legacy direct daemon path still showed the host icon,
  which motivated routing it through the same prefetch coordinator.
- Both Android projects passed local debug build and lint before adding the
  exact-ID daemon handoff. Final matching APK/native deployment remains to test.
