package com.anlandnext.awl;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import com.anlandnext.WlBinder;
import java.util.List;

/** Device check of the production client path, without changing windows or settings. */
final class BinderDiscoveryProbe {
    static String run(Context context, Bundle args) throws Exception {
        HostConnection.initialize(context);
        IBinder binder = HostConnection.get();
        if (binder == null || !binder.pingBinder()) throw new AssertionError("Host Binder unavailable");
        if (!HostConnection.HOST_DESCRIPTOR.equals(binder.getInterfaceDescriptor()))
            throw new AssertionError("Wrong host descriptor");
        boolean rejected = false;
        try {
            context.getContentResolver().call(Uri.parse("content://" + HostConnection.AUTHORITY),
                    "publish", null, new Bundle());
        } catch (SecurityException expected) { rejected = true; }
        if (!rejected) throw new AssertionError("App can replace root bridge");
        if (WlBinder.configGet("zoom") < 0) throw new AssertionError("Host configuration unavailable");
        List<Awl.WlWindow> windows = Awl.getWindows();
        if (windows == null || !Awl.supportsContainerSelection())
            throw new AssertionError("Window/container discovery unavailable");
        if ("1".equals(args.getString("expect_private"))) {
            Object direct = Class.forName("android.os.ServiceManager")
                    .getMethod("checkService", String.class).invoke(null, "anland.host");
            if (direct != null) throw new AssertionError("App can still discover the service directly");
        }
        return "PASS: provider discovery, publication protection, host config, "
                + windows.size() + " windows and container protocol"
                + ("1".equals(args.getString("expect_private")) ? "; direct lookup denied" : "") + "\n";
    }
}
