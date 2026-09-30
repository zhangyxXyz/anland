#!/system/bin/sh
# Publish the resolved Android night mode, including automatic/scheduled mode.
# The container reads data only; no Android commands run inside its namespace.
state=${1:?runtime directory required}/appearance
mkdir -p "$state" || exit 1
[ ! -L "$state" ] || exit 1
chmod 755 "$state"
trap 'rm -f "$state/night-mode.tmp.$$"; exit 0' TERM INT
publish() {
    mode=$(timeout 2 dumpsys uimode 2>/dev/null | sed -n 's/.*mComputedNightMode=\([^ ]*\).*/\1/p' | head -1)
    case "$mode" in true) mode=dark ;; false) mode=light ;; *) return 1 ;; esac
    if [ "$mode" != "$(cat "$state/night-mode" 2>/dev/null)" ]; then
        printf '%s\n' "$mode" > "$state/night-mode.tmp.$$" &&
            chmod 644 "$state/night-mode.tmp.$$" &&
            mv -f "$state/night-mode.tmp.$$" "$state/night-mode"
    fi
}
# APK startup can repair a stopped boot-time monitor without restarting graphics.
# Refresh synchronously so the consumer never starts with yesterday's theme.
if [ "${2:-}" = --ensure ]; then
    publish || exit 1
    nohup sh "$0" "$1" > "$state/monitor.log" 2>&1 < /dev/null &
    exit 0
fi
# Boot and APK recovery share one lock; repeated foreground callbacks are safe.
# Android mksh marks nonstandard descriptors close-on-exec. Stdin is unused
# by this file-backed script and remains open in toybox flock.
exec 0>"$state/monitor.lock"
flock -n 0 || exit 0
printf '%s\n' "$$" > "$state/monitor.pid"
while :; do
    # Do not let dumpsys/sleep inherit the lock if the monitor is killed.
    publish < /dev/null || true
    sleep 5 < /dev/null
done
