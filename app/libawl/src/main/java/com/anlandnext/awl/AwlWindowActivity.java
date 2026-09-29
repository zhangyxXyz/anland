package com.anlandnext.awl;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.CompletionInfo;
import android.view.inputmethod.CorrectionInfo;
import android.view.inputmethod.CursorAnchorInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.SurroundingText;
import android.view.inputmethod.TextAttribute;
import android.widget.EditText;
import android.widget.FrameLayout;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One wayland window = one Activity instance (id passed as extra), running
 * INSIDE the consuming app: the Android window/task identity, process, uid
 * and binder credentials are the third-party app's — the anland host APK
 * takes no part. The Surface is reported to the daemon over binder (the
 * daemon holds the rendering resources and renders straight into this
 * window; its uid pass accepts the report because the wayland connection
 * came from {@link Awl#getWaylandFd} under the same uid).
 *
 * Lifecycle (single-attach model: this side only reports facts, all
 * decisions live in the daemon):
 *   onStop → PAUSE(id,host): daemon full detach (minimize: the wayland
 *   window stays alive); onResume / surfaceChanged → re-send SURFACE to
 *   re-attach (the daemon evicts the previous holder).
 *   Killed-from-recents / swiped away = minimize-and-keep-alive (onStop +
 *   binder death already cover it, no onDestroy report).
 *   The window is closed via Awl.closeWindow (daemon T_CLOSE). Client
 *   closed its own window / evicted by a newer instance → daemon ctrl
 *   C_CLOSE → finish.
 *
 * IME bridge (text-input protocol ↔ Android IME): hidden EditText display
 * side + a full InputConnection table bridged to the text-input protocol
 * over binder; the daemon pushes the client's editor state.
 */
public class AwlWindowActivity extends Activity {
    private static final String TAG = "anland-awlwin";
    private static final ConcurrentHashMap<Long, AwlWindowActivity> LIVE = new ConcurrentHashMap<>();

    /** Control-channel descriptor (daemon AIBinder_Class_define "anland.ICtrl") */
    private static final String CTRL_DESC = "anland.ICtrl";
    /** daemon → Activity command codes */
    private static final int C_CLOSE = 1;      /* wayland window destroyed → exit */
    private static final int C_TITLE = 2;      /* (title:string16) title update → Recents label */
    private static final int C_IME_SHOW = 3;   /* (hint purpose:i32) text field focused → show soft keyboard */
    private static final int C_IME_HIDE = 4;   /* () text field unfocused → hide soft keyboard */
    private static final int C_IME_STATE = 5;  /* (hint purpose cursor anchor cx cy cw ch flags
                                                  + text:string16) editor state snapshot */
    private static final int C_CLIP_WRITE = 6; /* (text:string16) wl set_selection → write
                                                  the Android clipboard (empty = clear) */
    private static final int C_CAPTURE = 7;    /* (mode x y w h:i32×5) pointer-constraints
                                                  state sync (zwp lock/confine):
                                                  mode = 1 confine / 2 lock →
                                                  requestPointerCapture
                                                  (x,y,w,h = confine region
                                                  in view px, zeros = whole
                                                  window), 0 → release;
                                                  re-sent on set_region and
                                                  re-attach */
    private static final int C_CURSOR = 8;     /* (hidden:i32) wl client took over the
                                                  cursor (image composited by the
                                                  daemon renderer on top of the
                                                  window, or NULL = invisible)
                                                  → hide the Android pointer; 0 → restore */
    private static final int C_KEEPON = 9;     /* (on:i32) zwp_idle_inhibit_manager_v1
                                                  aggregate flipped: 1 →
                                                  FLAG_KEEP_SCREEN_ON, 0 → clear;
                                                  re-sent on re-attach */
    private static final int C_ICON = 10;      /* (has:i32) toplevel icon applied/reset
                                                  → re-fetch the pixels and re-apply
                                                  the task description */
    private static final int STATE_RESET = 0x1;   /* v1 reset → clear composing state + restartInput */
    private static final int CAPTURE_NONE = 0;
    private static final int CAPTURE_CONFINE = 1;
    private static final int CAPTURE_LOCK = 2;

    /** Unique Activity instance id (trailing host field of SURFACE/PAUSE: daemon eviction criterion) */
    private static final AtomicLong HOST_SEQ = new AtomicLong();

    private long id = -1;   /* -1 = unbound (bindWindowId's firstBind test; a plain long would read 0 = "bound to window 0") */
    private long host;
    private long surfaceGeneration;
    private boolean windowSubscribed;
    private boolean redirectingDialog;
    private SurfaceView sv;
    private FrameLayout root;
    private EditText hiddenInput;
    private InputMethodManager imm;
    private CtrlBinder ctrl;
    private int lastW, lastH;
    private boolean attached;
    private boolean finishingByGone;   /* client closed the window / evicted, nothing left to report */
    private boolean deathLinked;       /* daemon death monitoring attached */
    private String taskTitle;          /* last known client title (Recents label) */
    private String taskDesktopName;
    private String taskAppId;
    private boolean taskIdentityReady, taskPublished;
    private boolean iconFetchRunning, iconFetchPending;
    private final java.util.concurrent.atomic.AtomicInteger iconRequest =
            new java.util.concurrent.atomic.AtomicInteger();
    private android.graphics.Bitmap taskIcon;   /* last fetched toplevel icon */

    /* ---- Clipboard bridge (static shared = consistent across windows,
     *      single process) ----
     * lastClipWritten: text we wrote via C_CLIP_WRITE — setPrimaryClip fires
     * the listener too, so compare-and-suppress the echo push (otherwise
     * wl↔Android loops forever).
     * lastClipPushed:  text last pushed to the daemon (repeated focus changes
     * don't re-push). */
    private static String sLastClipWritten;
    private static String sLastClipPushed;
    private ClipboardManager clipMgr;

    /* ---- IME state (pushed by daemon ctrl + written by InputConnection; all on the UI thread) ---- */

    private String surText = "";        /* client surrounding (no preedit) */
    private int surCursor, surAnchor;   /* char indices into surText */
    private int imeHint, imePurpose;    /* zwp_text_input content type */
    private final int[] imeRect = new int[4];   /* cursor rect (surface coords) */
    private boolean imeWanted;          /* input wanted (kept across detach, basis for reopening) */
    private String compText = "";       /* mirror of the preedit we sent (inserted at surCursor) */
    private int compCursor;             /* char cursor inside the preedit */

    /* Consumer hosting hooks (Awl.attachWindow(ctx, win, HostCallbacks)):
     * fetched once per instance below; null = started by the daemon /
     * attached without hooks. Fired on the main thread inside the matching
     * lifecycle method; consumer exceptions are contained (logged, never
     * propagated into the host). */
    private Awl.HostCallbacks hostCbs;
    private Awl.WlWindow hostWin;

    private interface HostFire {
        void fire(Awl.HostCallbacks cbs, Awl.WlWindow win, Activity activity);
    }

    private void fireHost(HostFire f) {
        if (hostCbs != null) {
            try {
                f.fire(hostCbs, hostWin, this);
            } catch (Throwable t) {
                Log.e(TAG, "host lifecycle callback threw", t);
            }
        }
    }

    /** Window events while resumed: a sibling window of this app destroyed
     *  (its Activity paused, ctrl already torn down at detach) → finish that
     *  instance; own window's destroy also arrives over ctrl (C_CLOSE) —
     *  finishById is id-addressed and finish()-guarded, double delivery is
     *  harmless. */
    private final Awl.Callback winEvents = new Awl.Callback() {
        @Override public void onWindowCreated(long wid, String title) { }
        @Override public void onWindowAttached(long wid) { }
        @Override public void onWindowDetached(long wid) { }
        @Override public void onWindowDestroyed(long wid) { finishById(wid); }
    };

    /**
     * Local end of the control channel: reported to the daemon with SURFACE —
     * it is both the death token (app killed → the daemon's binder death
     * detaches automatically) and the endpoint the daemon sends commands to
     * (CLOSE / IME).
     */
    class CtrlBinder extends Binder {
        CtrlBinder() {
            /* Publish the descriptor: the daemon's libbinder_ndk
             * AIBinder_associateClass() queries it (INTERFACE_TRANSACTION)
             * and AIBinder_prepareTransaction() refuses any transaction on a
             * mismatch. */
            attachInterface(null, CTRL_DESC);
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws android.os.RemoteException {
            if (code < FIRST_CALL_TRANSACTION || code > LAST_CALL_TRANSACTION)
                return super.onTransact(code, data, reply, flags);   /* INTERFACE_TRANSACTION / PING / DUMP: no interface token in the parcel */
            try {
                data.enforceInterface(CTRL_DESC);
            } catch (Exception e) {
                return false;   /* descriptor mismatch (not from the daemon) — reject */
            }
            if (code == C_CLOSE) {
                Log.i(TAG, "win " + id + ": CLOSE via ctrl channel");
                finishingByGone = true;
                runOnUiThread(() -> finishAndDropTask());
                return true;
            }
            if (code == 11) { // C_PRESENTATION: protocol parent/size hints changed
                runOnUiThread(() -> updatePresentation());
                return true;
            }
            if (code == C_TITLE) {
                String t = data.readString();
                if (t != null) {
                    runOnUiThread(() -> {
                        taskTitle = t;
                        applyTaskDescription();
                        applyTaskIconAsync();
                    });
                }
                return true;
            }
            if (code == C_IME_SHOW) {
                int hint = data.readInt();
                int purpose = data.readInt();
                runOnUiThread(() -> onImeShow(hint, purpose));
                return true;
            }
            if (code == C_IME_HIDE) {
                runOnUiThread(this::onImeHideCmd);
                return true;
            }
            if (code == C_IME_STATE) {
                int hint = data.readInt(), purpose = data.readInt();
                int curB = data.readInt(), ancB = data.readInt();
                int cx = data.readInt(), cy = data.readInt();
                int cw = data.readInt(), ch = data.readInt();
                int fl = data.readInt();
                String text = data.readString();
                runOnUiThread(() -> onImeState(text, curB, ancB, hint, purpose,
                                              cx, cy, cw, ch, fl));
                return true;
            }
            if (code == C_CLIP_WRITE) {
                String t = data.readString();
                final String text = t == null ? "" : t;
                runOnUiThread(() -> writeClipboard(text));
                return true;
            }
            if (code == C_CAPTURE) {
                final int mode = data.readInt();
                final int rx = data.readInt(), ry = data.readInt();
                final int rw = data.readInt(), rh = data.readInt();
                runOnUiThread(() -> setPointerCaptureMode(mode, rx, ry, rw, rh));
                return true;
            }
            if (code == C_CURSOR) {
                final boolean hidden = data.readInt() != 0;
                runOnUiThread(() -> setPointerHidden(hidden));
                return true;
            }
            if (code == C_KEEPON) {
                final boolean on = data.readInt() != 0;
                runOnUiThread(() -> applyKeepOn(on));
                return true;
            }
            if (code == C_ICON) {
                final boolean has = data.readInt() != 0;
                runOnUiThread(() -> {
                    if (!has) {
                        iconRequest.incrementAndGet(); // cancel an older icon fetch
                        taskIcon = null;
                        applyTaskDescription();
                        applyTaskIconAsync(); // reset may still have a desktop-file fallback
                    } else applyTaskIconAsync();
                });
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }

        private void onImeHideCmd() {
            onImeHide();
        }
    }

    /** Daemon (peer of the long-lived binder connection) death → this window
     *  lost its host, exit */
    private final IBinder.DeathRecipient daemonDeath = new IBinder.DeathRecipient() {
        @Override
        public void binderDied() {
            Log.e(TAG, "win " + id + ": daemon gone (binder death) -> finish");
            finishingByGone = true;   /* DETACH has nowhere to go now */
            /* the wl-side selection died with the daemon — clear the push
             * bookkeeping; once a new daemon is up, the focused window
             * re-pushes the current clipboard */
            sLastClipPushed = null;
            sLastClipWritten = null;
            runOnUiThread(() -> finishAndDropTask());
        }
    };

    /** Leave for good, Recents card included. Every finish of this host
     *  means its window cannot be shown again (client quit, daemon dead,
     *  SURFACE rejected at onCreate/onResume re-attach, no id): a card left
     *  behind would relaunch onto a dead window id and exit at once. The
     *  removal is explicit rather than left to the manifest's
     *  autoRemoveFromRecents — that flag is evaluated at Task.setIntent
     *  time, so a task created before it existed, or re-entered without a
     *  setIntent re-evaluation, still carries autoRemoveRecents=false and
     *  a plain finish() would retain it. finishAndRemoveTask on the task's
     *  root activity removes the task (ActivityClientController:
     *  FINISH_TASK_WITH_ROOT_ACTIVITY → removeTask(REMOVE_FROM_RECENTS)). */
    private void finishAndDropTask() {
        if (isFinishing()) return;
        if (this instanceof AwlDialogActivity) {
            finish();
            overridePendingTransition(0, 0);
        } else if (!isTaskRoot()) finish();
        else finishAndRemoveTask();
    }

    public static void finishById(long id) {
        AwlWindowActivity a = LIVE.get(id);
        if (a != null) {
            a.finishingByGone = true;
            a.finishAndDropTask();
            Log.i(TAG, "win " + id + " finished (client gone)");
        }
    }

    /** Same, plus a Recents sweep for a host whose PROCESS is already gone
     *  (LMK / force-stop while the window lived on in the daemon): there is
     *  no instance to finish, yet the task — root ActivityRecord, no process
     *  — still sits in Recents and would relaunch onto a dead window id.
     *  The manifest's autoRemoveFromRecents only fires on finish(), so the
     *  task whose base intent carries this window's URI is removed by hand.
     *  For the host APK's WINDOW_GONE receiver: a component broadcast, so
     *  it has a Context and is delivered even with the app process dead. */
    public static void finishById(android.content.Context ctx, long id) {
        finishById(id);
        removeTaskById(ctx, id);
    }

    /** Reconcile retained Android document tasks with an authoritative daemon
     * snapshot. Null means disconnected, never "all clients have exited". This
     * also repairs missed one-way CLOSE delivery to a stopped/frozen host. */
    public static void reconcileTasks(android.content.Context ctx, java.util.List<Awl.WlWindow> windows) {
        if (ctx == null || windows == null) return;
        android.app.ActivityManager manager = ctx.getSystemService(android.app.ActivityManager.class);
        if (manager == null) return;
        java.util.List<android.app.ActivityManager.AppTask> tasks = manager.getAppTasks();
        // Snapshot tasks first, then re-read the daemon: a newly created window
        // must not be mistaken for an orphan because the UI snapshot was older.
        java.util.List<Awl.WlWindow> current = Awl.getWindows();
        if (current == null) return;
        java.util.Set<Long> alive = new java.util.HashSet<>();
        for (Awl.WlWindow window : current) alive.add(window.id);
        for (android.app.ActivityManager.AppTask task : tasks) {
            try {
                long windowId = WindowTaskService.windowId(task.getTaskInfo().baseIntent);
                if (windowId >= 0 && !alive.contains(windowId)) finishById(ctx, windowId);
            } catch (RuntimeException e) { Log.w(TAG, "Task changed during reconciliation", e); }
        }
    }

    private static void removeTaskById(android.content.Context ctx, long id) {
        if (ctx == null || id < 0) return;
        android.app.ActivityManager am =
                ctx.getSystemService(android.app.ActivityManager.class);
        if (am == null) return;
        final String uri = "anland://win/" + id;   /* Awl.attachWindow / daemon am start -d */
        try {
            for (android.app.ActivityManager.AppTask t : am.getAppTasks()) {
                android.app.ActivityManager.RecentTaskInfo ti = t.getTaskInfo();
                android.content.Intent base = ti != null ? ti.baseIntent : null;
                if (base == null || base.getData() == null) continue;
                if (!uri.equals(base.getData().toString())) continue;
                t.finishAndRemoveTask();
                Log.i(TAG, "win " + id + " task removed from recents (no live host)");
            }
        } catch (Exception e) {
            Log.w(TAG, "win " + id + ": recents sweep failed", e);
        }
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        /* defensive id switch when documentLaunchMode reuses this instance for the same data URI */
        long nid = intent.getLongExtra("id", id);
        if (nid != id)
            bindWindowId(nid, intent.getStringExtra("title"), null, true);
        else if (id >= 0) {
            Awl.hostArrived(id); // an existing document also completes the pending launch
            taskPublished = false;
            applyTaskDescription();
            applyTaskIconAsync();
        }
    }

    /* ---- window binding ----
     * Everything id-dependent. Two entry flavors:
     *   fromRegistry  onCreate/onNewIntent (attachWindow / daemon am-start):
     *                 hooks come from Awl's attach registry (hostEntry)
     *   explicit      subclass late binding (hostWindow): hooks given inline
     * A re-bind to a different window switches cleanly (the onNewIntent
     * defensive path); firstBind additionally arms the daemon-death watch. */
    private void bindWindowId(long newId, String title, Awl.HostCallbacks cbs,
                              boolean fromRegistry) {
        if (newId == id || newId < 0) return;
        boolean firstBind = id < 0;
        if (!firstBind) {   /* switching away from a bound window */
            if (attached) AwlClient.pause(id, host, surfaceGeneration);   /* daemon detaches the old id */
            setPointerCaptureMode(CAPTURE_NONE, 0, 0, 0, 0);   /* the old window's constraint does not carry over */
            LIVE.remove(id, this);
            attached = false;
        }
        id = newId;
        iconRequest.incrementAndGet();
        taskTitle = null;
        taskDesktopName = null;
        taskAppId = null;
        taskIcon = null;
        taskIdentityReady = false;
        taskPublished = false;
        if (root != null) root.requestApplyInsets();
        host = HOST_SEQ.incrementAndGet();
        surfaceGeneration = 0;
        ctrl = new CtrlBinder();
        /* lastW/H keep the surface size (an Android-window property, not a
         * wayland-window one): if surfaceChanged already fired while awaiting,
         * they are exactly what the catch-up below needs — resetting them
         * would deadlock the late bind (no further surfaceChanged comes for
         * an unchanged size) */
        surText = ""; surCursor = surAnchor = 0;
        compText = ""; compCursor = 0;
        markedStart = markedEnd = -1;
        imeBatchDepth = 0;
        imeWanted = false;
        LIVE.put(id, this);

        if (fromRegistry) {
            Awl.hostArrived(id);
            Awl.HostEntry he = Awl.hostEntry(id);
            hostCbs = he != null ? he.cbs : null;
            hostWin = he != null ? he.win : null;
            if (he != null && he.identity != null) {
                taskIdentityReady = true;
                taskTitle = he.identity.title;
                taskIcon = he.identity.icon;
                if (he.identity.desktop != null) {
                    taskAppId = he.identity.desktop.appId;
                    taskDesktopName = he.identity.desktop.name;
                }
            }
        } else {
            hostWin = new Awl.WlWindow(id, true, title);
            hostCbs = cbs;
        }

        /* Recents shows the wayland window's real title (the UI itself has no title bar, only the task label) */
        if (taskTitle == null) taskTitle = title;
        applyTaskDescription();

        if (firstBind) {
            /* Long-lived binder death monitoring: daemon gone (module restart / killed) → exit, no dead windows left behind */
            deathLinked = AwlClient.monitorDeath(daemonDeath);
            if (!deathLinked && !AwlClient.available()) {
                Log.e(TAG, "win " + id + ": daemon unreachable -> finish");
                finishAndDropTask();
                return;
            }
        } else {
            Log.i(TAG, "win re-bound to id=" + id);
        }

        /* the surface may already be up (late binding: UI was built while
         * awaiting, surfaceChanged recorded its size) — report it now;
         * hosting then proceeds exactly like the started-with-id path */
        if (!attached && lastW > 0 && sv != null
                && sv.getHolder().getSurface() != null
                && sv.getHolder().getSurface().isValid())
            sendSurface(sv.getHolder(), lastW, lastH);

        fireHost((c2, w2, a2) -> c2.onHostCreate(w2, a2));
    }

    /** Subclass opt-in for LATE BINDING: return true to keep this activity
     *  alive when started WITHOUT a window id — the subclass then starts its
     *  wayland app (onCreate) and calls {@link #hostWindow} when the window
     *  appears (e.g. from {@link Awl.Callback#onWindowCreated}). super cannot
     *  be deferred until then (the platform lifecycle waits for no async
     *  event) — the bind happens inside the library instead, and hosting
     *  proceeds identically afterwards. Default false keeps the legacy
     *  behavior (no id → finish at once). */
    protected boolean onAwaitWindow() { return false; }

    /** Late-bind the hosted window (await mode only; main thread): bind THIS
     *  activity to the window — task/identity = your app — install the
     *  hosting callbacks (null = none) and, if the surface is already up,
     *  report it to the daemon immediately. */
    protected final void hostWindow(long windowId, String title, Awl.HostCallbacks cbs) {
        bindWindowId(windowId, title, cbs, false);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        /* the manifest theme belongs to THIS class's declaration — a SUBCLASS
         * declared without a theme falls back to the app default (title bar
         * and all). Kill the title programmatically so subclasses inherit
         * the chrome-free look with zero manifest work. */
        try {
            requestWindowFeature(Window.FEATURE_NO_TITLE);
        } catch (Exception e) {
            Log.w(TAG, "FEATURE_NO_TITLE (content already set by a subclass?)", e);
        }
        imm = getSystemService(InputMethodManager.class);
        clipMgr = getSystemService(ClipboardManager.class);

        /* Local, NOT the field: bindWindowId's first line is
         * `if (newId == id) return;` — pre-assigning the field made the bind a
         * no-op for every daemon/attachWindow-started instance (no ctrl, no
         * death token, no LIVE entry, host=0 → SURFACE attached without a
         * ctrl channel, so C_CLOSE / WINDOW_GONE could never finish it; the
         * window stayed on its last frame after the client quit). */
        long startId = getIntent().getLongExtra("id", -1);
        if (startId >= 0 && redirectDialog(startId)) return;
        if (startId >= 0) {
            bindWindowId(startId, getIntent().getStringExtra("title"), null, true);
        } else if (onAwaitWindow()) {
            /* awaiting mode: no daemon-death watch yet (armed at first bind);
             * but with the daemon GONE there is nothing to wait for */
            if (!AwlClient.available()) {
                Log.e(TAG, "await: daemon unreachable -> finish");
                finishAndDropTask();
                return;
            }
            Log.i(TAG, "awaiting a wayland window (unbound)");
        } else {
            finishAndDropTask();   /* started without a window id: nothing to host, nothing to resume */
            return;
        }

        sv = new SurfaceView(this);
        sv.getHolder().setFormat(android.graphics.PixelFormat.RGBX_8888);
        sv.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder holder) { }
            @Override public void surfaceChanged(SurfaceHolder holder, int format,
                                                 int width, int height) {
                if (id < 0) {
                    /* still awaiting: remember the surface, report after bind */
                    Log.i(TAG, "awaiting: surface ready " + width + "x" + height);
                    lastW = width;
                    lastH = height;
                    return;
                }
                Log.i(TAG, "win " + id + " surface " + width + "x" + height);
                if (!attached)
                    sendSurface(holder, width, height);
                else if (width != lastW || height != lastH) {
                    /* window resize (rotation/split-screen/IME inset) → configure the client to follow */
                    AwlClient.resize(id, width, height);
                    lastW = width;
                    lastH = height;
                }
            }
            @Override public void surfaceDestroyed(SurfaceHolder holder) {
                detachVisibleSurface();
            }
        });

        initHiddenInput();

        /* freeform windows report the bottom resize bar as a content inset;
         * with decor-fits enabled the DecorView pads the content up and the
         * bar strip exposes the black window background. Take over inset
         * handling so the surface draws under the bar. */
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            // Insets below own the viewport. Platform adjustPan would move
            // the whole host independently of that same keyboard animation.
            if (!(this instanceof AwlDialogActivity))
                getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        }

        root = new FrameLayout(this);
        root.setFitsSystemWindows(false);
        root.addView(sv, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        root.addView(hiddenInput, new FrameLayout.LayoutParams(1, 1));
        /* IME inset: in inset mode the surface yields (client reflows); in overlay mode the keyboard floats above */
        if (android.os.Build.VERSION.SDK_INT >= 30)
            SurfaceInsetsController.install(root, this::applyContentInsets);
        setContentView(root);

        setupFullscreen();   /* immersive */
        updatePresentation();
        WindowTaskService.sync(this);
    }

    private boolean redirectDialog(long windowId) {
        if (this instanceof AwlDialogActivity || redirectingDialog) return false;
        if (!launchDialog(this, windowId, getIntent().getStringExtra("title"))) return false;
        redirectingDialog = true;
        // Compatibility for explicit old Activity intents or a relation
        // added after first map. Normal launches classify before any Activity
        // is created and do not take this fallback path.
        finish();
        overridePendingTransition(0, 0);
        return true;
    }

    /** Start a protocol transient directly in its parent task (main thread). */
    static boolean launchDialog(android.content.Context context, long windowId, String title) {
        AwlClient.Presentation p = AwlClient.presentation(windowId);
        if (!p.dialog) return false;
        AwlWindowActivity existing = LIVE.get(windowId);
        if (existing instanceof AwlDialogActivity && !existing.isFinishing()) {
            android.app.ActivityManager am = context.getSystemService(android.app.ActivityManager.class);
            if (am != null) for (android.app.ActivityManager.AppTask task : am.getAppTasks()) {
                if (task.getTaskInfo().taskId == existing.getTaskId()) {
                    task.moveToFront();
                    Awl.hostArrived(windowId);
                    return true;
                }
            }
        }
        android.content.Intent next = new android.content.Intent(context, AwlDialogActivity.class)
                .setData(android.net.Uri.parse("anland://dialog/" + windowId))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION)
                .putExtra("id", windowId).putExtra("title", title);
        Bundle options = android.app.ActivityOptions.makeCustomAnimation(context, 0, 0).toBundle();
        AwlWindowActivity parent = LIVE.get(p.parent);
        if (parent != null && !parent.isFinishing()) {
            parent.startActivity(next, options);
        } else {
            boolean launched = false;
            android.app.ActivityManager am = context.getSystemService(android.app.ActivityManager.class);
            if (am != null && p.parent != 0) {
                for (android.app.ActivityManager.AppTask task : am.getAppTasks()) {
                    android.content.Intent base = task.getTaskInfo().baseIntent;
                    if (base != null && base.getLongExtra("id", -1) == p.parent) {
                        task.startActivity(context, next, options);
                        launched = true;
                        break;
                    }
                }
            }
            if (!launched) {
                if (!(context instanceof Activity))
                    next.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(next, options);
            }
        }
        return true;
    }

    private void updatePresentation() {
        if (id < 0 || isFinishing() || redirectDialog(id)) return;
        if (!(this instanceof AwlDialogActivity)) return;
        AwlClient.Presentation p = AwlClient.presentation(id);
        android.graphics.Point screen = new android.graphics.Point();
        getWindowManager().getDefaultDisplay().getSize(screen);
        int margin = Math.round(16 * getResources().getDisplayMetrics().density);
        int availableW = Math.max(1, screen.x - margin * 2);
        int availableH = Math.max(1, screen.y - margin * 2);
        int w = p.width > 0 ? p.width : availableW;
        int h = p.height > 0 ? p.height : availableH;
        double scale = Math.min(1.0, Math.min((double)availableW/w, (double)availableH/h));
        getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        getWindow().setDimAmount(.25f);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        getWindow().setLayout(Math.max(1, (int)(w*scale)), Math.max(1, (int)(h*scale)));
        getWindow().setGravity(android.view.Gravity.CENTER);
    }

    @Override public void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config);
        updatePresentation();
    }

    /* Immersive fullscreen: hide status bar + navigation bar, swipe-revealed
     * as transient overlays, extend into the display cutout area. */
    private void setupFullscreen() {
        if (this instanceof AwlDialogActivity) return;
        if (android.os.Build.VERSION.SDK_INT < 30) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            return;
        }
        android.view.WindowInsetsController ic = getWindow().getInsetsController();
        if (ic != null) {
            ic.hide(android.view.WindowInsets.Type.statusBars()
                  | android.view.WindowInsets.Type.navigationBars());
            ic.setSystemBarsBehavior(
                android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
        getWindow().getAttributes().layoutInDisplayCutoutMode =
            android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
    }

    /** Recents entry: last known client title + last fetched toplevel icon.
     *  TaskDescription is atomic — every label update must re-carry the icon
     *  or it is silently dropped. */
    private void applyTaskDescription() {
        if (this instanceof AwlDialogActivity) return; // retain the parent's task identity
        if (!taskIdentityReady) return;
        String label = TaskIdentity.label(taskDesktopName, taskTitle);
        android.app.ActivityManager.TaskDescription td = taskIcon != null
                ? new android.app.ActivityManager.TaskDescription(label, taskIcon)
                : new android.app.ActivityManager.TaskDescription(label);
        setTaskDescription(td);
        // Direct daemon launches and process recreation have no prefetched
        // registry entry. The manifest initially excludes the document from
        // Recents; publish it only after its first identity snapshot is ready.
        // This prevents a launcher from caching the host icon in the gap before
        // our asynchronous load, without delaying rendering or stealing focus.
        if (!taskPublished) {
            android.app.ActivityManager manager = getSystemService(android.app.ActivityManager.class);
            if (manager != null) for (android.app.ActivityManager.AppTask task : manager.getAppTasks()) {
                try {
                    if (task.getTaskInfo().taskId == getTaskId()) {
                        task.setExcludeFromRecents(false);
                        taskPublished = true;
                        break;
                    }
                } catch (IllegalArgumentException gone) { /* task removed during snapshot */ }
            }
        }
    }

    /** Pull the daemon's stored toplevel icon (xdg-toplevel-icon-v1 pixels)
     *  off the UI thread, then swap the task description. */
    private void applyTaskIconAsync() {
        if (iconFetchRunning) { iconFetchPending = true; return; }
        iconFetchRunning = true;
        final long fid = id;
        final int request = iconRequest.incrementAndGet();
        final String requestedTitle = taskTitle;
        new Thread(() -> {
            WindowTaskIdentity loaded = null;
            try { loaded = WindowTaskIdentity.load(fid); }
            catch (RuntimeException e) { Log.w(TAG, "task identity unavailable", e); }
            final WindowTaskIdentity identity = loaded;
            final AwlClient.DesktopInfo desktop = identity != null ? identity.desktop : null;
            final android.graphics.Bitmap fb = identity != null ? identity.icon : null;
            runOnUiThread(() -> {
                iconFetchRunning = false;
                if (isFinishing() || isDestroyed()) return;
                if (request == iconRequest.get() && fid == id) {
                    taskIdentityReady = true; // absent client icons retain the normal host fallback
                    // Refresh title changes made before attachment / while
                    // stopped, without overwriting a newer control event.
                    if (identity != null && java.util.Objects.equals(taskTitle, requestedTitle))
                        taskTitle = identity.title;
                    if (desktop != null) {
                        if (taskAppId != null && !taskAppId.equals(desktop.appId)) taskIcon = null;
                        taskAppId = desktop.appId;
                        taskDesktopName = desktop.name;
                    }
                    // A failed fetch must not erase a valid icon; C_ICON reset
                    // explicitly clears it. Coalesce rapid title/icon events.
                    if (fb != null) taskIcon = fb;
                    applyTaskDescription();
                }
                if (iconFetchPending) {
                    iconFetchPending = false;
                    applyTaskIconAsync();
                }
            });
        }, "awl-icon").start();
    }

    /** Report the Surface to the daemon (shared by first attach / resume re-attach).
     *  rc != 0 (service gone / window missing / attach failed / uid rejected)
     *  → exit AND drop the Recents card: a rejected attach is exactly the
     *  "window id no longer exists" case (client quit while this host was
     *  paused or its process dead, daemon restarted, stale card tapped) —
     *  no placeholder instance and no dead card left behind */
    private void sendSurface(SurfaceHolder holder, int w, int h) {
        // PAUSE is asynchronous. A fresh generation makes a delayed pause of
        // the previous attachment harmless even when this Activity is reused.
        int rc = AwlClient.surface(id, w, h, holder.getSurface(), ctrl, host, ++surfaceGeneration);
        attached = rc == 0;
        lastW = w;
        lastH = h;
        if (!attached) {
            Log.e(TAG, "win " + id + " surface binder rc=" + rc + " -> finish + remove task");
            finishAndDropTask();
            return;
        }
        // onNewIntent can cause pause/resume WITHOUT a window-focus callback.
        // Detach sent keyboard/text-input leave, so restore the actual Android
        // focus as part of attachment; rendering alone cannot restore input.
        AwlClient.focus(id, hasWindowFocus(), host, surfaceGeneration);
        applyTaskIconAsync();   /* the daemon may already hold an icon (re-attach / set before map) */
    }

    /* ---- Clipboard bridge ----
     * Android→wl: read while focused (background reads restricted on
     * Android 10+) → T_CLIPBOARD push; three entry points — listener +
     * focus-gained + onResume (the latter two cover "listener missed the
     * change" cases such as a daemon restart). Plain-text bridge only. */

    private final ClipboardManager.OnPrimaryClipChangedListener clipListener =
            new ClipboardManager.OnPrimaryClipChangedListener() {
        @Override
        public void onPrimaryClipChanged() {
            pushClipboard();
        }
    };

    /** Read the current clipboard as text; null when absent / not coercible
     *  to text / read restricted. Any item is coerced (coerceToText handles
     *  HTML/URI/Intent items). */
    private String readClipText() {
        if (clipMgr == null) return null;
        try {
            ClipData cd = clipMgr.getPrimaryClip();
            if (cd == null || cd.getItemCount() == 0) return null;
            ClipDescription d = cd.getDescription();
            if (d != null && d.hasMimeType("image/*") && !d.hasMimeType("text/*"))
                return null;   /* pure image clip: nothing to bridge */
            CharSequence cs = cd.getItemAt(0).coerceToText(this);
            if (cs == null || cs.length() == 0) return null;
            return cs.toString();
        } catch (SecurityException e) {
            Log.w(TAG, "win " + id + ": clipboard read denied");
            return null;
        }
    }

    private void pushClipboard() {
        if (!hasWindowFocus()) return;   /* background reads restricted; re-pushed on focus-gained */
        String t = readClipText();
        if (t == null) return;
        if (t.length() > 256 * 1024) t = t.substring(0, 256 * 1024);
        if (t.equals(sLastClipWritten) || t.equals(sLastClipPushed)) return;
        sLastClipPushed = t;
        AwlClient.clipboard(id, t);
    }

    /** wl client set_selection → write the Android clipboard (UI thread; sent by daemon ctrl) */
    private void writeClipboard(String t) {
        sLastClipWritten = t;   /* record first: setPrimaryClip fires our own listener */
        if (clipMgr == null) return;
        if (t.isEmpty()) clipMgr.clearPrimaryClip();
        else clipMgr.setPrimaryClip(ClipData.newPlainText("anland", t));
    }

    /* ---- Lifecycle → control-channel reports ---- */

    @Override
    protected void onStart() {
        super.onStart();
        fireHost((cbs, win, act) -> cbs.onHostStart(win, act));
    }

    @Override
    protected void onStop() {
        detachVisibleSurface();
        if (windowSubscribed) {
            Awl.release();
            Awl.unregisterCallback(winEvents);
            windowSubscribed = false;
        }
        fireHost((cbs, win, act) -> cbs.onHostStop(win, act));
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        setupFullscreen();   /* the system may reset immersive mode */
        if (!windowSubscribed && !isFinishing()) {
            Awl.registerCallback(winEvents);
            Awl.acquire();
            windowSubscribed = true;
        }
        if (clipMgr != null)
            clipMgr.addPrimaryClipChangedListener(clipListener);
        /* pause without stop (quick round-trip) → the surface survived and
         * surfaceChanged won't fire again: re-sending SURFACE here is the
         * re-attach. If the surface was rebuilt, surfaceChanged handles it */
        if (!attached && lastW > 0 && sv != null
                && sv.getHolder().getSurface() != null
                && sv.getHolder().getSurface().isValid())
            sendSurface(sv.getHolder(), lastW, lastH);
        else if (attached)
            AwlClient.focus(id, hasWindowFocus(), host, surfaceGeneration);
        fireHost((cbs, win, act) -> cbs.onHostResume(win, act));
        Log.i(TAG, "win " + id + " RESUME");
        /* capture state is daemon-owned: the SURFACE re-attach re-pushes
         * C_CAPTURE (a persistent constraint survives the pause) */
    }

    @Override
    protected void onPause() {
        fireHost((cbs, win, act) -> cbs.onHostPause(win, act));
        if (clipMgr != null)
            clipMgr.removePrimaryClipChangedListener(clipListener);
        setPointerCaptureMode(CAPTURE_NONE, 0, 0, 0, 0);   /* release + local mode reset (the daemon mirror survives; re-pushed on re-attach) */
        if (id >= 0) endPadStream();   /* the touchpad pointer stream may be interrupted by lifecycle: make up leave/button releases */
        /* A translucent child dialog pauses its parent without hiding it.
         * Keep that visible GPU surface alive; onStop is the visibility
         * boundary. Keyboard focus still leaves immediately. */
        if (attached) AwlClient.focus(id, false, host, surfaceGeneration);
        Log.i(TAG, "win " + id + " PAUSE");
        super.onPause();
    }

    private void detachVisibleSurface() {
        if (!attached) return;
        AwlClient.pause(id, host, surfaceGeneration);
        attached = false;
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (id < 0) return;   /* awaiting: nothing to report focus for */
        AwlClient.focus(id, hasFocus, host, surfaceGeneration);   /* focus notifies the wayland client (configure ACTIVATED) */
        if (hasFocus) {
            tryShowIme();        /* C_IME_SHOW may arrive before focus does (input state kept across re-attach) */
            pushClipboard();     /* daemon restart / listener missed the change → re-push while focused */
            applyPointerCapture();   /* the system broke the capture silently on focus loss — re-request (mode unchanged) */
        }
        Log.i(TAG, "win " + id + " focus=" + hasFocus);
    }

    /* ---- IME bridge ----
     * hidden-EditText display side: 1x1 invisible, disabled by default;
     * enable+focus+show when needed. The InputConnection below bridges the
     * full table (text → text-input protocol; queries ← state cache). */

    private void initHiddenInput() {
        hiddenInput = new EditText(this) {
            @Override
            public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
                super.onCreateInputConnection(outAttrs);
                int sel = editorSelStart();
                outAttrs.initialSelStart = sel;
                outAttrs.initialSelEnd = editorSelEnd();
                outAttrs.initialCapsMode = TextUtils.getCapsMode(editorText(), sel,
                        TextUtils.CAP_MODE_SENTENCES | TextUtils.CAP_MODE_WORDS
                        | TextUtils.CAP_MODE_CHARACTERS);
                return new WlInputConnection(this);
            }
        };
        hiddenInput.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        hiddenInput.setCursorVisible(false);
        hiddenInput.setAlpha(0f);
        hiddenInput.setEnabled(false);
        hiddenInput.setFocusable(false);
        hiddenInput.setFocusableInTouchMode(false);
        hiddenInput.setClickable(false);
        hiddenInput.setLongClickable(false);
        hiddenInput.setImeOptions(EditorInfo.IME_ACTION_GO
                | EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | EditorInfo.IME_FLAG_NO_FULLSCREEN);
        hiddenInput.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_NORMAL);
    }

    /** zwp_text_input content hint/purpose → Android InputType (keyboard layout / segmentation style) */
    private int imeInputType() {
        int t;
        switch (imePurpose) {
            case 2: case 3: case 10: case 11: case 12:   /* digits number date time datetime */
                t = InputType.TYPE_CLASS_NUMBER; break;
            case 9:                                      /* pin */
                t = InputType.TYPE_CLASS_NUMBER
                        | InputType.TYPE_NUMBER_VARIATION_PASSWORD; break;
            case 4:
                t = InputType.TYPE_CLASS_PHONE; break;
            case 5:
                t = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI; break;
            case 6:
                t = InputType.TYPE_CLASS_TEXT
                        | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS; break;
            case 7:
                t = InputType.TYPE_CLASS_TEXT
                        | InputType.TYPE_TEXT_VARIATION_PERSON_NAME; break;
            case 8:                                      /* password */
                t = InputType.TYPE_CLASS_TEXT
                        | InputType.TYPE_TEXT_VARIATION_PASSWORD; break;
            default:
                t = InputType.TYPE_CLASS_TEXT; break;
        }
        if ((imeHint & 0x200) != 0) t |= InputType.TYPE_TEXT_FLAG_MULTI_LINE;
        if ((imeHint & 0x1) != 0) t |= InputType.TYPE_TEXT_FLAG_AUTO_COMPLETE;
        if ((imeHint & 0x2) != 0) t |= InputType.TYPE_TEXT_FLAG_AUTO_CORRECT;
        if ((imeHint & 0x4) != 0) t |= InputType.TYPE_TEXT_FLAG_CAP_SENTENCES;
        if ((imeHint & 0x10) != 0) t |= InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS;
        if ((imeHint & 0x40) != 0
                && (t & InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT
                && imePurpose != 8)
            t = (t & ~InputType.TYPE_MASK_VARIATION)
                | InputType.TYPE_TEXT_VARIATION_PASSWORD;   /* hidden_text */
        return t;
    }

    /** IME display mode: 0 = inset (surface yields), 1 = overlay (floating above) */
    private boolean imeOverlayMode() {
        return getSharedPreferences("awl", MODE_PRIVATE).getInt("ime_mode", 0) != 0;
    }

    private void applyContentInsets(WindowInsets insets) {
        if (android.os.Build.VERSION.SDK_INT < 30) return;
        if (root.getWidth() <= 0 || root.getHeight() <= 0) return;
        // Immersive bars are transient overlays, not a second resize target.
        // The navigation strip can appear just before the IME and disappear
        // just after it; counting it separately made the viewport jump twice.
        // Caption bars and real cutouts still obstruct content; floating and
        // multi-window hosts also retain their ordinary system-bar insets.
        int bars = this instanceof AwlDialogActivity || isInMultiWindowMode()
                ? WindowInsets.Type.systemBars() : WindowInsets.Type.captionBar();
        android.graphics.Insets safe = insets.getInsets(WindowInsets.Type.displayCutout() | bars);
        int[] margins = WindowSafeArea.contentMargins(
                new int[]{safe.left, safe.top, safe.right, safe.bottom},
                this instanceof AwlDialogActivity ? 0 : insets.getInsets(WindowInsets.Type.ime()).bottom,
                imeOverlayMode());
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) sv.getLayoutParams();
        if (lp.leftMargin == margins[0] && lp.topMargin == margins[1]
                && lp.rightMargin == margins[2] && lp.bottomMargin == margins[3]) return;
        lp.leftMargin = margins[0];
        lp.topMargin = margins[1];
        lp.rightMargin = margins[2];
        lp.bottomMargin = margins[3];
        // Resize the actual surface: Wayland reflows, and input remains local
        // to that same surface. Do not merely crop pixels after rendering.
        sv.setLayoutParams(lp);
    }

    private void onImeShow(int hint, int purpose) {
        imeWanted = true;
        if (hint != imeHint || purpose != imePurpose) {
            imeHint = hint;
            imePurpose = purpose;
            hiddenInput.setInputType(imeInputType());
        }
        tryShowIme();
    }

    /** Focus may arrive late (surfaceChanged precedes onWindowFocusChanged) → show as soon as ready */
    private void tryShowIme() {
        if (!imeWanted || !hasWindowFocus() || imm == null) {
            Log.i(TAG, "win " + id + ": ime show deferred (wanted=" + imeWanted
                    + " focus=" + hasWindowFocus() + ")");
            return;
        }
        hiddenInput.setEnabled(true);
        hiddenInput.setFocusable(true);
        hiddenInput.setFocusableInTouchMode(true);
        boolean focused = hiddenInput.requestFocus();
        /* explicit (flags=0), not SHOW_IMPLICIT: the wl client asked for the
         * panel; InputMethodService.onShowInputRequested refuses implicit
         * requests while a hard keyboard is attached. */
        boolean shown = imm.showSoftInput(hiddenInput, 0);
        Log.i(TAG, "win " + id + ": ime show requestFocus=" + focused + " showSoftInput=" + shown
                + " type=0x" + Integer.toHexString(hiddenInput.getInputType()));
    }

    private void onImeHide() {
        Log.i(TAG, "win " + id + ": ime hide");
        imeWanted = false;
        if (imm != null)
            imm.hideSoftInputFromWindow(hiddenInput.getWindowToken(), 0);
        if (hiddenInput.isEnabled()) {
            hiddenInput.clearFocus();
            hiddenInput.setFocusable(false);
            hiddenInput.setFocusableInTouchMode(false);
            hiddenInput.setEnabled(false);
        }
    }

    /** Editor state pushed by the daemon (the client's set_surrounding/cursor_rect take effect with the commit) */
    private void onImeState(String text, int curB, int ancB, int hint, int purpose,
                            int cx, int cy, int cw, int ch, int flags) {
        boolean typeChanged = hint != imeHint || purpose != imePurpose;
        String nextText = text == null ? "" : text;
        if (!nextText.equals(surText)) markedStart = markedEnd = -1;
        surText = nextText;
        surCursor = Math.min(surText.length(), byteToChar(surText, curB));
        surAnchor = Math.min(surText.length(), byteToChar(surText, ancB));
        imeHint = hint;
        imePurpose = purpose;
        imeRect[0] = cx; imeRect[1] = cy; imeRect[2] = cw; imeRect[3] = ch;
        if ((flags & STATE_RESET) != 0) {
            compText = "";
            compCursor = 0;
            markedStart = markedEnd = -1;
            if (imm != null) imm.restartInput(hiddenInput);
            return;
        }
        if (typeChanged) hiddenInput.setInputType(imeInputType());
        notifyImeState();
    }

    /** Push selection/composing region/cursor anchor (IME candidate window follows the cursor, context stays in sync) */
    private void notifyImeState() {
        if (imm == null || imeBatchDepth > 0) return;
        int sel = editorSelStart();
        int candStart = -1, candEnd = -1;
        int compAt = Math.min(surCursor, surText.length());
        if (!compText.isEmpty()) {
            candStart = compAt;
            candEnd = compAt + compText.length();
        }
        if (markedStart >= 0) {
            candStart = markedStart;
            candEnd = markedEnd;
        }
        imm.updateSelection(hiddenInput, sel, editorSelEnd(), candStart, candEnd);
        if (imeRect[2] > 0 && imeRect[3] > 0 && sv != null) {
            /* Positional parameters require a local→screen matrix
             * (CursorAnchorInfo.Builder.build throws IllegalArgumentException
             * otherwise). The daemon's rect is in surface-view pixels; the
             * IME wants screen coordinates. */
            int[] loc = new int[2];
            sv.getLocationOnScreen(loc);
            android.graphics.Matrix m = new android.graphics.Matrix();
            m.setTranslate(loc[0], loc[1]);
            CursorAnchorInfo.Builder b = new CursorAnchorInfo.Builder()
                    .setMatrix(m)
                    .setInsertionMarkerLocation(imeRect[0], imeRect[1],
                                                imeRect[1] + imeRect[3],
                                                imeRect[1] + imeRect[3],
                                                CursorAnchorInfo.FLAG_HAS_VISIBLE_REGION);
            if (!compText.isEmpty()) {
                b.setComposingText(compAt, compText);
            }
            b.setSelectionRange(sel, editorSelEnd());
            try {
                imm.updateCursorAnchorInfo(hiddenInput, b.build());
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "win " + id + ": cursor anchor info rejected: " + e.getMessage());
            }
        }
    }

    private void clearComposing() {
        compText = "";
        compCursor = 0;
        markedStart = markedEnd = -1;
        notifyImeState();
    }

    /* ---- Virtual editor (IME's view: surText + compText inserted at surCursor) ---- */

    private String editorText() {
        int c = Math.min(surCursor, surText.length());
        if (compText.isEmpty()) return surText;
        return surText.substring(0, c) + compText + surText.substring(c);
    }

    private int editorSelStart() {
        return Math.min(surCursor, surText.length()) + compCursor;
    }

    private int editorSelEnd() {
        return compText.isEmpty() ? Math.min(surAnchor, surText.length()) : editorSelStart();
    }

    /* setComposingRegion changes metadata, NOT text or selection. Keep a
     * marked committed range until the IME actually replaces it. Deleting
     * it eagerly makes re-segmentation + finishComposingText erase a word. */
    private int markedStart = -1, markedEnd = -1;
    private int imeBatchDepth;

    /** Editor index → client surrounding index (subtract the composing-length offset) */
    private int toSurroundingIndex(int editorIdx) {
        int c = Math.min(surCursor, surText.length());
        if (editorIdx <= c) return editorIdx;
        return Math.max(c, editorIdx - compText.length());
    }

    /* ---- UTF-16 ↔ UTF-8 conversion (protocol byte offsets ↔ Java char indices) ---- */

    private static int utf8Len(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            n += cp <= 0x7F ? 1 : cp <= 0x7FF ? 2 : cp <= 0xFFFF ? 3 : 4;
            i += Character.charCount(cp);
        }
        return n;
    }

    /** Byte offset → char index (stops on a code point boundary) */
    private static int byteToChar(String s, int byteOff) {
        if (byteOff <= 0) return 0;
        int chars = 0, bytes = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            int b = cp <= 0x7F ? 1 : cp <= 0x7FF ? 2 : cp <= 0xFFFF ? 3 : 4;
            if (bytes + b > byteOff) return chars;
            bytes += b;
            int c = Character.charCount(cp);
            chars += c;
            i += c;
        }
        return chars;
    }

    /** Android positions are UTF-16 offsets: positive relative to end - 1,
     * non-positive relative to start. Wayland wants UTF-8 byte offsets. */
    private static int preeditCursorBytes(String text, int newCp) {
        return utf8Len(text.substring(0, composingCursorChars(text, newCp)));
    }

    /** Preedit cursor char offset (for the local mirror) */
    private static int composingCursorChars(String text, int newCp) {
        long pos = newCp > 0 ? (long) text.length() + newCp - 1 : newCp;
        return snap(text, (int) Math.max(0, Math.min(text.length(), pos)));
    }

    /* Step n code points backward / forward from idx (surrogate-pair safe) */
    private static int cpBack(String s, int idx, int n) {
        for (int i = 0; i < n && idx > 0; i++)
            idx = snapBack(s, idx - 1);
        return idx;
    }
    private static int cpFwd(String s, int idx, int n) {
        for (int i = 0; i < n && idx < s.length(); i++)
            idx = snap(s, idx + Character.charCount(s.codePointAt(idx)));
        return idx;
    }

    /** Snap an index to a code point boundary (forward: absorb the second half of a surrogate pair) */
    private static int snap(String s, int idx) {
        if (idx <= 0) return 0;
        if (idx >= s.length()) return s.length();
        if (Character.isHighSurrogate(s.charAt(idx - 1))
                && Character.isLowSurrogate(s.charAt(idx)))
            return idx + 1;
        return idx;
    }

    /** Snap an index to a code point boundary (backward: retreat to the first half of a surrogate pair) */
    private static int snapBack(String s, int idx) {
        if (idx <= 0) return 0;
        if (idx >= s.length()) return s.length();
        if (Character.isHighSurrogate(s.charAt(idx - 1))
                && Character.isLowSurrogate(s.charAt(idx)))
            return idx - 1;
        return idx;
    }

    /* chars / code points around the cursor → bytes (converted on the client's surrounding) */
    private int bytesBefore(int units) {
        if (units <= 0) return 0;
        int start = snapBack(surText, Math.max(0, surCursor - units));
        return utf8Len(surText.substring(start, surCursor));
    }
    private int bytesAfter(int units) {
        if (units <= 0) return 0;
        int end = snap(surText, Math.min(surText.length(), surCursor + units));
        return utf8Len(surText.substring(surCursor, end));
    }
    private int bytesBeforeCp(int cps) {
        if (cps <= 0) return 0;
        int i = surCursor, cnt = 0;
        while (i > 0 && cnt < cps) {
            i = snapBack(surText, i - 1);
            cnt++;
        }
        return utf8Len(surText.substring(i, surCursor));
    }
    private int bytesAfterCp(int cps) {
        if (cps <= 0) return 0;
        int i = surCursor, cnt = 0;
        while (i < surText.length() && cnt < cps) {
            i = snap(surText, i + Character.charCount(surText.codePointAt(i)));
            cnt++;
        }
        return utf8Len(surText.substring(surCursor, i));
    }

    /**
     * Full-table InputConnection bridge (Android IME ↔ binder passthrough to
     * the text-input protocol). Output: commitText/setComposingText/
     * deleteSurrounding… → ime ONEWAY; queries: getTextBeforeCursor/
     * getCursorCapsMode/… ← state cache (segmentation/prediction context).
     */
    private class WlInputConnection extends BaseInputConnection {
        WlInputConnection(View target) {
            super(target, false);
        }

        @Override public boolean beginBatchEdit() { imeBatchDepth++; return true; }
        @Override public boolean endBatchEdit() {
            if (imeBatchDepth > 0) imeBatchDepth--;
            if (imeBatchDepth == 0) notifyImeState();
            return imeBatchDepth > 0;
        }

        private boolean replaceMarked(String text, boolean preedit) {
            if (markedStart < 0) return false;
            int c = Math.min(surCursor, surText.length());
            int a = Math.min(markedStart, surText.length());
            int b = Math.min(markedEnd, surText.length());
            // Text-input-v3 can replace around its cursor, not a disjoint
            // remote selection. Never claim an unrelated deletion succeeded.
            if (a > c || b < c) return false;
            AwlClient.ime(id, preedit ? AwlClient.IME_REPLACE_PREEDIT : AwlClient.IME_REPLACE,
                    utf8Len(surText.substring(a, c)), utf8Len(surText.substring(c, b)), text);
            surText = surText.substring(0, a) + surText.substring(b);
            surCursor = surAnchor = a;
            markedStart = markedEnd = -1;
            return true;
        }

        /* --- text output --- */

        @Override
        public boolean commitText(CharSequence text, int newCursorPosition) {
            String t = text == null ? "" : text.toString();
            boolean marked = markedStart >= 0;
            if (marked && !replaceMarked(t, false)) return false;
            if (!marked) AwlClient.ime(id, AwlClient.IME_COMMIT, 0, 0, t);
            /* keep the virtual editor in step: without this a commit-only
             * session (English typing) leaves surText stale and the next
             * backspace converts to a zero-byte delete (client-side no-op) */
            int c = Math.min(surCursor, surText.length());
            int a = Math.min(c, surAnchor), b = Math.max(c, surAnchor);
            surText = surText.substring(0, a) + t + surText.substring(b);
            surCursor = surAnchor = a + t.length();
            clearComposing();
            return true;
        }

        @Override
        public boolean setComposingText(CharSequence text, int newCursorPosition) {
            String t = text == null ? "" : text.toString();
            boolean marked = markedStart >= 0;
            if (marked && !replaceMarked(t, true)) return false;
            int cb = preeditCursorBytes(t, newCursorPosition);
            if (!marked || cb != utf8Len(t))
                AwlClient.ime(id, AwlClient.IME_PREEDIT, cb, cb, t);
            if (compText.isEmpty() && surCursor != surAnchor) {
                int a = Math.min(surCursor, surAnchor), b = Math.max(surCursor, surAnchor);
                surText = surText.substring(0, a) + surText.substring(b);
                surCursor = surAnchor = a;
            }
            compText = t;
            compCursor = composingCursorChars(t, newCursorPosition);
            notifyImeState();
            return true;
        }

        @Override
        public boolean finishComposingText() {
            // finish removes composing spans; it MUST preserve the text.
            // Empty Wayland preedit cancels it and is not equivalent.
            if (!compText.isEmpty()) return commitText(compText, 1);
            markedStart = markedEnd = -1;
            notifyImeState();
            return true;
        }

        @Override
        public boolean deleteSurroundingText(int beforeLength, int afterLength) {
            deleteAround(beforeLength, afterLength, false);
            return true;
        }

        @Override
        public boolean deleteSurroundingTextInCodePoints(int beforeLength, int afterLength) {
            deleteAround(beforeLength, afterLength, true);
            return true;
        }

        /* The editor must report the new selection after a successful delete
         * (imm.updateSelection from notifyImeState). LatinIME re-issues a
         * delete that produced no selection update — returning true while the
         * state cache still shows the pre-delete editor makes it delete again,
         * each retry eating another char until the field is empty. */
        private void deleteAround(int beforeChars, int afterChars, boolean codePoints) {
            AwlClient.ime(id, AwlClient.IME_DELETE,
                    codePoints ? bytesBeforeCp(beforeChars) : bytesBefore(beforeChars),
                    codePoints ? bytesAfterCp(afterChars) : bytesAfter(afterChars), "");
            int c = Math.min(surCursor, surText.length());
            int a = codePoints ? cpBack(surText, c, beforeChars)
                               : snapBack(surText, Math.max(0, c - beforeChars));
            int b = codePoints ? cpFwd(surText, c, afterChars)
                               : snap(surText, Math.min(surText.length(), c + afterChars));
            if (b > a) {
                surText = surText.substring(0, a) + surText.substring(b);
                surCursor = surAnchor = a;
                notifyImeState();
            }
        }

        @Override
        public boolean commitCompletion(CompletionInfo text) {
            if (text != null) return commitText(text.getText(), 1);
            return true;
        }

        @Override
        public boolean commitCorrection(CorrectionInfo correctionInfo) {
            /* advisory only: the IME already delivered the corrected text via
             * commitText (LatinIME sends both on autocorrect/auto-capitalize)
             * — re-applying it here would duplicate the word. TextView also
             * just records the CorrectionInfo span, no text change. */
            return true;
        }

        @Override
        public boolean setComposingRegion(int start, int end) {
            String et = editorText();
            int a = snap(et, Math.max(0, Math.min(et.length(), Math.min(start, end))));
            int b = snap(et, Math.max(0, Math.min(et.length(), Math.max(start, end))));
            if (!compText.isEmpty() && a == surCursor && b == a + compText.length())
                return true;
            if (!compText.isEmpty()) finishComposingText();
            markedStart = a < b ? a : -1;
            markedEnd = a < b ? b : -1;
            // No updateSelection here: the selection has not moved. Some
            // keyboards re-segment on every update, producing a feedback loop.
            return true;
        }

        @Override
        public boolean setSelection(int start, int end) {
            /* v1 cursor_position; v3 has no matching event (the client's state push self-heals) */
            String et = editorText();
            int a = toSurroundingIndex(snap(et, Math.max(0, Math.min(start, end))));
            int b = toSurroundingIndex(snap(et, Math.min(et.length(), Math.max(start, end))));
            AwlClient.ime(id, AwlClient.IME_CURSOR, a, b, "");
            surCursor = a;
            surAnchor = b;
            notifyImeState();
            return true;
        }

        @Override
        public boolean replaceText(int start, int end, CharSequence text,
                                   int newCursorPosition, TextAttribute textAttribute) {
            finishComposingText();
            String et = editorText();
            int a = snap(et, Math.max(0, Math.min(et.length(), Math.min(start, end))));
            int b = snap(et, Math.max(0, Math.min(et.length(), Math.max(start, end))));
            int c = Math.min(surCursor, surText.length());
            if (a > c || b < c) return false;
            markedStart = a; markedEnd = b;
            return commitText(text, newCursorPosition);
        }

        /* --- context queries (data source for IME segmentation/prediction; answered locally) --- */

        @Override
        public CharSequence getTextBeforeCursor(int length, int flags) {
            String et = editorText();
            int sel = Math.min(editorSelStart(), editorSelEnd());
            int start = snapBack(et, Math.max(0, sel - Math.max(0, length)));
            return et.subSequence(start, sel);
        }

        @Override
        public CharSequence getTextAfterCursor(int length, int flags) {
            String et = editorText();
            int sel = Math.max(editorSelStart(), editorSelEnd());
            int end = snap(et, Math.min(et.length(), sel + Math.max(0, length)));
            return et.subSequence(sel, end);
        }

        @Override
        public CharSequence getSelectedText(int flags) {
            if (surCursor == surAnchor || surText.isEmpty()) return null;
            int a = Math.min(surCursor, surAnchor), b = Math.max(surCursor, surAnchor);
            return surText.substring(a, b);
        }

        @Override
        @android.annotation.TargetApi(31)
        public SurroundingText getSurroundingText(int beforeLength, int afterLength, int flags) {
            String et = editorText();
            int sel = editorSelStart(), anchor = editorSelEnd();
            int startPos = snapBack(et, Math.max(0, Math.min(sel, anchor) - Math.max(0, beforeLength)));
            int endPos = snap(et, Math.min(et.length(), Math.max(sel, anchor) + Math.max(0, afterLength)));
            return new SurroundingText(et.substring(startPos, endPos),
                                       sel - startPos, anchor - startPos, startPos);
        }

        @Override
        public ExtractedText getExtractedText(ExtractedTextRequest request, int flags) {
            ExtractedText t = new ExtractedText();
            t.text = editorText();
            t.startOffset = 0;
            t.selectionStart = editorSelStart();
            t.selectionEnd = editorSelEnd();
            return t;
        }

        @Override
        public int getCursorCapsMode(int reqModes) {
            return TextUtils.getCapsMode(editorText(), editorSelStart(), reqModes);
        }

        /* --- misc --- */

        @Override
        public boolean requestCursorUpdates(int cursorUpdateMode) {
            return true;   /* every state push already does updateCursorAnchorInfo */
        }

        @Override
        public void closeConnection() {
            finishComposingText();
            imeBatchDepth = 0;
            super.closeConnection();
        }
    }

    /* ---- Input passthrough: Android dispatch callbacks → binder literal
     *      translation into wayland events ----
     * Routing authority = Android (an event reaching this Activity belongs to
     * this window); the daemon keeps zero routing state.
     * Event type values = the daemon's AWL_IN_* (do not change); BTN_* = evdev codes. */

    private static final int PTR_ENTER = 1, PTR_LEAVE = 2, PTR_MOTION = 3,
            PTR_BUTTON = 4, PTR_AXIS = 5, PTR_REL = 6,
            KEY = 9, TOUCH_DOWN = 11, TOUCH_MOTION = 12, TOUCH_UP = 13,
            TOUCH_CANCEL = 14;

    /* ---- Pointer capture (zwp_pointer_constraints_v1 state sync) ----
     * The daemon only mirrors the client's constraint state (C_CAPTURE:
     * mode + confine rect in view px); activation and limiting are local:
     *   NONE    — normal mode: absolute positions, rel = absolute-position diff
     *   CONFINE — requestPointerCapture + a virtual clamped position
     *   LOCK    — requestPointerCapture, relative-only (cursor frozen)
     * The system silently breaks capture on focus loss; onWindowFocusChanged
     * re-requests it. */
    private int captureMode = CAPTURE_NONE;
    private final int[] capRect = new int[4];   /* confine region, view px (zeros = whole window) */
    private float confinex, confiney;           /* virtual clamped position (CONFINE) */

    /* confine box = capRect ∩ live window (invalid/zero rect = whole window;
     * a degenerate intersection collapses to its lower edge) */
    private float confLoX() { return capRect[2] > 0 ? Math.max(0, capRect[0]) : 0f; }
    private float confLoY() { return capRect[3] > 0 ? Math.max(0, capRect[1]) : 0f; }
    private float confHiX() {
        return capRect[2] > 0 ? Math.max(confLoX(),
                Math.min(lastW - 1f, capRect[0] + capRect[2] - 1f)) : lastW - 1f;
    }
    private float confHiY() {
        return capRect[3] > 0 ? Math.max(confLoY(),
                Math.min(lastH - 1f, capRect[1] + capRect[3] - 1f)) : lastH - 1f;
    }

    /** New constraint state from the daemon (request / set_region / destroy):
     *  adopt the mode + rect, anchor the virtual position, (re)capture. */
    private void setPointerCaptureMode(int mode, int rx, int ry, int rw, int rh) {
        captureMode = mode;
        capRect[0] = rx; capRect[1] = ry; capRect[2] = rw; capRect[3] = rh;
        if (mode == CAPTURE_CONFINE) {
            /* anchor inside the box: the current pointer when known, else the box center */
            float ax = Float.isNaN(lastMouseX) ? (confLoX() + confHiX()) / 2f : lastMouseX;
            float ay = Float.isNaN(lastMouseY) ? (confLoY() + confHiY()) / 2f : lastMouseY;
            confinex = limitRange(confLoX(), confHiX(), ax);
            confiney = limitRange(confLoY(), confHiY(), ay);
        } else if (mode == CAPTURE_LOCK) {
            /* buttons anchor at the frozen position (window center if unknown) */
            if (Float.isNaN(lastMouseX)) lastMouseX = lastW / 2f;
            if (Float.isNaN(lastMouseY)) lastMouseY = lastH / 2f;
        }
        applyPointerCapture();
        Log.i(TAG, "win " + id + ": pointer capture mode=" + mode
                + " rect=" + rx + "," + ry + " " + rw + "x" + rh);
    }

    /** (Re-)request/release the system capture without touching the anchor
     *  (focus regained: capture broke silently, the mode is unchanged). */
    private void applyPointerCapture() {
        android.view.View v = getWindow().getDecorView();
        if (captureMode != CAPTURE_NONE) v.requestPointerCapture();
        else v.releasePointerCapture();
    }

    @Override
    public void onPointerCaptureChanged(boolean hasCapture) {
        super.onPointerCaptureChanged(hasCapture);
        Log.i(TAG, "win " + id + ": pointer capture " + (hasCapture ? "granted" : "lost")
                + " (mode=" + captureMode + ")");   /* log-only: the daemon owns the mode */
    }

    /* ---- Client cursor (wl_pointer.set_cursor) ----
     * While the wl client drives the cursor the daemon composites the
     * client's cursor image itself — the Android system pointer must not be
     * drawn on top of it. TYPE_NULL on the views under the pointer hides it;
     * restored when the daemon reports the client cursor gone. */
    private boolean ptrHidden;

    private void setPointerHidden(boolean hidden) {
        if (ptrHidden == hidden) return;
        ptrHidden = hidden;
        PointerIcon icon = hidden ? PointerIcon.getSystemIcon(this, PointerIcon.TYPE_NULL) : null;
        if (sv != null) sv.setPointerIcon(icon);
        if (root != null) root.setPointerIcon(icon);
        Log.i(TAG, "win " + id + ": android pointer " + (hidden ? "hidden (client cursor)" : "restored"));
    }

    /* ---- Idle inhibitor (zwp_idle_inhibit_manager_v1, C_KEEPON) ----
     * daemon-owned state (pure mirror sync): the FLAG_KEEP_SCREEN_ON window
     * flag is the whole implementation (honored only while visible = the
     * protocol's visible-surface semantics). */
    private void applyKeepOn(boolean on) {
        if (isFinishing()) return;
        if (on) getWindow().addFlags(
                android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(
                android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        Log.i(TAG, "win " + id + ": keep-screen-on " + (on ? "on (idle inhibitor)" : "off"));
    }

    private static final int BTN_LEFT = 0x110, BTN_RIGHT = 0x111,
            BTN_MIDDLE = 0x112, BTN_FORWARD = 0x115, BTN_BACK = 0x116;

    /* Mouse detection (pure source bits, no tool check) — some devices'
     * mouse hover events report TOOL_TYPE_FINGER; filtering by TOOL_TYPE_MOUSE
     * would drop them. */
    private static boolean isMouse(MotionEvent ev) {
        return (ev.getSource() & InputDevice.SOURCE_CLASS_POINTER) != 0
                && ev.getSource() != InputDevice.SOURCE_TOUCHSCREEN;
    }

    /* ---- Touchpad gestures (classified within the shared pointer stream) ----
     * System gesture classification (MotionEvent.getClassification), dispatched
     * BEFORE the virtual-mouse default so a classified gesture never falls
     * into pointer emulation:
     *   two-finger swipe (3) → scroll (AXIS_GESTURE_SCROLL_*_DISTANCE px)
     *   multi-finger swipe (4) / pinch (5) → touch passthrough
     *   rest (single contact / physical mouse) → virtual mouse */
    private static final int CLS_TWO_FINGER_SWIPE = 3;
    private static final int CLS_MULTI_FINGER_SWIPE = 4;
    private static final int CLS_PINCH = 5;

    private boolean padInWin;      /* pointer enter/leave pairing (protocol requires enter first) */

    private static boolean isTouchpad(MotionEvent ev) {
        return (ev.getSource() & InputDevice.SOURCE_CLASS_POINTER) != 0
                && ev.getSource() != InputDevice.SOURCE_TOUCHSCREEN
                && ev.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER;
    }

    /** Pointer stream teardown: release leftover buttons + leave (shared by
     *  classification switches to touch passthrough, gesture end, hover
     *  exit, and lifecycle interruptions). */
    private void endPadStream() {
        if (mouseSavedBS != 0) {
            int diff = mouseSavedBS;
            mouseSavedBS = 0;
            for (int[] b : PAD_BUTTON_MAP)
                if ((diff & b[0]) != 0)
                    AwlClient.input(id, PTR_BUTTON, b[1], lastMouseX, lastMouseY, 0, 0, 0);
        }
        if (padInWin) {
            padInWin = false;
            AwlClient.input(id, PTR_LEAVE, 0, 0, 0, 0, 0, 0);
        }
        lastMouseX = lastMouseY = Float.NaN;   /* no rel diff across a leave */
    }

    /** Make up the pointer enter at the mode-valid position (NONE = raw event
     *  position, CONFINE = the virtual clamped position, LOCK = the frozen
     *  anchor, window center when unknown) — protocol requires enter before
     *  motion/button/axis. */
    private void padEnter(MotionEvent ev) {
        padInWin = true;
        switch (captureMode) {
        case CAPTURE_LOCK:
            if (Float.isNaN(lastMouseX)) lastMouseX = lastW / 2f;
            if (Float.isNaN(lastMouseY)) lastMouseY = lastH / 2f;
            break;
        case CAPTURE_CONFINE:
            lastMouseX = confinex;
            lastMouseY = confiney;
            break;
        default:
            lastMouseX = ev.getX();
            lastMouseY = ev.getY();
            break;
        }
        AwlClient.input(id, PTR_ENTER, 0, lastMouseX, lastMouseY, 0, 0, 0);
    }

    /* ---- Main pointer handling (the virtual mouse for physical-mouse
     *      events AND single-finger touchpad contact) ----
     * dx/dy = this event's endpoint - the last delivered endpoint (diff over
     * the sent event stream) — the telescoping sum ≡ total displacement,
     * independent of how Android batches/coalesces samples.
     * Enter pairing: enters on the first event (a touchpad tap has no
     * preceding hover-enter) and stays after lift — leave happens only on
     * hover-exit / classification switches / teardown.
     * Buttons = buttonState diff (savedBS XOR current) — the touch stream
     * and the generic stream are two input paths sharing one differ. */
    private int mouseSavedBS;
    private float lastMouseX = Float.NaN, lastMouseY = Float.NaN;

    private float limitRange(float a, float b, float val){
        if(val<a) return a;
        if(val>b) return b;
        return val;
    }

    /* Three-mode dispatch (see the capture block above): NONE = absolute +
     * abs-diff rel; LOCK = AXIS_RELATIVE_* only, position frozen at the
     * capture point; CONFINE = AXIS_RELATIVE_* accumulated into the clamped
     * virtual position, sent as absolute motion. lastMouseX/Y always hold
     * the position the client believes the pointer is at. */
    private void handleMouseEvent(MotionEvent ev) {
        float ex = ev.getX(), ey = ev.getY();
        float dx, dy;
        boolean sendabs;
        float x, y;
        switch (captureMode) {
        case CAPTURE_LOCK:
            /* captured: only the relative axes are populated (sum over the
             * batch); no absolute motion — the client cursor stays frozen */
            dx = sumAxis(ev, MotionEvent.AXIS_RELATIVE_X);
            dy = sumAxis(ev, MotionEvent.AXIS_RELATIVE_Y);
            if (Float.isNaN(lastMouseX)) lastMouseX = lastW / 2f;
            if (Float.isNaN(lastMouseY)) lastMouseY = lastH / 2f;
            sendabs = false;
            x = lastMouseX; y = lastMouseY;
            break;
        case CAPTURE_CONFINE:
            dx = sumAxis(ev, MotionEvent.AXIS_RELATIVE_X);
            dy = sumAxis(ev, MotionEvent.AXIS_RELATIVE_Y);
            confinex = limitRange(confLoX(), confHiX(), confinex + dx);
            confiney = limitRange(confLoY(), confHiY(), confiney + dy);
            sendabs = true;
            x = lastMouseX = confinex;
            y = lastMouseY = confiney;
            break;
        default:   /* CAPTURE_NONE — uncaptured AXIS_RELATIVE_* is always 0 */
            dx = Float.isNaN(lastMouseX) ? 0f : ex - lastMouseX;
            dy = Float.isNaN(lastMouseY) ? 0f : ey - lastMouseY;
            sendabs = true;
            x = lastMouseX = ex;
            y = lastMouseY = ey;
            break;
        }
        if (!padInWin)
            padEnter(ev);   /* no prior hover (touchpad tap without hover) → make up the enter */
        if (dx != 0f || dy != 0f)
            AwlClient.input(id, PTR_REL, 0, dx, dy, 0, 0, 0);
        if (sendabs)
            AwlClient.input(id, PTR_MOTION, 0, x, y, 0, 0, 0);
        int bs = ev.getButtonState();
        int diff = mouseSavedBS ^ bs;
        if (diff != 0) {
            for (int[] b : PAD_BUTTON_MAP)
                if ((diff & b[0]) != 0)
                    AwlClient.input(id, PTR_BUTTON, b[1], x, y,
                            (bs & b[0]) != 0 ? 1 : 0, 0, 0);
            mouseSavedBS = bs;
        }
    }

    private static final int[][] PAD_BUTTON_MAP = {   /* {android bit, evdev code} */
        {MotionEvent.BUTTON_PRIMARY,   BTN_LEFT},
        {MotionEvent.BUTTON_SECONDARY, BTN_RIGHT},
        {MotionEvent.BUTTON_TERTIARY,  BTN_MIDDLE},
        {MotionEvent.BUTTON_BACK,      BTN_BACK},
        {MotionEvent.BUTTON_FORWARD,   BTN_FORWARD},
    };

    /** Sum of an axis over the batched historical samples + the current one.
     *  AXIS_GESTURE_SCROLL_*_DISTANCE is per-sample, not accumulated —
     *  reading only the current sample drops distance whenever Android
     *  coalesces MOVEs. */
    private static float sumAxis(MotionEvent ev, int axis) {
        float s = 0f;
        for (int i = 0; i < ev.getHistorySize(); i++)
            s += ev.getHistoricalAxisValue(axis, 0, i);
        return s + ev.getAxisValue(axis);
    }

    /** Two-finger scroll (classification 3): MOVE yields pixel distances;
     *  UP/CANCEL = gesture end → a zero-delta finger axis event (axis_stop =
     *  fling start on the client). The pointer stream is NOT torn down.
     *  Sign: AXIS_GESTURE_SCROLL_*_DISTANCE is a scroll-position delta
     *  (positive = page scrolls down/right) — identical to the wayland axis
     *  convention, forwarded verbatim. Position is NOT passed: the fake
     *  finger the gesture converter synthesizes drifts from the cursor.
     *  The classifier may tag cls=3 from the very first event → make up an
     *  enter before the axis (protocol requires axis to follow enter). */
    private void handleTouchpadScroll(MotionEvent ev) {
        int a = ev.getActionMasked();
        if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
            if (padInWin)
                AwlClient.input(id, PTR_AXIS, 1, 0, 0, 0, 0, 0);   /* v=h=0 finger → axis_stop */
            return;
        }
        if (a != MotionEvent.ACTION_MOVE) return;
        if (!padInWin)
            padEnter(ev);
        float v = sumAxis(ev, MotionEvent.AXIS_GESTURE_SCROLL_Y_DISTANCE);
        float h = sumAxis(ev, MotionEvent.AXIS_GESTURE_SCROLL_X_DISTANCE);
        if (v != 0 || h != 0)
            AwlClient.input(id, PTR_AXIS, 1, v, h, 0, 0, 0);
    }

    /** Touchscreen (touch id = code; coordinates = SurfaceView-local).
     * Pointer-class non-touchscreen (mouse/touchpad, one stream on many
     * devices): dispatch by gesture classification FIRST, virtual mouse as
     * the default — two-finger = scroll, multi-finger/pinch = touch
     * passthrough, else (single contact / physical mouse) =
     * handleMouseEvent. */
    /** Activity events are window-local, whereas the compositor consumes
     * SurfaceView-local pixels. Insets (IME, cutouts and system bars)
     * move the surface without moving the Activity coordinate origin.
     * Offset a copy so all pointers and historical samples stay consistent;
     * never mutate the event that Android or the superclass will receive. */
    private MotionEvent surfaceEvent(MotionEvent event) {
        MotionEvent local = MotionEvent.obtain(event);
        int[] origin = new int[2];
        sv.getLocationInWindow(origin);
        local.offsetLocation(-origin[0], -origin[1]);
        return local;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (id < 0 || sv == null) return super.dispatchTouchEvent(ev);
        MotionEvent local = surfaceEvent(ev);
        try {
            if (dispatchSurfaceTouch(local)) return true;
        } finally {
            local.recycle();
        }
        return super.dispatchTouchEvent(ev);
    }

    private boolean dispatchSurfaceTouch(MotionEvent ev) {
        if (isMouse(ev)) {
            int cls = ev.getClassification();
            if (cls == CLS_TWO_FINGER_SWIPE) {
                handleTouchpadScroll(ev);
                return true;
            }
            if (cls != CLS_MULTI_FINGER_SWIPE && cls != CLS_PINCH) {
                handleMouseEvent(ev);   /* whole drag/tap: motion + rel diff + button diff */
                return true;
            }
            /* multi-finger swipe / pinch → touch passthrough. The first finger
             * already went through pointer emulation's enter/motion: send its
             * touch down (the client gets a complete touch stream) and then
             * close the pointer stream. */
            if (padInWin) {
                AwlClient.input(id, TOUCH_DOWN, ev.getPointerId(0),
                        ev.getX(0), ev.getY(0), 0, 0, 0);
                endPadStream();
            }
        }
        switch (ev.getActionMasked()) {
        case MotionEvent.ACTION_DOWN:
            sendTouch(TOUCH_DOWN, ev, ev.getActionIndex());
            return true;
        case MotionEvent.ACTION_POINTER_DOWN:
            sendTouch(TOUCH_DOWN, ev, ev.getActionIndex());
            return true;
        case MotionEvent.ACTION_MOVE:
            for (int i = 0; i < ev.getPointerCount(); i++)
                sendTouch(TOUCH_MOTION, ev, i);
            return true;
        case MotionEvent.ACTION_POINTER_UP:
            sendTouch(TOUCH_UP, ev, ev.getActionIndex());
            return true;
        case MotionEvent.ACTION_UP:
            sendTouch(TOUCH_UP, ev, ev.getActionIndex());
            return true;
        case MotionEvent.ACTION_CANCEL:
            for (int i = 0; i < ev.getPointerCount(); i++)
                AwlClient.input(id, TOUCH_CANCEL, ev.getPointerId(i), 0, 0, 0, 0, 0);
            return true;
        default:
            return false;
        }
    }

    private void sendTouch(int type, MotionEvent ev, int idx) {
        AwlClient.input(id, type, ev.getPointerId(idx),
                       ev.getX(idx), ev.getY(idx), 0, 0, 0);
    }

    /** Mouse/touchpad generic events: hover enter/move/exit + all buttons
     *  + wheel/finger scroll.
     *  Wheel (physical mouse): Android AXIS_VSCROLL is normalized -1.0 (down)
     *  .. 1.0 (up) and AXIS_HSCROLL -1.0 (left) .. 1.0 (right); wayland axis
     *  values live in the motion coordinate space (positive = down / right)
     *  → the vertical notch is negated, the horizontal one forwarded
     *  verbatim (code=0, notches; the daemon does ×10 + axis_discrete).
     *  An ACTION_SCROLL carrying AXIS_GESTURE_SCROLL_*_DISTANCE (touchpad
     *  scroll semantics) is forwarded as raw pixel distances (code=1).
     *  Under pointer capture the system stops hover synthesis; the raw
     *  touchpad stream arrives as ACTION_MOVE + SOURCE_TOUCHPAD with the
     *  relative axes — routed by capture state (NONE keeps ignoring it, the
     *  two streams never double-process). */
    @Override
    public boolean onGenericMotionEvent(MotionEvent ev) {
        if (id < 0 || sv == null) return super.onGenericMotionEvent(ev);
        // Captured touchpad coordinates are relative distances, not positions.
        MotionEvent local = captureMode != CAPTURE_NONE
                && ev.isFromSource(InputDevice.SOURCE_TOUCHPAD)
                ? MotionEvent.obtain(ev) : surfaceEvent(ev);
        try {
            if (dispatchSurfaceMotion(local)) return true;
        } finally {
            local.recycle();
        }
        return super.onGenericMotionEvent(ev);
    }

    // Android exposes the captured touchpad classifications as hidden constants.
    @android.annotation.SuppressLint("WrongConstant")
    private boolean dispatchSurfaceMotion(MotionEvent ev) {
        boolean capturedPad = captureMode != CAPTURE_NONE
                && ev.isFromSource(InputDevice.SOURCE_TOUCHPAD);
        if (!isMouse(ev) && !capturedPad)
            return false;
        switch (ev.getActionMasked()) {
        case MotionEvent.ACTION_MOVE:
            /* captured raw touchpad stream (single finger = virtual mouse; two = scroll) */
            if (ev.getClassification() == CLS_TWO_FINGER_SWIPE) {
                handleTouchpadScroll(ev);
                return true;
            }
            if (ev.getClassification() == CLS_MULTI_FINGER_SWIPE
                    || ev.getClassification() == CLS_PINCH)
                return true;   /* captured: no touch-passthrough stream, swallow */
            handleMouseEvent(ev);
            return true;
        case MotionEvent.ACTION_HOVER_ENTER:
            if (padInWin) {   /* pointer already inside (made up by a scroll/tap) → protocol forbids a 2nd enter, treat as motion */
                handleMouseEvent(ev);
                return true;
            }
            padEnter(ev);
            return true;
        case MotionEvent.ACTION_HOVER_MOVE:
            handleMouseEvent(ev);   /* motion + rel diff + button diff (enter already paired by HOVER_ENTER) */
            return true;
        case MotionEvent.ACTION_HOVER_EXIT:
            if (captureMode != CAPTURE_NONE)
                return true;   /* captured: the pointer cannot leave the window (this exit is capture-start churn) */
            endPadStream();   /* leave + release leftovers + reset the rel anchor */
            return true;
        case MotionEvent.ACTION_SCROLL: {
            if (!padInWin)   /* same as the scroll-classification path: enter before the axis */
                padEnter(ev);
            float fv = sumAxis(ev, MotionEvent.AXIS_GESTURE_SCROLL_Y_DISTANCE);
            float fh = sumAxis(ev, MotionEvent.AXIS_GESTURE_SCROLL_X_DISTANCE);
            if (fv != 0 || fh != 0) {   /* touchpad pixel distances: finger source, no position */
                AwlClient.input(id, PTR_AXIS, 1, fv, fh, 0, 0, 0);
                return true;
            }
            float v = ev.getAxisValue(MotionEvent.AXIS_VSCROLL);
            float h = ev.getAxisValue(MotionEvent.AXIS_HSCROLL);
            if (v != 0 || h != 0) {
                /* wheel notches; v1/v2 = pointer position for the synthesized
                 * surface-coord motion in the axis frame: LOCK = 0,0 (no
                 * motion — the client cursor is frozen), CONFINE = the
                 * clamped virtual position, NONE = raw */
                float px = 0f, py = 0f;
                if (captureMode == CAPTURE_CONFINE) { px = confinex; py = confiney; }
                else if (captureMode == CAPTURE_NONE) { px = ev.getX(); py = ev.getY(); }
                AwlClient.input(id, PTR_AXIS, 0, -v, h, px, py, 0);
            }
            return true;
        }
        case MotionEvent.ACTION_BUTTON_PRESS:
        case MotionEvent.ACTION_BUTTON_RELEASE:
            handleMouseEvent(ev);   /* ensure-enter + savedBS differ (dedups with the touch stream) */
            return true;
        default:
            return false;
        }
    }

    /** Keyboard: evdev scan code forwarded verbatim (matches the embedded
     *  keymap), meta as-is (the daemon decodes the Android meta bits).
     *  Volume keys stay with the system. Android's synthetic key repeats
     *  are swallowed (repeat is the client's own job under wayland;
     *  forwarding them would re-press a held key).
     *  Soft-keyboard/IME synthesized keys have scanCode=0 → map the keycode
     *  to an evdev code.
     *
     *  Backspace while the IME is open is delivered as an instant
     *  press+release tap: LatinIME acts on the DEL ACTION_UP and consumes
     *  it, so a forwarded press would never see its release — the client
     *  then holds the key down and its own key repeat drains the field
     *  (Android apps delete on ACTION_DOWN and never depend on the UP;
     *  wayland receivers key off the release instead). Repeats each become
     *  one more tap, so held-key auto-repeat still works. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent ev) {
        if (id < 0) return super.dispatchKeyEvent(ev);   /* awaiting */
        int kc = ev.getKeyCode();
        if (kc == KeyEvent.KEYCODE_VOLUME_UP || kc == KeyEvent.KEYCODE_VOLUME_DOWN
                || kc == KeyEvent.KEYCODE_VOLUME_MUTE)
            return super.dispatchKeyEvent(ev);
        if (kc == KeyEvent.KEYCODE_DEL && imeWanted) {
            if (ev.getAction() == KeyEvent.ACTION_DOWN) {
                int bs = ev.getScanCode() != 0 ? ev.getScanCode() : fallbackSc(kc);
                if (bs > 0) {
                    AwlClient.input(id, KEY, bs, 0, 0, 1, 0, ev.getMetaState());
                    AwlClient.input(id, KEY, bs, 0, 0, 0, 0, 0);
                }
            }
            return true;   /* UP swallowed — already released with the tap */
        }
        if (ev.getAction() == KeyEvent.ACTION_DOWN && ev.getRepeatCount() > 0)
            return true;   /* synthetic repeat — swallow (see above) */
        int sc = ev.getScanCode();
        int meta = ev.getMetaState();
        if (sc == 0) {
            sc = fallbackSc(kc);
            // These Android symbol keys occupy shifted positions in our US keymap.
            if (kc == KeyEvent.KEYCODE_AT || kc == KeyEvent.KEYCODE_POUND
                    || kc == KeyEvent.KEYCODE_STAR || kc == KeyEvent.KEYCODE_PLUS
                    || kc == KeyEvent.KEYCODE_NUMPAD_LEFT_PAREN
                    || kc == KeyEvent.KEYCODE_NUMPAD_RIGHT_PAREN)
                meta |= KeyEvent.META_SHIFT_ON;
        }
        if (sc > 0) {
            AwlClient.input(id, KEY, sc, 0, 0,
                           ev.getAction() == KeyEvent.ACTION_DOWN ? 1 : 0, 0,
                           meta);
            return true;
        }
        return super.dispatchKeyEvent(ev);
    }

    // A..Z in the embedded evdev US/QWERTY keymap, not Android key-code order.
    private static final int[] LETTER_SC = {
        30, 48, 46, 32, 18, 33, 34, 35, 23, 36, 37, 38, 50,
        49, 24, 25, 16, 19, 31, 20, 22, 47, 17, 45, 21, 44
    };

    /** IME-synthesized key (scanCode=0) → evdev code (matches the embedded keymap).
     * Unicode text from commitText stays on the text-input channel. */
    private static int fallbackSc(int keyCode) {
        if (keyCode >= KeyEvent.KEYCODE_A && keyCode <= KeyEvent.KEYCODE_Z)
            return LETTER_SC[keyCode - KeyEvent.KEYCODE_A];
        if (keyCode >= KeyEvent.KEYCODE_F1 && keyCode <= KeyEvent.KEYCODE_F10)
            return 59 + keyCode - KeyEvent.KEYCODE_F1;
        switch (keyCode) {
        /* Android IMEs can send digits as key events instead of commitText.
         * These synthetic events have no hardware scan code. */
        case KeyEvent.KEYCODE_0:            return 0x0b;
        case KeyEvent.KEYCODE_1:            return 0x02;
        case KeyEvent.KEYCODE_2:            return 0x03;
        case KeyEvent.KEYCODE_3:            return 0x04;
        case KeyEvent.KEYCODE_4:            return 0x05;
        case KeyEvent.KEYCODE_5:            return 0x06;
        case KeyEvent.KEYCODE_6:            return 0x07;
        case KeyEvent.KEYCODE_7:            return 0x08;
        case KeyEvent.KEYCODE_8:            return 0x09;
        case KeyEvent.KEYCODE_9:            return 0x0a;
        case KeyEvent.KEYCODE_GRAVE:        return 41;
        case KeyEvent.KEYCODE_MINUS:        return 12;
        case KeyEvent.KEYCODE_EQUALS:
        case KeyEvent.KEYCODE_PLUS:         return 13;
        case KeyEvent.KEYCODE_LEFT_BRACKET: return 26;
        case KeyEvent.KEYCODE_RIGHT_BRACKET:return 27;
        case KeyEvent.KEYCODE_BACKSLASH:    return 43;
        case KeyEvent.KEYCODE_SEMICOLON:    return 39;
        case KeyEvent.KEYCODE_APOSTROPHE:   return 40;
        case KeyEvent.KEYCODE_COMMA:        return 51;
        case KeyEvent.KEYCODE_PERIOD:       return 52;
        case KeyEvent.KEYCODE_SLASH:        return 53;
        case KeyEvent.KEYCODE_AT:           return 3;    /* Shift+2 */
        case KeyEvent.KEYCODE_POUND:        return 4;    /* Shift+3 */
        case KeyEvent.KEYCODE_STAR:         return 9;    /* Shift+8 */
        case KeyEvent.KEYCODE_NUMPAD_LEFT_PAREN: return 10; /* Shift+9 */
        case KeyEvent.KEYCODE_NUMPAD_RIGHT_PAREN:return 11; /* Shift+0 */
        case KeyEvent.KEYCODE_SHIFT_LEFT:   return 42;
        case KeyEvent.KEYCODE_SHIFT_RIGHT:  return 54;
        case KeyEvent.KEYCODE_CTRL_LEFT:    return 29;
        case KeyEvent.KEYCODE_CTRL_RIGHT:   return 97;
        case KeyEvent.KEYCODE_ALT_LEFT:     return 56;
        case KeyEvent.KEYCODE_ALT_RIGHT:    return 100;
        case KeyEvent.KEYCODE_META_LEFT:    return 125;
        case KeyEvent.KEYCODE_META_RIGHT:   return 126;
        case KeyEvent.KEYCODE_CAPS_LOCK:    return 58;
        case KeyEvent.KEYCODE_NUM_LOCK:     return 69;
        case KeyEvent.KEYCODE_SCROLL_LOCK:  return 70;
        case KeyEvent.KEYCODE_F11:          return 87;
        case KeyEvent.KEYCODE_F12:          return 88;
        case KeyEvent.KEYCODE_INSERT:       return 110;
        case KeyEvent.KEYCODE_SYSRQ:        return 99;
        case KeyEvent.KEYCODE_BREAK:        return 119;
        case KeyEvent.KEYCODE_NUMPAD_0:     return 82;
        case KeyEvent.KEYCODE_NUMPAD_1:     return 79;
        case KeyEvent.KEYCODE_NUMPAD_2:     return 80;
        case KeyEvent.KEYCODE_NUMPAD_3:     return 81;
        case KeyEvent.KEYCODE_NUMPAD_4:     return 75;
        case KeyEvent.KEYCODE_NUMPAD_5:     return 76;
        case KeyEvent.KEYCODE_NUMPAD_6:     return 77;
        case KeyEvent.KEYCODE_NUMPAD_7:     return 71;
        case KeyEvent.KEYCODE_NUMPAD_8:     return 72;
        case KeyEvent.KEYCODE_NUMPAD_9:     return 73;
        case KeyEvent.KEYCODE_NUMPAD_DIVIDE:return 98;
        case KeyEvent.KEYCODE_NUMPAD_MULTIPLY:return 55;
        case KeyEvent.KEYCODE_NUMPAD_SUBTRACT:return 74;
        case KeyEvent.KEYCODE_NUMPAD_ADD:   return 78;
        case KeyEvent.KEYCODE_NUMPAD_DOT:   return 83;
        case KeyEvent.KEYCODE_NUMPAD_COMMA: return 121;
        case KeyEvent.KEYCODE_NUMPAD_EQUALS:return 117;
        case KeyEvent.KEYCODE_ENTER:
        case KeyEvent.KEYCODE_NUMPAD_ENTER: return 0x1c;
        case KeyEvent.KEYCODE_DEL:          return 0x0e;   /* Android DEL = backspace */
        case KeyEvent.KEYCODE_FORWARD_DEL:  return 0x6f;
        case KeyEvent.KEYCODE_TAB:          return 0x0f;
        case KeyEvent.KEYCODE_ESCAPE:       return 0x01;
        case KeyEvent.KEYCODE_SPACE:        return 0x39;
        case KeyEvent.KEYCODE_DPAD_UP:      return 0x67;
        case KeyEvent.KEYCODE_DPAD_DOWN:    return 0x6c;
        case KeyEvent.KEYCODE_DPAD_LEFT:    return 0x69;
        case KeyEvent.KEYCODE_DPAD_RIGHT:   return 0x6a;
        case KeyEvent.KEYCODE_PAGE_UP:      return 0x68;
        case KeyEvent.KEYCODE_PAGE_DOWN:    return 0x6d;
        case KeyEvent.KEYCODE_MOVE_HOME:    return 0x66;
        case KeyEvent.KEYCODE_MOVE_END:     return 0x6b;
        default:                            return 0;
        }
    }

    @Override
    protected void onDestroy() {
        fireHost((cbs, win, act) -> cbs.onHostDestroy(win, act));
        boolean owned = LIVE.remove(id, this);
        if (owned && isFinishing() && id >= 0 && !redirectingDialog)
            Awl.hostGone(id);   /* keep the entry across re-creation / dialog re-hosting */
        if (deathLinked) {
            AwlClient.unmonitorDeath(daemonDeath);
            deathLinked = false;
        }
        /* No detach report: swiping away / killing the background always
         * minimizes and keeps the window alive (onStop already sent PAUSE;
         * process death is backstopped by the daemon-side binder death).
         * Closing = Awl.closeWindow (daemon T_CLOSE) → client closes the
         * window → C_CLOSE → finish */
        attached = false;
        super.onDestroy();
    }
}
