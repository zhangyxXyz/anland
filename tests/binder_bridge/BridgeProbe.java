package com.anland.bridgeprobe;

import android.app.Activity;
import android.app.Instrumentation;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;
import com.anlandnext.awl.HostConnection;

/** A separate UID exercises the actual provider/bridge classes against a live daemon. */
public final class BridgeProbe extends Instrumentation {
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            Uri uri = Uri.parse("content://com.anland.bridgeprobe.binder");
            boolean rejected = false;
            try { getTargetContext().getContentResolver().call(uri, "publish", null, new Bundle()); }
            catch (SecurityException expected) { rejected = true; }
            if (!rejected) throw new AssertionError("Non-root publisher accepted");
            IBinder daemon = null;
            long deadline = SystemClock.uptimeMillis() + 10000;
            while (daemon == null && SystemClock.uptimeMillis() < deadline) {
                Bundle reply = getTargetContext().getContentResolver().call(uri, "get", null, null);
                daemon = reply == null ? null : reply.getBinder(HostConnection.KEY_BINDER);
                if (daemon == null) SystemClock.sleep(200);
            }
            if (daemon == null || !daemon.pingBinder()) throw new AssertionError("Daemon not delivered");
            if (!HostConnection.HOST_DESCRIPTOR.equals(daemon.getInterfaceDescriptor()))
                throw new AssertionError("Unexpected endpoint");
            Parcel data = Parcel.obtain(), reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(HostConnection.HOST_DESCRIPTOR);
                if (!daemon.transact(4, data, reply, 0) || reply.readInt() != 0)
                    throw new AssertionError("Fresh third-party UID saw other apps' windows");
            } finally { data.recycle(); reply.recycle(); }
            data = Parcel.obtain(); reply = Parcel.obtain();
            rejected = false;
            try {
                data.writeInterfaceToken(HostConnection.HOST_DESCRIPTOR);
                data.writeString("zoom");
                rejected = !daemon.transact(12, data, reply, 0);
            } catch (SecurityException | RemoteException expected) { rejected = true; }
            finally { data.recycle(); reply.recycle(); }
            if (!rejected) throw new AssertionError("Bridge elevated client to root/host UID");
            result.putString("stream", "PASS: non-root publication rejected; native Binder delivered; "
                    + "window list scoped to caller; host-only configuration denied\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable e) {
            result.putString("stream", android.util.Log.getStackTraceString(e));
            finish(Activity.RESULT_CANCELED, result);
        }
    }
}
