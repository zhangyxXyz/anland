package com.anlandnext.awl;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Android task removal is an explicit user action; Activity destruction, PAUSE
 * and Binder death are not. A foreground service receives onTaskRemoved even
 * when a document Activity is stopped. Never infer Close from memory pressure,
 * rotation, APK replacement, Home/Back or a daemon-driven task cleanup. */
public final class WindowTaskService extends Service {
    public static final String PREF = "close_on_task_removed";
    private static final String CHANNEL = "window_task_actions";
    private static final int NOTIFICATION = 2074;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Awl.Callback events = new Awl.Callback() {
        public void onWindowCreated(long id, String title) {}
        public void onWindowAttached(long id) {}
        public void onWindowDetached(long id) {}
        public void onWindowDestroyed(long id) { AwlWindowActivity.finishById(WindowTaskService.this, id); }
    };
    public static boolean enabled(Context context) {
        return context.getSharedPreferences("awl", MODE_PRIVATE).getBoolean(PREF, false);
    }
    public static void sync(Context context) {
        Intent service = new Intent(context, WindowTaskService.class);
        if (!enabled(context)) { context.stopService(service); return; }
        try { context.startForegroundService(service); }
        catch (RuntimeException e) { Log.w("anland-tasks", "Cannot watch removed tasks", e); }
    }
    /** Accept only the library's document URI, not a Settings/launcher task. */
    static long windowId(Intent intent) {
        if (intent == null || intent.getData() == null || intent.getComponent() == null) return -1;
        if (!AwlWindowActivity.class.getName().equals(intent.getComponent().getClassName())) return -1;
        android.net.Uri uri = intent.getData();
        if (!"anland".equals(uri.getScheme()) || !"win".equals(uri.getHost()) || uri.getPathSegments().size() != 1) return -1;
        try { return Long.parseLong(uri.getLastPathSegment()); } catch (NumberFormatException e) { return -1; }
    }
    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, getString(R.string.awl_task_channel), NotificationManager.IMPORTANCE_LOW));
        Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
        Notification.Builder notification = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_view).setContentTitle(getString(R.string.awl_task_title))
                .setContentText(getString(R.string.awl_task_text)).setOngoing(true);
        if (launch != null) notification.setContentIntent(PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        startForeground(NOTIFICATION, notification.build());
        Awl.registerCallback(events);
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!enabled(this)) stopSelf();
        return START_NOT_STICKY;
    }
    @Override public void onTaskRemoved(Intent rootIntent) {
        long id = windowId(rootIntent);
        if (id < 0 || !enabled(this)) return;
        worker.execute(() -> {
            java.util.List<Awl.WlWindow> windows = Awl.getWindows();
            if (windows == null || windows.stream().noneMatch(w -> w.id == id)) return;
            int result = Awl.closeWindow(id);
            Log.i("anland-tasks", "Task removed: close window " + id + ", result=" + result);
            // A close request is not an exit. If the client needs confirmation,
            // its transient is hosted normally; never kill a shared process.
            handler.postDelayed(() -> {
                java.util.List<Awl.WlWindow> remaining = Awl.getWindows();
                if (remaining != null && remaining.isEmpty()) stopSelf();
            }, 2000);
        });
    }
    @Override public void onDestroy() {
        Awl.unregisterCallback(events);handler.removeCallbacksAndMessages(null);worker.shutdown();super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
