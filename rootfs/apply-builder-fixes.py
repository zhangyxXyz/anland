"""Apply reviewed overrides to the pinned upstream RootFS builder, fail on drift."""
import pathlib
import shutil
import sys

builder = pathlib.Path(sys.argv[1])
source = pathlib.Path(__file__).resolve().parent.parent

def replace_once(path, old, new):
    text = path.read_text(encoding="utf-8")
    if text.count(old) != 1:
        raise SystemExit(f"Unexpected builder source: {path}")
    path.write_text(text.replace(old, new), encoding="utf-8", newline="\n")

replace_once(builder / "scripts/configure-chrome.sh",
    "    --render-node-override=/dev/dri/renderD128 \\\n    --ignore-gpu-blocklist \\\n    --use-angle=vulkan \\\n    --enable-features=VaapiVideoDecodeLinux,VaapiVideoDecoder,VaapiVideoDecodeLinuxGL \\\n",
    '    --use-angle="${ANLAND_CHROME_ANGLE:-gl}" \\\n    --ignore-gpu-blocklist \\\n')

# The launcher selects the live desktop backend; independent launches keep Wayland.
replace_once(builder / "scripts/configure-chrome.sh",
    "--ozone-platform=wayland",
    '--ozone-platform="${ANLAND_OZONE_PLATFORM:-wayland}"')

replace_once(builder / "scripts/configure-desktop.sh",
    "configure_anland_next_runtime() {\n",
    "configure_anland_next_runtime() {\n"
    '    bash "$(dirname "$0")/configure-anland-audio.sh" "$rootfs"\n'
    '    install -m 0755 "$(dirname "$0")/anland-session-fixed.sh" "$rootfs/usr/bin/anland-session"\n')
shutil.copyfile(source / "anland-session/anland-session.sh", builder / "scripts/anland-session-fixed.sh")
shutil.copyfile(source / "rootfs/configure-anland-audio.sh", builder / "scripts/configure-anland-audio.sh")
replace_once(builder / "Debian-13.Dockerfile",
    "COPY scripts/configure-desktop.sh /usr/local/sbin/configure-desktop\n",
    "COPY scripts/configure-desktop.sh /usr/local/sbin/configure-desktop\n"
    "COPY scripts/configure-anland-audio.sh scripts/anland-session-fixed.sh /usr/local/sbin/\n")
