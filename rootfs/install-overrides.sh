#!/bin/bash
# Runs as the final customizer layer, after every upstream package installer.
set -euo pipefail
cd /tmp/anland-overrides
sha256sum -c SHA256SUMS
export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y --allow-downgrades --no-install-recommends ./xfdesktop4_*_arm64.deb ./xfdesktop4-data_*_all.deb python3
install -m755 Xwayland /usr/lib/anland/Xwayland
install -m755 anland-miniwm /usr/bin/anland-miniwm
install -m755 anland-session /usr/bin/anland-session
install -d /usr/share/anland /var/lib/droidspaces-tui/components
install -m644 rootfs-components.json /usr/share/anland/rootfs-components.json
dpkg-query -W -f='${Version}\n' anland-session > /var/lib/droidspaces-tui/components/anland-next.version
dpkg-query -W -f='${Package}\t${Version}\t${Architecture}\n' > /usr/share/anland/dpkg-packages.tsv
# Keep the selected fixes during a general apt upgrade. Deliberate package
# replacement requires apt-mark unhold (documented in both READMEs).
apt-mark hold xfdesktop4 xfdesktop4-data anland-session
python3 verify-installed.py
