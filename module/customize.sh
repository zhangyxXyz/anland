# customize.sh — sourced by ksud when flashing the zip (env: MODPATH = install
# staging dir, ZIPFILE)

# Preserve daemon settings when updating the module. Otherwise an upgrade
# silently resets the selected renderer, zoom and runtime directory.
OLDMOD="/data/adb/modules/${MODPATH##*/}"
if [ "$OLDMOD" != "$MODPATH" ] && [ -f "$OLDMOD/config.json" ]; then
  cp "$OLDMOD/config.json" "$MODPATH/config.json"
  set_perm "$MODPATH/config.json" 0 0 0644
fi

# File modes: the installer has just run `set_perm_recursive $MODPATH 0 0 0755
# 0644` over the whole tree (before sourcing this script) — the zip's modes are
# gone and every binary is 0644. service.sh chmods again at boot, but the
# flashed module should be right on its own (and readable in the manager).
set_perm "$MODPATH/waylandbridge" 0 0 0755
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/binder-bridge.sh" 0 0 0755

# Apps receive the native Binder through the root bridge, not ServiceManager.
# Remove only this module's legacy generated file on an in-place upgrade.
rm -f "$MODPATH/system/etc/selinux/plat_service_contexts" "$MODPATH/plat_service_contexts.anland"
rmdir "$MODPATH/system/etc/selinux" "$MODPATH/system/etc" "$MODPATH/system" 2>/dev/null || true
touch "$MODPATH/skip_mount"
set_perm "$MODPATH/skip_mount" 0 0 0644

if [ -d "$MODPATH/pulse/bin" ]; then
  set_perm_recursive "$MODPATH/pulse/bin" 0 0 0755 0755
  echo "anland: pulse/bin marked executable"
fi
