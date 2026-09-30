package com.anlandnext.awl;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Instrumentation;
import android.os.Bundle;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Opt-in device regression using a disposable, already-mapped Linux client.
 * Its Android document task must not exist yet (disable auto_attach while
 * creating the fixture). Assert identity at onCreate, before first focus,
 * surface attachment or a trip through Recents can mask the startup race. */
public final class TaskIdentityInstrumentation extends Instrumentation {
    private Bundle args;
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); args = arguments; start(); }
    @SuppressWarnings("deprecation") // Assert the dynamic task bitmap, not a packaged resource icon.
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            long id = Long.parseLong(args.getString("window_id", "-1"));
            if ("back".equals(args.getString("mode"))) {
                testBack(id);
                result.putString("stream", "PASS: Back retains the live Linux window and its visible Recents task; the card resumes\n");
                finish(Activity.RESULT_OK, result);
                return;
            }
            String expected = args.getString("label");
            if (id < 0 || expected == null) throw new AssertionError("window_id and label are required");
            // Exercise a foreground user launch. Some OEMs deny background
            // starts even to an instrumented process; the shell opens the
            // exported main page first, without changing device permissions.
            try (android.os.ParcelFileDescriptor descriptor = getUiAutomation().executeShellCommand(
                    "am start -W -n com.anlandnext/.MainActivity");
                 java.io.InputStream input = new android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
                byte[] buffer = new byte[1024];
                while (input.read(buffer) != -1) { }
            }
            waitForIdleSync();
            ActivityManager manager = getTargetContext().getSystemService(ActivityManager.class);
            for (ActivityManager.AppTask task : manager.getAppTasks()) {
                if (WindowTaskService.windowId(task.getTaskInfo().baseIntent) == id)
                    throw new AssertionError("Fixture already has a document task");
            }
            Awl.WlWindow window = null;
            for (Awl.WlWindow candidate : Awl.getWindows()) if (candidate.id == id) window = candidate;
            if (window == null) throw new AssertionError("Fixture Linux window is missing");
            final Awl.WlWindow target = window;
            CountDownLatch created = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            runOnMainSync(() -> Awl.attachWindow(getTargetContext(), target, new Awl.HostCallbacks() {
                @Override public void onHostCreate(Awl.WlWindow win, Activity activity) {
                    try {
                        if (activity.hasWindowFocus()) throw new AssertionError("Check happened after first focus");
                        ActivityManager.TaskDescription description = null;
                        for (ActivityManager.AppTask task : manager.getAppTasks()) {
                            if (task.getTaskInfo().taskId == activity.getTaskId()) description = task.getTaskInfo().taskDescription;
                        }
                        if (description == null || !expected.equals(description.getLabel()))
                            throw new AssertionError("Initial task label is not resolved");
                        if (description.getIcon() == null) throw new AssertionError("Initial task icon is absent");
                    } catch (Throwable error) { failure.set(error); }
                    finally { created.countDown(); }
                }
            }));
            if (!created.await(20, TimeUnit.SECONDS)) throw new AssertionError("Window did not launch");
            if (failure.get() != null) throw new AssertionError("Initial identity failed", failure.get());
            result.putString("stream", "PASS: task label and icon present in onCreate, before first focus\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("stream", android.util.Log.getStackTraceString(error));
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    @SuppressWarnings("deprecation")
    private void testBack(long id) throws Exception {
        if (id < 0) throw new AssertionError("window_id is required");
        try (android.os.ParcelFileDescriptor fd = getUiAutomation().executeShellCommand(
                "am start -W -n com.anlandnext/.MainActivity");
             java.io.InputStream input = new android.os.ParcelFileDescriptor.AutoCloseInputStream(fd)) {
            while (input.read() != -1) { }
        }
        Awl.WlWindow window = null;
        for (Awl.WlWindow item : Awl.getWindows()) if (item.id == id) window = item;
        if (window == null) throw new AssertionError("Linux window missing");
        final Awl.WlWindow target = window;
        CountDownLatch resumed = new CountDownLatch(1);
        AtomicReference<Activity> host = new AtomicReference<>();
        runOnMainSync(() -> Awl.attachWindow(getTargetContext(), target, new Awl.HostCallbacks() {
            @Override public void onHostResume(Awl.WlWindow win, Activity activity) {
                host.set(activity);
                resumed.countDown();
            }
        }));
        if (!resumed.await(20, TimeUnit.SECONDS)) throw new AssertionError("Host did not resume");
        Activity activity = host.get();
        int taskId = activity.getTaskId();
        // Exercise the callback immediately, including before asynchronous identity completion.
        runOnMainSync(activity::onBackPressed);
        waitForIdleSync();
        if (activity.isFinishing() || activity.isDestroyed()) throw new AssertionError("Back finished the host");
        ActivityManager.AppTask retained = null;
        for (ActivityManager.AppTask item : getTargetContext().getSystemService(ActivityManager.class).getAppTasks()) {
            if (item.getTaskInfo().taskId == taskId) retained = item;
        }
        if (retained == null) throw new AssertionError("Back removed the task");
        if ((retained.getTaskInfo().baseIntent.getFlags() & android.content.Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS) != 0)
            throw new AssertionError("Task is still excluded from Recents");
        boolean alive = false;
        for (Awl.WlWindow item : Awl.getWindows()) if (item.id == id) alive = true;
        if (!alive) throw new AssertionError("Back closed the Linux window");
        final ActivityManager.AppTask card = retained;
        runOnMainSync(card::moveToFront);
        waitForIdleSync();
        if (activity.isFinishing()) throw new AssertionError("Cannot resume retained card");
    }
}
