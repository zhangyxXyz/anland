import android.os.IBinder;
import android.os.Parcel;

/** Read-only device probe; compile to DEX and run through root app_process. */
public final class PresentationProbe {
    public static void main(String[] args) throws Exception {
        IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, "anland.host");
        if (binder == null) throw new IllegalStateException("daemon unavailable");
        Parcel request = Parcel.obtain(), reply = Parcel.obtain();
        try {
            request.writeInterfaceToken("anland.IHost");
            binder.transact(4, request, reply, 0);
            int count = reply.readInt();
            for (int i = 0; i < count; i++) {
                long id = reply.readLong();
                int attached = reply.readInt();
                String title = reply.readString();
                Parcel query = Parcel.obtain(), state = Parcel.obtain();
                try {
                    query.writeInterfaceToken("anland.IHost"); query.writeLong(id);
                    binder.transact(20, query, state, 0);
                    System.out.println("id=" + id + " attached=" + attached + " parent=" + state.readLong()
                            + " width=" + state.readInt() + " height=" + state.readInt()
                            + " dialog=" + state.readInt() + " title=" + title);
                } finally { query.recycle(); state.recycle(); }
            }
        } finally { request.recycle(); reply.recycle(); }
    }
}
