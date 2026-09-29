#!/bin/bash
# Runs in an ARM64 Debian container with a read-only source mount.
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive
sed -i 's/^Types: deb$/Types: deb deb-src/' /etc/apt/sources.list.d/debian.sources
apt-get update
apt-get install -y --no-install-recommends git ca-certificates build-essential \
    patch devscripts meson ninja-build python3 libx11-dev libxcomposite-dev
apt-get build-dep -y xfdesktop4 xwayland
mkdir -p /tmp/anland-components/xfdesktop /tmp/anland-components/xserver
cd /tmp/anland-components/xfdesktop
source_version=$(python3 -c 'import json; print(json.load(open("/src/rootfs/sources.json"))["xfdesktop_source_version"])')
apt-get source "xfdesktop4=$source_version"
cd xfdesktop4-*
for patch_file in /src/patches/xfdesktop/*.patch; do patch -p1 < "$patch_file"; done
export DEBEMAIL=build@anland.invalid DEBFULLNAME=Anland
version=$(sed -n 's/^ROOTFS_VERSION=//p' /src/version.properties)
dch --local "+anland${version}." --distribution trixie 'Recognize touch double taps without changing mouse activation or drag handling.'
DEB_BUILD_OPTIONS='nocheck parallel=4' dpkg-buildpackage -b -uc -us
cp ../xfdesktop4_*_arm64.deb ../xfdesktop4-data_*_all.deb /out/
cd /tmp/anland-components/xserver
git -c safe.directory=/src/third_party/xserver -C /src/third_party/xserver archive HEAD | tar xf -
for patch_file in /src/patches/xwayland/*.patch; do patch -p1 < "$patch_file"; done
meson setup build --prefix=/usr -Dxvfb=false -Dxwayland_ei=false \
    -Dxkb_dir=/usr/share/X11/xkb -Dxkb_bin_dir=/usr/bin
meson compile -C build -j 4
install -m755 build/hw/xwayland/Xwayland /out/Xwayland
gcc -O2 -Wall /src/anland-session/miniwm.c -o /out/anland-miniwm -lX11 -lXcomposite
dpkg-deb -f /out/xfdesktop4_*_arm64.deb Version > /out/xfdesktop-version
dpkg-deb -f /out/xfdesktop4-data_*_all.deb Version | cmp - /out/xfdesktop-version
