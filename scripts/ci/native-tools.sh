#!/bin/bash
set -euo pipefail
git submodule update --init third_party/libffi third_party/wayland third_party/pulseaudio
sudo apt-get update
sudo apt-get install -y --no-install-recommends meson ninja-build pkg-config m4 patch
# The release tarball supplies configure, absent from the libffi Git checkout.
curl -fsSL --retry 3 https://github.com/libffi/libffi/releases/download/v3.4.6/libffi-3.4.6.tar.gz \
    | tar xz --strip-components=1 -C third_party/libffi
test -x third_party/libffi/configure
meson setup /tmp/wl-scanner third_party/wayland \
    -Dscanner=true -Dlibraries=false -Ddocumentation=false -Dtests=false -Ddtd_validation=false
ninja -C /tmp/wl-scanner
sudo install -m755 /tmp/wl-scanner/src/wayland-scanner /usr/local/bin/wayland-scanner
