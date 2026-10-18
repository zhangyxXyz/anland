package com.anlandnext;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;

import com.anlandnext.awl.HostConnection;

/** Root publishes a discovery bridge. Apps receive the daemon's original Binder. */
public final class HostBinderProvider extends ContentProvider {
    private final Object lock = new Object();
    private final Binder lifetime = new Binder();
    private IBinder bridge;
    private IBinder.DeathRecipient bridgeDeath;

    @Override public boolean onCreate() { return true; }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        if ("publish".equals(method)) {
            // Check the kernel-supplied UID before inspecting caller-controlled data.
            if (Binder.getCallingUid() != 0) throw new SecurityException("Root publisher required");
            IBinder incoming = extras == null ? null : extras.getBinder(HostConnection.KEY_BINDER);
            try {
                if (incoming == null || !incoming.pingBinder()
                        || !HostConnection.BRIDGE_DESCRIPTOR.equals(incoming.getInterfaceDescriptor()))
                    throw new IllegalArgumentException("Invalid Binder bridge");
                synchronized (lock) {
                    IBinder.DeathRecipient death = () -> {
                        synchronized (lock) {
                            if (bridge == incoming) { bridge = null; bridgeDeath = null; }
                            lock.notifyAll();
                        }
                    };
                    incoming.linkToDeath(death, 0);
                    if (bridge != null && bridgeDeath != null) bridge.unlinkToDeath(bridgeDeath, 0);
                    bridge = incoming;
                    bridgeDeath = death;
                    lock.notifyAll();
                }
            } catch (RemoteException e) { throw new IllegalStateException("Bridge died", e); }
            Bundle result = new Bundle();
            result.putBinder("lifetime", lifetime);
            return result;
        }
        if (!"get".equals(method)) throw new IllegalArgumentException("Unknown method");
        IBinder current;
        synchronized (lock) {
            // A cold app can race the module's reconnect loop. Publishing runs on
            // another Binder thread; never hold this lock while doing remote IPC.
            long deadline = SystemClock.uptimeMillis() + 1500;
            while (bridge == null) {
                long remaining = deadline - SystemClock.uptimeMillis();
                if (remaining <= 0) break;
                try { lock.wait(remaining); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
            current = bridge;
        }
        Bundle result = new Bundle();
        if (current == null) return result;
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(HostConnection.BRIDGE_DESCRIPTOR);
            if (current.transact(HostConnection.GET_HOST, data, reply, 0)) {
                reply.readException();
                result.putBinder(HostConnection.KEY_BINDER, reply.readStrongBinder());
            }
        } catch (RemoteException e) {
            synchronized (lock) {
                if (bridge == current) {
                    if (bridgeDeath != null) current.unlinkToDeath(bridgeDeath, 0);
                    bridge = null;
                    bridgeDeath = null;
                }
            }
        } finally { data.recycle(); reply.recycle(); }
        return result;
    }

    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public String getType(Uri u) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri u, String s, String[] a) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
}
