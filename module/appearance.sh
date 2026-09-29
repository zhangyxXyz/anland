#!/system/bin/sh
# Publish the resolved Android night mode, including automatic/scheduled mode.
# The container reads data only; no Android commands run inside its namespace.
state=${1:?runtime directory required}/appearance
mkdir -p "$state" || exit 1
[ ! -L "$state" ] || exit 1
chmod 755 "$state"
last=
trap 'rm -f "$state/night-mode.tmp.$$"; exit 0' TERM INT
while :; do
    mode=$(dumpsys uimode 2>/dev/null | sed -n 's/.*mComputedNightMode=\([^ ]*\).*/\1/p' | head -1)
    case "$mode" in true) mode=dark ;; false) mode=light ;; *) sleep 5; continue ;; esac
    if [ "$mode" != "$last" ]; then
        printf '%s\n' "$mode" > "$state/night-mode.tmp.$$" &&
            chmod 644 "$state/night-mode.tmp.$$" &&
            mv -f "$state/night-mode.tmp.$$" "$state/night-mode" && last=$mode
    fi
    sleep 5
done
