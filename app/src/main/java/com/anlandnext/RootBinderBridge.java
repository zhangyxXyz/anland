package com.anlandnext;

import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import android.util.Log;

import com.anlandnext.awl.HostConnection;

import java.io.File;
import java.lang.reflect.Method;

/** app_process entry point, started by the module as root, never inside the APK process. */
public final class RootBinderBridge extends Binder {
    private static final String TAG = "anland-bridge";
    private final Method checkService;

    private RootBinderBridge() throws ReflectiveOperationException {
        attachInterface(null, HostConnection.BRIDGE_DESCRIPTOR);
        checkService = Class.forName("android.os.ServiceManager").getMethod("checkService", String.class);
    }

    @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
            throws RemoteException {
        if (code != HostConnection.GET_HOST) return super.onTransact(code, data, reply, flags);
        data.enforceInterface(HostConnection.BRIDGE_DESCRIPTOR);
        // Only discovery runs as root. The returned Binder is the original
        // native endpoint, so its transactions still see the real client UID.
        long identity = Binder.clearCallingIdentity();
        try {
            IBinder host = (IBinder) checkService.invoke(null, "anland.host");
            reply.writeNoException();
            reply.writeStrongBinder(host);
        } catch (ReflectiveOperationException e) {
            reply.writeException(new IllegalStateException("Daemon lookup failed", e));
        } finally { Binder.restoreCallingIdentity(identity); }
        return true;
    }

    private IBinder publish(String authority, int user) throws ReflectiveOperationException {
        Class<?> managerClass = Class.forName("android.app.IActivityManager");
        Object manager = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null);
        Object holder = managerClass.getMethod("getContentProviderExternal", String.class,
                int.class, IBinder.class, String.class).invoke(manager, authority, user, null, TAG);
        if (holder == null) return null;
        try {
            Object provider = holder.getClass().getField("provider").get(holder);
            if (provider == null) return null;
            Bundle extras = new Bundle();
            extras.putBinder(HostConnection.KEY_BINDER, this);
            // The provider call gained attribution arguments in Android 11/12.
            // Reflect the platform interface instead of hardcoding transaction IDs.
            Class<?> api = Class.forName("android.content.IContentProvider");
            Bundle reply;
            if (Build.VERSION.SDK_INT >= 31) {
                Class<?> source = Class.forName("android.content.AttributionSource");
                Class<?> builder = Class.forName("android.content.AttributionSource$Builder");
                Object attribution = builder.getConstructor(int.class).newInstance(0);
                builder.getMethod("setPackageName", String.class).invoke(attribution, "android");
                attribution = builder.getMethod("build").invoke(attribution);
                reply = (Bundle) api.getMethod("call", source, String.class, String.class,
                        String.class, Bundle.class).invoke(provider, attribution, authority, "publish", null, extras);
            } else if (Build.VERSION.SDK_INT == 30) {
                reply = (Bundle) api.getMethod("call", String.class, String.class, String.class,
                        String.class, String.class, Bundle.class).invoke(provider,
                        null, null, authority, "publish", null, extras);
            } else {
                reply = (Bundle) api.getMethod("call", String.class, String.class,
                        String.class, String.class, Bundle.class).invoke(provider,
                        null, authority, "publish", null, extras);
            }
            return reply == null ? null : reply.getBinder("lifetime");
        } finally {
            managerClass.getMethod("removeContentProviderExternalAsUser", String.class, IBinder.class, int.class)
                    .invoke(manager, authority, null, user);
        }
    }

    public static void main(String[] args) throws Exception {
        if (Process.myUid() != 0) throw new SecurityException("Must be started by the root module");
        Looper.prepareMainLooper();
        RootBinderBridge bridge = new RootBinderBridge();
        String authority = args.length > 0 ? args[0] : HostConnection.AUTHORITY;
        int user = args.length > 1 ? Integer.parseInt(args[1]) : 0;
        File apk = new File(System.getenv("CLASSPATH"));
        long apkTime = apk.lastModified();
        IBinder lifetime = null;
        long lastError = 0;
        // App restart loses its cached bridge. Republish without granting root
        // to the app, restarting the compositor, or pinning the provider alive.
        while (apk.isFile() && apk.lastModified() == apkTime) {
            try {
                if (lifetime == null || !lifetime.isBinderAlive()) {
                    lifetime = bridge.publish(authority, user);
                    if (lifetime != null) {
                        // Subscribe to death so isBinderAlive updates without
                        // synchronous pings into a potentially frozen app.
                        lifetime.linkToDeath(() -> { }, 0);
                        Log.i(TAG, "Published Binder bridge to " + authority);
                    }
                }
            } catch (Exception e) {
                if (android.os.SystemClock.uptimeMillis() - lastError > 30000) {
                    Log.w(TAG, "Waiting for host provider", e);
                    lastError = android.os.SystemClock.uptimeMillis();
                }
                lifetime = null;
            }
            Thread.sleep(1000);
        }
        Log.i(TAG, "Host APK changed; supervisor will reload the bridge");
        System.exit(0);
    }
}
