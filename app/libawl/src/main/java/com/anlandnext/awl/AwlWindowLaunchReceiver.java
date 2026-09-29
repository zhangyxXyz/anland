package com.anlandnext.awl;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Daemon launch bridge with no intermediate Activity or document task. */
public final class AwlWindowLaunchReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!"anland.ATTACH_WINDOW".equals(intent.getAction())) return;
        long id = intent.getLongExtra("id", -1);
        if (id < 0) return;
        // A window can disappear while the explicit broadcast is queued.
        java.util.List<Awl.WlWindow> windows = Awl.getWindows();
        if (windows == null) return;
        for (Awl.WlWindow window : windows) {
            if (window.id == id) {
                PendingResult pending = goAsync();
                Awl.attachWindow(context, window, null, pending::finish);
                return;
            }
        }
    }
}
