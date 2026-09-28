package com.anlandnext.awl;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;

/** Independent Android task adapter. The complete input/render/IME lifecycle
 * lives in AwlWindowHost, shared with desktop panes (including its original
 * protocol and regression comments). Third-party late-binding API is retained. */
public class AwlWindowActivity extends Activity {
    private AwlWindowHost host;
    protected boolean onAwaitWindow() { return false; }
    protected final void hostWindow(long id, String title, Awl.HostCallbacks callbacks) {
        host.hostWindow(id, title, callbacks);
    }
    public static void finishById(long id) { AwlWindowHost.finishById(id); }
    public static void finishById(Context context, long id) { AwlWindowHost.finishById(context, id); }
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        long id = getIntent().getLongExtra("id", -1);
        if (id >= 0 && Awl.routeWindow(this, id, getIntent().getStringExtra("title"))) {
            Awl.hostArrived(id);
            finishAndRemoveTask();
            return;
        }
        host = new AwlWindowHost(this, getIntent(), false, onAwaitWindow(), null);
        host.onCreate(state);
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        long id = intent.getLongExtra("id", -1);
        if (id >= 0 && Awl.routeWindow(this, id, intent.getStringExtra("title"))) {
            Awl.hostArrived(id);
            finishAndRemoveTask();
        } else if (host != null) host.onNewIntent(intent);
    }
    @Override protected void onStart() { super.onStart(); if (host != null) host.onStart(); }
    @Override protected void onResume() { super.onResume(); if (host != null) host.onResume(); }
    @Override protected void onPause() { if (host != null) host.onPause(); super.onPause(); }
    @Override protected void onStop() { if (host != null) host.onStop(); super.onStop(); }
    @Override protected void onDestroy() { if (host != null) host.onDestroy(); super.onDestroy(); }
    @Override public void onWindowFocusChanged(boolean focus) { super.onWindowFocusChanged(focus); if (host != null) host.onWindowFocusChanged(focus); }
    @Override public void onPointerCaptureChanged(boolean capture) { super.onPointerCaptureChanged(capture); if (host != null) host.onPointerCaptureChanged(capture); }
    @Override public boolean dispatchTouchEvent(MotionEvent e) { return host != null && host.dispatchTouchEvent(e) || super.dispatchTouchEvent(e); }
    @Override public boolean onGenericMotionEvent(MotionEvent e) { return host != null && host.onGenericMotionEvent(e) || super.onGenericMotionEvent(e); }
    @Override public boolean dispatchKeyEvent(KeyEvent e) { return host != null && host.dispatchKeyEvent(e) || super.dispatchKeyEvent(e); }
}
