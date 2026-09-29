#!/bin/bash
# Runs after all upstream installers, using output from the disposable build stage.
set -euo pipefail
cd /tmp/anland-overrides
cp -a xfdesktop-root/. /
install -m755 Xwayland /usr/lib/anland/Xwayland
install -m755 anland-miniwm /usr/bin/anland-miniwm
install -m755 anland-session /usr/bin/anland-session
install -d /usr/share/anland /var/lib/droidspaces-tui/components
install -m644 rootfs-components.json /usr/share/anland/rootfs-components.json
source /etc/os-release
case "$ID" in
    debian|ubuntu)
        dpkg-query -W -f='${Version}\n' anland-session > /var/lib/droidspaces-tui/components/anland-next.version
        dpkg-query -W -f='${Package}\t${Version}\t${Architecture}\n' > /usr/share/anland/packages.tsv
        apt-mark hold xfdesktop4 xfdesktop4-data anland-session
        apt-get clean
        rm -rf /var/lib/apt/lists/*
        ;;
    fedora)
        rpm -q --qf '%{VERSION}-%{RELEASE}\n' anland-session > /var/lib/droidspaces-tui/components/anland-next.version
        rpm -qa --qf '%{NAME}\t%{VERSION}-%{RELEASE}\t%{ARCH}\n' > /usr/share/anland/packages.tsv
        # A named override can be disabled explicitly with --disableexcludes=main.
        python3 - <<'PY'
import configparser
from pathlib import Path
path = Path('/etc/dnf/dnf.conf')
config = configparser.ConfigParser(interpolation=None)
config.read(path)
if not config.has_section('main'):
    config.add_section('main')
config.set('main', 'excludepkgs', config.get('main', 'excludepkgs', fallback='') + ' xfdesktop anland-session')
with path.open('w') as stream:
    config.write(stream)
PY
        dnf clean all
        ;;
    arch|archarm|archlinux)
        pacman -Q anland-session | awk '{print $2}' > /var/lib/droidspaces-tui/components/anland-next.version
        pacman -Q > /usr/share/anland/packages.tsv
        sed -i '/^\[options\]/a IgnorePkg = xfdesktop anland-session' /etc/pacman.conf
        rm -rf /var/cache/pacman/pkg/* /var/lib/pacman/sync/*
        ;;
    *) echo "Unsupported runtime system: $ID" >&2; exit 1 ;;
esac
python3 verify-installed.py
