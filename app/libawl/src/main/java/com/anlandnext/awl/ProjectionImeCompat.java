package com.anlandnext.awl;

import android.os.IBinder;
import android.os.Parcel;
import android.util.Log;

/** Optional OEM projection caret channel, in addition to Android's IME APIs. */
final class ProjectionImeCompat {
    private static final String DESCRIPTOR = "com.xiaomi.mirror.IMirrorAppService";
    // Same one-way AIDL call used by HyperOS TextViewImpl/MirrorManager.
    private static final int NOTIFY_INPUT_TYPE_AND_POS = 4;
    private boolean initialized;
    private IBinder mirror;
    private int lastType = -1, lastX = Integer.MIN_VALUE, lastY = Integer.MIN_VALUE;

    void reset() { lastType = -1; }

    void update(int inputType, int screenX, int screenY) {
        if (mirror != null && !mirror.isBinderAlive()) {
            mirror = null; initialized = false; reset();
        }
        if (!initialized) {
            initialized = true;
            try {
                Class<?> sm = Class.forName("android.os.ServiceManager");
                IBinder binder = (IBinder)sm.getMethod("getService", String.class)
                        .invoke(null, "miui.mirror_app_service");
                if (binder != null && DESCRIPTOR.equals(binder.getInterfaceDescriptor())) mirror = binder;
            } catch (Exception | LinkageError ignored) {
                // Other Android implementations use the standard cursor APIs.
            }
        }
        if (mirror == null || (lastType == inputType && lastX == screenX && lastY == screenY)) return;
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeInt(inputType); data.writeInt(screenX); data.writeInt(screenY);
            if (mirror.transact(NOTIFY_INPUT_TYPE_AND_POS, data, null, IBinder.FLAG_ONEWAY)) {
                lastType = inputType; lastX = screenX; lastY = screenY;
            }
        } catch (Exception e) {
            mirror = null;
            Log.w("anland-awlwin", "Projection caret extension unavailable", e);
        } finally { data.recycle(); }
    }
}
