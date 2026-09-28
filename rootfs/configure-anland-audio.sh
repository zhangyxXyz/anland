#!/bin/bash
set -euo pipefail
rootfs="${1?RootFS prefix required (may be empty inside the build container)}"
install -d -m 0755 "$rootfs/etc/pipewire/pipewire-pulse.conf.d"
cat > "$rootfs/etc/pipewire/pipewire-pulse.conf.d/90-anland.conf" <<'EOF'
# Keep ordinary PulseAudio clients connected to the Android host as well as
# applications started with anland-session's explicit PULSE_SERVER.
pulse.cmd = [
    { cmd = "load-module" args = "module-tunnel-sink server=unix:/run/anland/pulse.sock sink_name=anland_android" flags = [ ] }
]
EOF
