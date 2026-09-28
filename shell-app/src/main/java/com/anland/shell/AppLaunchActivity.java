package com.anland.shell;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.anland.shell.ds.DsCli;
import com.anland.shell.ds.EnvVars;
import com.anland.shell.ds.RootExec;
import com.anland.shell.ds.ShellUtils;

import java.util.List;

/**
 * Translucent trampoline used by both grid taps and pinned home-screen
 * shortcuts: make sure the container is running (boot + poll if not), then
 * launch the app detached inside it (DsCli.launchApp — nohup, returns
 * immediately, survives this process) and finish. noHistory +
 * excludeFromRecents keep it invisible in the flow.
 */
public final class AppLaunchActivity extends androidx.appcompat.app.AppCompatActivity {

    private TextView msg;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final String container = getIntent().getStringExtra("container");
        final String exec = getIntent().getStringExtra("exec");
        final String user = getIntent().getStringExtra("user");
        String name = getIntent().getStringExtra("app_name");
        if (container == null || container.isEmpty() || exec == null || exec.isEmpty()) {
            finish();
            return;
        }
        final String appName = name == null || name.isEmpty() ? exec : name;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setKeepScreenOn(true);

        ProgressBar bar = new ProgressBar(this);
        root.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        msg = new TextView(this);
        msg.setText(getString(R.string.launching_fmt, appName));
        msg.setTextSize(14);
        msg.setGravity(Gravity.CENTER);
        msg.setPadding(0, dp(16), 0, 0);
        root.addView(msg, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);

        RootExec.POOL.execute(() -> launch(container, appName, exec, user));
    }

    private void launch(String container, String appName, String exec, String user) {
        try {
            if (DsCli.pid(container) <= 0) {
                post(R.string.launch_starting_fmt, container);
                RootExec.Result r = DsCli.start(container);
                if (!r.ok || !DsCli.awaitRunning(container, 90_000)) {
                    fail(getString(r.ok ? R.string.start_timeout_fmt : R.string.start_failed_fmt,
                            container, r.ok ? "" : errText(r)));
                    return;
                }
            }

            List<String> argv = ShellUtils.parseExec(exec);
            if (argv.isEmpty()) {
                fail(getString(R.string.launch_failed_fmt, appName,
                        getString(R.string.launch_empty_exec)));
                return;
            }

            List<String[]> customEnv = EnvVars.parse(Prefs.launchEnv(this, container));
            boolean desktopEntry = getIntent().getBooleanExtra("desktop_session", false)
                    || "org.freedesktop.Xwayland".equals(getIntent().getStringExtra("window_app_id"));
            String launchUser = user == null || user.isEmpty() ? DsCli.autoUser(container) : user;
            String probe;
            try (java.io.InputStream input = getAssets().open("desktop-session-probe.py")) {
                java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                byte[] chunk = new byte[4096]; int n;
                while ((n = input.read(chunk)) != -1) bytes.write(chunk, 0, n);
                probe = new String(bytes.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
            }
            String probeUser = launchUser.isEmpty() ? "root" : launchUser;
            // /proc/<pid>/environ must be read as its owner in this container:
            // container root lacks CAP_SYS_PTRACE and cannot read other users.
            String probeCommand = "python3 -c " + ShellUtils.shQuote(probe) + " " + ShellUtils.shQuote(probeUser);
            RootExec.Result session = DsCli.runSh(container, "su - " + ShellUtils.shQuote(probeUser)
                    + " -c " + ShellUtils.shQuote(probeCommand), 15000);
            if (!session.ok) throw new IllegalStateException(errText(session));
            org.json.JSONObject state = new org.json.JSONObject(session.stdout.trim());
            final boolean inDesktop = state.getBoolean("active");
            if (desktopEntry && !inDesktop) {
                android.os.Bundle windows = getContentResolver().call(android.net.Uri.parse("content://com.anlandnext.sessions"), "windows", null, null);
                if (windows == null) throw new IllegalStateException(getString(R.string.host_update_needed));
                if (windows.getInt("independent") > 0) {
                    runOnUiThread(() -> LaunchUi.blocked(this, () -> {
                        startActivity(new android.content.Intent().setClassName("com.anlandnext", "com.anlandnext.MainActivity"));
                        finish();
                    }));
                    return;
                }
            }
            if (inDesktop && !desktopEntry) {
                // Override after DsCli's standalone environment, including its user bus.
                // Unset inherited Wayland endpoints, but keep all GPU/ANGLE settings.
                List<String> routed = new java.util.ArrayList<>(java.util.Arrays.asList("env", "-u", "WAYLAND_DISPLAY", "-u", "WAYLAND_SOCKET", "-u", "SESSION_MANAGER"));
                org.json.JSONObject env = state.getJSONObject("env");
                java.util.Iterator<String> keys = env.keys();
                while (keys.hasNext()) { String k = keys.next(); routed.add(k + "=" + env.getString(k)); }
                routed.addAll(argv); argv = routed;
            }
            final boolean showDesktop = desktopEntry || inDesktop;
            RootExec.Result r = desktopEntry && inDesktop
                    ? session : DsCli.launchApp(container, argv, launchUser, customEnv);
            if (r.ok) {
                runOnUiThread(() -> {
                    if (inDesktop && !desktopEntry) Toast.makeText(this, R.string.launched_in_desktop, Toast.LENGTH_LONG).show();
                    String windowAppId = showDesktop ? "org.freedesktop.Xwayland" : getIntent().getStringExtra("window_app_id");
                    if (windowAppId != null && !windowAppId.isEmpty()) {
                        try {
                            startActivity(new android.content.Intent().setClassName("com.anlandnext", "com.anlandnext.OpenWindowActivity")
                                    .putExtra("window_app_id",windowAppId)
                                    .putExtra("desktop_id",showDesktop ? null : getIntent().getStringExtra("id")));
                        } catch (android.content.ActivityNotFoundException e) {
                            Toast.makeText(this,R.string.host_update_needed,Toast.LENGTH_LONG).show();
                        }
                    }
                    finish();
                });
            } else {
                fail(getString(R.string.launch_failed_fmt, appName, errText(r)));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("interrupted");
        } catch (Exception e) {
            fail(getString(R.string.launch_failed_fmt, appName, e.getMessage()));
        }
    }

    private void post(final int res, final Object... args) {
        runOnUiThread(() -> msg.setText(getString(res, args)));
    }

    private void fail(final String text) {
        runOnUiThread(() -> {
            Toast.makeText(this, text, Toast.LENGTH_LONG).show();
            finish();
        });
    }

    private static String errText(RootExec.Result r) {
        if (r.error != null)
            return r.error;
        String e = r.stderr.trim();
        return e.isEmpty() ? "exit " + r.exit : e.split("\n", 2)[0];
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
