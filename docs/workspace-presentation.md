# Unified workspace presentation

Independent Android tasks and the workspace host the same daemon window IDs.
Switching does not change `DISPLAY`, the session bus, Linux processes, the
Wayland connection, or `auto_attach`. `AwlWindowHost` owns the original Surface,
touch/mouse, IME, clipboard, pointer capture and cursor implementation; the
Activity and workspace are presentation adapters.

## State and ownership

- `WorkspaceController` in the host APK owns committed presentation mode.
  Shell reads it through a read-only provider; it does not store another mode.
- The mode applies to the shared Anland display server, **all containers**.
  The UI labels this scope explicitly; the existing daemon API does not expose
  sufficient container ownership to promise per-container workspace isolation.
- Each switch snapshots live IDs, prepares real target Surfaces, and waits for
  attachment acknowledgements. Destroyed clients are removed from the pending
  set. Returning to independent tasks starts each task after the previous one
  acknowledges attachment, because Android pauses background Activities.
- The committed mode changes only after all pending windows acknowledge or
  close. Failures/timeouts restore presentation of live clients in the original
  mode. No client is relaunched. This is not a guarantee of zero blank frames:
  the native daemon has one render target per window, and target replacement is
  not a simultaneous two-surface transaction.
- A process death during a switch recovers the committed mode. Window IDs are
  enumerated afresh, never persisted as authoritative state across daemon deaths.
- Obsolete independent Recents cards are retired only after workspace entry
  succeeds. Hiding the workspace keeps the mode and clients alive. Ending the
  graphical session requests graceful close; save dialogs remain operable.

## Boundaries

The old rootful Xwayland/XFCE session is itself one Wayland toplevel. It stays
one whole pane; its internal X11 clients cannot be extracted by changing an
Android host. The old entry is labelled separately. Newly launched rootless
X11 or native Wayland toplevels can participate directly.

On Android 36+, each SurfaceView receives a distinct negative composition
order, below workspace decorations. Older systems show the active pane with
Dock switching, avoiding undefined overlapping SurfaceView order.

Application activation uses desktop ID / StartupWMClass / optional explicit
metadata and the daemon's app ID. Multiple matches are a chooser, not a guessed
application-specific alias. This shared display cannot yet distinguish identical
app IDs from different containers; the window list remains the recovery entry.

## Device acceptance

Before release, record client PIDs/start times, daemon window IDs, and editable
text. Exercise independent → workspace → independent repeatedly, including:

- Native Wayland and rootless X11 clients, newly created windows and close.
- Mouse focus/cursor, touch, Dock restore, title-bar close, drag and resize.
- Overlapping title bars must not send clicks to the lower client.
- Keyboard/IME and clipboard follow the focused pane.
- Background/foreground, minimize, rotation and interrupted switches.
- Preserve a save dialog after graceful close; no process kill fallback.
- Confirm Linux GPU rendering remains accelerated.

Compilation and lint alone do not satisfy device acceptance. A successful
Surface binder return is attachment readiness, not proof that a frame rendered.
