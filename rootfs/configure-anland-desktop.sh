#!/bin/bash
set -euo pipefail
rootfs="${1?RootFS prefix required}"
run_root() { if [[ -n "$rootfs" ]]; then chroot "$rootfs" "$@"; else "$@"; fi; }
packages=(xfce4-session xfce4-panel xfdesktop4 xfwm4 xfce4-settings thunar xfce4-terminal xauth x11-utils xcompmgr)
missing=false
for package in "${packages[@]}"; do
    [[ $(run_root dpkg-query -W -f='${db:Status-Status}' "$package" 2>/dev/null || true) == installed ]] || missing=true
done
if $missing; then
    run_root env DEBIAN_FRONTEND=noninteractive apt-get update
    run_root env DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends "${packages[@]}"
fi
here=$(dirname "$0")
install -d "$rootfs/usr/local/bin" "$rootfs/usr/lib/systemd/user" "$rootfs/usr/local/share/applications"
for file in anland-desktop anland-desktop-session anland-desktop-inner anland-desktop-appearance; do
    install -m 0755 "$here/$file" "$rootfs/usr/local/bin/$file"
done
for size in 128 256; do
    icon_dir="$rootfs/usr/local/share/icons/hicolor/${size}x${size}/apps"
    install -d "$icon_dir"
    install -m 0644 "$here/assets/anland-debian-$size.png" "$icon_dir/anland-debian.png"
done
cat > "$rootfs/usr/lib/systemd/user/anland-desktop.service" <<'EOF'
[Unit]
Description=Anland full desktop
[Service]
Type=simple
ExecStart=/usr/local/bin/anland-desktop-session
KillMode=control-group
TimeoutStopSec=8
EOF
# Match Xwayland's real rootful app_id through standard desktop metadata;
# labels/icons belong in packaging, never a browser/application lookup table.
cat > "$rootfs/usr/local/share/applications/org.freedesktop.Xwayland.desktop" <<'EOF'
[Desktop Entry]
Type=Application
Name=Linux Desktop
Name[zh_CN]=Linux 桌面
Name[zh]=Linux 桌面
Comment=Open the complete desktop inside Anland
Exec=/usr/local/bin/anland-desktop
Icon=anland-debian
Terminal=false
Categories=System;
X-Anland-DesktopSession=true
X-Anland-WindowAppId=org.freedesktop.Xwayland
EOF
chmod 0644 "$rootfs/usr/lib/systemd/user/anland-desktop.service" \
    "$rootfs/usr/local/share/applications/org.freedesktop.Xwayland.desktop"
