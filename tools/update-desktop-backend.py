"""Incremental migration for containers built before desktop launch routing.

Run as root inside Debian. Keep the existing GPU configuration, and permit the
launcher to select the display backend of the active session. The RootFS builder
must emit this same parameterized flag for future container installations.
"""
from pathlib import Path

path = Path('/usr/local/bin/google-chrome')
old = '--ozone-platform=wayland'
new = '--ozone-platform="${ANLAND_OZONE_PLATFORM:-wayland}"'
text = path.read_text()
if new not in text:
    if text.count(old) != 1:
        raise SystemExit('Unexpected Chrome wrapper; refusing to replace it')
    backup = path.with_name(path.name + '.before-desktop-routing')
    if not backup.exists():
        backup.write_text(text)
    path.write_text(text.replace(old, new))
