import android.content.Context;
import android.os.Binder;
import android.os.Looper;
import android.os.Parcel;
import android.view.View;
import android.view.inputmethod.InputConnection;
import java.lang.reflect.*;
import java.util.*;

/** Runs the APK's real InputConnection in app_process with an isolated fake
 * Binder. No installed Activity, Linux window or user document is touched.
 * CLASSPATH=probe.dex:candidate.apk app_process /system/bin ImeConnectionProbe */
public final class ImeConnectionProbe {
    static final List<String> events = new ArrayList<>();
    static Class<?> activityClass;
    static Object activity;
    static InputConnection input;
    static Context context;
    static int failures;
    static void field(String name, Object value) throws Exception {
        Field f = activityClass.getDeclaredField(name); f.setAccessible(true); f.set(activity, value);
    }
    static void reset(String text, int cursor, int anchor) throws Exception {
        activity = activityClass.getConstructor().newInstance();
        field("surText", text); field("surCursor", cursor); field("surAnchor", anchor);
        Constructor<?> c = Class.forName(activityClass.getName()+"$WlInputConnection")
                .getDeclaredConstructor(activityClass, View.class);
        c.setAccessible(true); input = (InputConnection)c.newInstance(activity, new View(context));
        events.clear();
    }
    static void check(String name, boolean ok) {
        System.out.println((ok ? "PASS " : "FAIL ")+name);
        if (!ok) failures++;
    }
    static String text() { return input.getExtractedText(null, 0).text.toString(); }
    public static void main(String[] args) throws Exception {
        Looper.prepareMainLooper();
        Class<?> at = Class.forName("android.app.ActivityThread");
        Object thread = at.getMethod("systemMain").invoke(null);
        context = (Context)at.getMethod("getSystemContext").invoke(thread);
        activityClass = Class.forName("com.anlandnext.awl.AwlWindowActivity");
        Class<?> client = Class.forName("com.anlandnext.awl.AwlClient");
        Field service = client.getDeclaredField("s"); service.setAccessible(true);
        service.set(null, new Binder() {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                if (code != 10) throw new AssertionError("Unexpected transaction "+code);
                data.enforceInterface("anland.IHost"); data.readLong();
                int op=data.readInt(), a=data.readInt(), b=data.readInt();
                events.add(op+":"+a+":"+b+":"+data.readString()); return true;
            }
        });
        reset("prefix ",7,7);
        input.setComposingText("hello",1); input.finishComposingText();
        check("finish preserves composition", text().equals("prefix hello") && events.contains("1:0:0:hello"));
        events.clear(); input.finishComposingText();
        check("finish is idempotent", events.isEmpty() && text().equals("prefix hello"));
        reset("prefix hello",12,12);
        for(int i=0;i<5;i++) { input.setComposingRegion(7,12); input.finishComposingText(); }
        check("candidate resegmentation never deletes text", text().equals("prefix hello") && events.isEmpty());
        input.setComposingRegion(7,12); input.commitText("help",1);
        check("candidate replacement is atomic", text().equals("prefix help") && events.equals(List.of("5:5:0:help")));
        reset("say hello",9,9);
        input.setComposingRegion(4,9); input.setComposingText("help",1); input.finishComposingText();
        check("recompose then finish retains only replacement", text().equals("say help") && events.equals(List.of("6:5:0:help","1:0:0:help")));
        reset("abc",3,3); input.setComposingRegion(-10,-1); input.finishComposingText();
        check("negative composing bounds clamp safely", text().equals("abc") && events.isEmpty());
        reset("a\uD83D\uDE00b",4,4); input.setComposingRegion(1,4); input.commitText("中",1);
        check("replacement uses UTF-8 byte lengths", text().equals("a中") && events.equals(List.of("5:5:0:中")));
        reset("one two",3,0); input.commitText("ONE",1);
        check("commit replaces selection", text().equals("ONE two"));
        reset("prefix ",7,7); input.setComposingText("test",0);
        check("zero composing cursor means start", input.getExtractedText(null,0).selectionStart==7 && events.contains("2:0:0:test"));
        if(failures!=0) throw new AssertionError(failures+" InputConnection regressions");
        System.out.println("All InputConnection contract regressions passed");
        System.exit(0);
    }
}
