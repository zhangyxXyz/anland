import android.os.IBinder;
import android.os.Parcel;

/** Device-only fault injection: send a delayed report for a disposable test
 * window's previous attachment. Never run against a user's working window.
 * Run with app_process: WindowLifecycleProbe pause|focus ID HOST OLD_GENERATION.
 * Verify in the Wayland trace that it emits no leave after the new attach. */
public final class WindowLifecycleProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 4 || !(args[0].equals("pause") || args[0].equals("focus")))
            throw new IllegalArgumentException("pause|focus ID HOST OLD_GENERATION");
        IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, "anland.host");
        if (binder == null) throw new IllegalStateException("daemon unavailable");
        Parcel request = Parcel.obtain(), reply = Parcel.obtain();
        try {
            request.writeInterfaceToken("anland.IHost");
            request.writeLong(Long.parseLong(args[1]));
            boolean pause = args[0].equals("pause");
            if (!pause) request.writeInt(0);
            request.writeLong(Long.parseLong(args[2]));
            request.writeLong(Long.parseLong(args[3]));
            binder.transact(pause ? 6 : 8, request, pause ? null : reply,
                    pause ? IBinder.FLAG_ONEWAY : 0);
            System.out.println("Sent stale " + args[0] + " for test window " + args[1]);
        } finally {
            request.recycle(); reply.recycle();
        }
    }
}
