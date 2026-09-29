# VS Code EditContext synchronization

Reviewed against VS Code 1.139.1 (`NativeEditContext._emitTypeEvent` and
`_updateEditContext`), Electron 43.6.0 / Chromium 150.0.7871.250.
Source: https://github.com/microsoft/vscode/blob/1.139.1/src/vs/editor/browser/controller/editContext/native/nativeEditContext.ts

The browser mutates `EditContext.text` before dispatching `textupdate`.
VS Code's synchronous model-change listener then replaces the buffer using
`_previousEditContextText.length`, which still describes the pre-event text.
Insertion consequently leaves an extra suffix in the native buffer. The
visible Monaco model stays correct, but Wayland `set_surrounding_text` and the
Android candidate bar receive the phantom suffix. On an empty line, composing
`test` produced surrounding text `tset`; commit-only input duplicated letters.

The source patch acknowledges the native buffer before applying the event to
the model. It preserves EditContext, native Wayland and GPU acceleration. The
Anland compositor does not recognize VS Code by name or rewrite its text.
This is separate from Anland's generic `finishComposingText`, metadata-only
`setComposingRegion`, and atomic replacement fixes.

## Apply to the installed Debian package

Close VS Code first. Run inside Debian (paths are explicit):

```sh
python3 apply-edit-context-fix.py \
  /usr/share/code/resources/app/out/vs/workbench/workbench.desktop.main.js \
  --product /usr/share/code/resources/app/product.json
sudo python3 apply-edit-context-fix.py \
  /usr/share/code/resources/app/out/vs/workbench/workbench.desktop.main.js \
  --product /usr/share/code/resources/app/product.json --apply
```

The first command only checks. The second verifies the existing integrity
manifest and reviewed source anchors, backs up both files, applies the same
one-line source change, and updates only this bundle's expected checksum.
The integrity checker remains enabled. Reapplying is idempotent; unknown
source layouts or pre-existing checksum mismatches abort without writes.
Do not apply while VS Code is running.

Original files remain alongside the modified files with the suffix
`.anland-edit-context-<original SHA256 prefix>.bak`. To roll back, close
VS Code and copy **both** matching backups over their original paths.
A package upgrade may replace this local patch; review the new source before
reapplying. There is no startup hook or automatic package modification.
The previously downloaded rootfs image is unchanged; this patch was applied
incrementally to the current container, whose VS Code package was installed
separately.

## Device validation (2026-09-29)

- Before: inspected the actual browser EditContext and Wayland trace; visible
  `test` disagreed with the buffer and `set_surrounding_text`.
- After: Sogou English `test`, two candidate-bar taps, then `hello` yields
  `test yourhello` in the editor, EditContext and Wayland context alike.
- Two backspaces give `test yourhel`; typing `lo` restores exactly
  `test yourhello`. Native replacement of a marked word is delivered as
  deletion plus preedit in one `done` group.
- GTK Entry with the same Sogou sequence also reports exactly
  `test yourhello`, confirming that the Anland protocol path does not need
  application-specific text heuristics.
- Source patch applies cleanly to the upstream 1.139.1 TypeScript file.
