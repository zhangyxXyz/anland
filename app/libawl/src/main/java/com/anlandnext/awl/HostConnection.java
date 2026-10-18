package com.anlandnext.awl;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

/** Obtains the original daemon Binder; window calls never run through a proxy UID. */
public final class HostConnection {
    public static final String AUTHORITY = "com.anlandnext.binder";
    public static final String BRIDGE_DESCRIPTOR = "anland.IBinderBridge";
    public static final String HOST_DESCRIPTOR = "anland.IHost";
    public static final String KEY_BINDER = "binder";
    public static final int GET_HOST = IBinder.FIRST_CALL_TRANSACTION;
    private static volatile Context context;
    private static volatile IBinder cached;

    public static void initialize(Context value) {
        Context application = value.getApplicationContext();
        context = application == null ? value : application;
    }

    public static IBinder get() {
        IBinder binder = cached;
        if (binder != null && binder.isBinderAlive()) return binder;
        cached = null;
        Context app = context;
        if (app == null) return null;
        try {
            Bundle result = app.getContentResolver().call(
                    Uri.parse("content://" + AUTHORITY), "get", null, null);
            binder = result == null ? null : result.getBinder(KEY_BINDER);
            if (binder != null && binder.pingBinder()
                    && HOST_DESCRIPTOR.equals(binder.getInterfaceDescriptor())) {
                cached = binder;
                return binder;
            }
        } catch (Exception e) {
            Log.w("anland-binder", "Module Binder bridge unavailable", e);
        }
        return null;
    }

    private HostConnection() { }
}
