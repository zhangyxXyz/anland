#!/bin/bash
set -euo pipefail
sdkmanager="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
yes | "$sdkmanager" --licenses >/dev/null || true
packages=('platforms;android-36' 'build-tools;36.0.0')
if [[ ${1:-} == native ]]; then
    packages+=('ndk;29.0.13113456' 'cmake;3.22.1')
fi
"$sdkmanager" "${packages[@]}"
