import android.app.Activity;
import android.os.Binder;
import android.os.Looper;
import android.os.Parcel;
import android.view.KeyEvent;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/** Exercises the APK's real key dispatch with IME-style zero scan codes.
 * CLASSPATH=probe.dex:candidate.apk app_process /system/bin ImeDigitKeyProbe */
public final class ImeDigitKeyProbe {
    static final List<String> events = new ArrayList<>();
    static int failures;

    static void check(String name, boolean ok) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok) failures++;
    }

    static KeyEvent event(int action, int keyCode, int scanCode, int meta, int repeat) {
        return new KeyEvent(0, 0, action, keyCode, repeat, meta, -1, scanCode,
                KeyEvent.FLAG_SOFT_KEYBOARD | KeyEvent.FLAG_KEEP_TOUCH_MODE);
    }

    static void tap(Activity activity, int keyCode, int scanCode, int meta) {
        events.clear();
        try {
            activity.dispatchKeyEvent(event(KeyEvent.ACTION_DOWN, keyCode, scanCode, meta, 0));
            activity.dispatchKeyEvent(event(KeyEvent.ACTION_UP, keyCode, scanCode, meta, 0));
        } catch (NullPointerException noWindow) {
            // An unhandled key falls into Activity's unattached window in this probe.
            // It must still fail the assertion that both Wayland events arrived.
        }
    }

    public static void main(String[] args) throws Exception {
        Looper.prepareMainLooper();
        Class.forName("android.app.ActivityThread").getMethod("systemMain").invoke(null);
        Class<?> type = Class.forName("com.anlandnext.awl.AwlWindowActivity");
        Activity activity = (Activity) type.getConstructor().newInstance();
        Field id = type.getDeclaredField("id");
        id.setAccessible(true); id.setLong(activity, 123);
        Field wanted = type.getDeclaredField("imeWanted");
        wanted.setAccessible(true); wanted.setBoolean(activity, true);
        Field service = Class.forName("com.anlandnext.awl.AwlClient").getDeclaredField("s");
        service.setAccessible(true);
        service.set(null, new Binder() {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                if (code != 9) throw new AssertionError("Unexpected transaction " + code);
                data.enforceInterface("anland.IHost");
                long window = data.readLong();
                int kind = data.readInt(), scan = data.readInt();
                data.readFloat(); data.readFloat();
                float pressed = data.readFloat(); data.readFloat();
                int meta = data.readInt();
                events.add(window + ":" + kind + ":" + scan + ":" + (int) pressed + ":" + meta);
                return true;
            }
        });
        for (int digit = 0; digit <= 9; digit++) {
            int scan = digit == 0 ? 11 : digit + 1;
            tap(activity, KeyEvent.KEYCODE_0 + digit, 0, 0);
            check("IME digit " + digit + " reaches Wayland as one tap", events.equals(List.of(
                    "123:9:" + scan + ":1:0", "123:9:" + scan + ":0:0")));
        }
        tap(activity, KeyEvent.KEYCODE_1, 0, KeyEvent.META_SHIFT_ON);
        check("shift modifier survives synthetic digit", events.equals(List.of("123:9:2:1:1", "123:9:2:0:1")));
        tap(activity, KeyEvent.KEYCODE_1, 79, 0);
        check("physical scan code takes precedence", events.equals(List.of("123:9:79:1:0", "123:9:79:0:0")));
        events.clear();
        activity.dispatchKeyEvent(event(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_1, 0, 0, 2));
        check("Android repeat does not duplicate Wayland repeat", events.isEmpty());
        tap(activity, KeyEvent.KEYCODE_DEL, 0, 0);
        check("IME backspace remains one immediate tap", events.equals(List.of("123:9:14:1:0", "123:9:14:0:0")));
        if (failures != 0) throw new AssertionError(failures + " key dispatch regressions");
        System.out.println("All IME digit key regressions passed");
        System.exit(0);
    }
}
