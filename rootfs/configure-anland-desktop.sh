#!/bin/bash
set -euo pipefail
rootfs="${1?RootFS prefix required}"
run_root() { if [[ -n "$rootfs" ]]; then chroot "$rootfs" "$@"; else "$@"; fi; }
source "$rootfs/etc/os-release"
case "$ID" in
    debian|ubuntu)
        run_root env DEBIAN_FRONTEND=noninteractive apt-get update
        run_root env DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
            xfce4-session xfce4-panel xfdesktop4 xfwm4 xfce4-settings thunar xfce4-terminal \
            xauth x11-utils xcompmgr python3 libdecor-0-0 libnotify4 libgarcon-gtk3-1-0
        ;;
    fedora)
        run_root dnf install -y --setopt=install_weak_deps=False \
            xfce4-session xfce4-panel xfdesktop xfwm4 xfce4-settings Thunar xfce4-terminal \
            xauth xprop xdpyinfo xcompmgr python3 libdecor libnotify garcon
        ;;
    arch|archarm|archlinux)
        run_root pacman -S --noconfirm --needed \
            xfce4-session xfce4-panel xfdesktop xfwm4 xfce4-settings thunar xfce4-terminal \
            xorg-xauth xorg-xprop xorg-xdpyinfo xcompmgr python libdecor libnotify garcon
        ;;
    *) echo "Unsupported desktop system: $ID" >&2; exit 1 ;;
esac
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

if [[ "$ID" != debian ]]; then
    sed -i 's/^Icon=anland-debian$/Icon=computer/' "$rootfs/usr/local/share/applications/org.freedesktop.Xwayland.desktop"
fi
