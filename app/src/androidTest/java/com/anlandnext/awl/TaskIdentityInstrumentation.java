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
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            long id = Long.parseLong(args.getString("window_id", "-1"));
            String expected = args.getString("label");
            if (id < 0 || expected == null) throw new AssertionError("window_id and label are required");
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
}
