#!/system/bin/sh
# Root discovery bridge. Reload the installed APK after updates, and wait when
# it is absent. There is no su invocation or app-side root authorization.
MODDIR=${0%/*}
# The lock covers retries as well as the child process, including APK absence.
# The kernel releases it after crashes; no stale PID file blocks the next boot.
exec 9>"$MODDIR/.binder-bridge.lock"
# Android mksh marks persistent extra descriptors close-on-exec. Explicitly
# inherit this one into flock; the supervisor retains the same open file.
flock -n 9 9>&9 || exit 0

while [ -d "$MODDIR" ] && [ ! -f "$MODDIR/disable" ] && [ ! -f "$MODDIR/remove" ]; do
  APK=$(pm path com.anlandnext 2>/dev/null | sed -n 's/^package://p' | head -1)
  if [ -n "$APK" ] && [ -r "$APK" ]; then
    CLASSPATH="$APK" /system/bin/app_process /system/bin --nice-name=anland-binder-bridge \
      com.anlandnext.RootBinderBridge
  fi
  sleep 2
done
