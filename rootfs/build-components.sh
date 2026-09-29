#!/bin/bash
# Runs in a disposable build stage derived from the selected runtime image.
set -euo pipefail
cd /src
sha256sum -c SHA256SUMS
source /etc/os-release
export DEBIAN_FRONTEND=noninteractive
case "$ID" in
    debian|ubuntu)
        apt-get update
        apt-get install -y --no-install-recommends build-essential patch meson ninja-build \
            pkg-config python3 bzip2 xz-utils gettext intltool libtool \
            libgtk-3-dev libxfce4ui-2-dev libxfce4util-dev libxfce4windowing-0-dev \
            libxfconf-0-dev libexo-2-dev libgarcon-1-0-dev libgarcon-gtk3-1-dev \
            libthunarx-3-dev libnotify-dev libyaml-dev libx11-dev libxcomposite-dev \
            xutils-dev x11proto-dev libpixman-1-dev libxkbfile-dev libxfont-dev \
            libxcvt-dev libwayland-dev wayland-protocols libxshmfence-dev libdrm-dev \
            libepoxy-dev libgbm-dev libssl-dev libxdmcp-dev libxau-dev libdecor-0-dev
        ;;
    fedora)
        dnf install -y --setopt=install_weak_deps=False gcc gcc-c++ make patch meson ninja-build \
            pkgconf-pkg-config python3 bzip2 xz gettext intltool libtool \
            gtk3-devel libxfce4ui-devel libxfce4util-devel libxfce4windowing-devel \
            xfconf-devel exo-devel garcon-devel Thunar-devel libnotify-devel libyaml-devel \
            libX11-devel libXcomposite-devel xorg-x11-util-macros xorg-x11-proto-devel \
            xorg-x11-xtrans-devel pixman-devel libxkbfile-devel libXfont2-devel \
            libxcvt-devel wayland-devel wayland-protocols-devel libxshmfence-devel \
            libdrm-devel libepoxy-devel mesa-libgbm-devel openssl-devel libXdmcp-devel \
            libXau-devel libdecor-devel
        ;;
    arch|archarm|archlinux)
        pacman -S --noconfirm --needed base-devel patch meson ninja python bzip2 xz \
            gettext intltool gtk3 libxfce4ui libxfce4util libxfce4windowing xfconf exo \
            garcon thunar libnotify libyaml libx11 libxcomposite xorg-util-macros \
            xorgproto xtrans pixman libxkbfile libxfont2 libxcvt wayland wayland-protocols \
            libxshmfence libdrm libepoxy mesa openssl libxdmcp libxau libdecor
        ;;
    *) echo "Unsupported build system: $ID" >&2; exit 1 ;;
esac
mkdir -p /tmp/anland-components/xfdesktop /tmp/anland-components/xserver /out/xfdesktop-root
cd /tmp/anland-components/xfdesktop
tar -xjf /src/xfdesktop.tar.bz2 --strip-components=1
for patch_file in /src/patches/xfdesktop/*.patch; do patch --batch --fuzz=0 -p1 < "$patch_file"; done
./configure --prefix=/usr --sysconfdir=/etc --disable-wayland --enable-x11 \
    --enable-file-icons --enable-thunarx --disable-tests
make -j4
make DESTDIR=/out/xfdesktop-root install
cd /tmp/anland-components/xserver
tar -xf /src/xserver.tar
for patch_file in /src/patches/xwayland/*.patch; do patch --batch --fuzz=0 -p1 < "$patch_file"; done
meson setup build --prefix=/usr -Dxvfb=false -Dxwayland_ei=false \
    -Dxkb_dir=/usr/share/X11/xkb -Dxkb_bin_dir=/usr/bin -Ddocs=false -Ddevel-docs=false
meson compile -C build -j4
install -m755 build/hw/xwayland/Xwayland /out/Xwayland
gcc -O2 -Wall /src/miniwm.c -o /out/anland-miniwm -lX11 -lXcomposite
install -m755 /src/anland-session /out/anland-session
python3 /src/record-components.py
