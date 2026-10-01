#!/bin/bash
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
output="${1?Output .so path required}"
g++ -std=c++17 -O2 -Wall -Wextra -Werror -fPIC -shared \
    -I"$here/../include" "$here/fcitx5-anland.cpp" \
    $(pkg-config --cflags --libs Fcitx5Core) -o "$output"
