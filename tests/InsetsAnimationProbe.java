import android.content.Context;
import android.graphics.Insets;
import android.os.Looper;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsAnimation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Dispatch real Android inset callbacks through an isolated View and the
 * candidate APK's controller. No installed Activity or user input is touched.
 * CLASSPATH=probe.dex:candidate.apk app_process /system/bin InsetsAnimationProbe */
public final class InsetsAnimationProbe {
    static final List<Integer> applied = new ArrayList<>();
    static WindowInsets insets(int bottom) {
        return new WindowInsets.Builder().setInsets(WindowInsets.Type.ime(),
                Insets.of(0, 0, 0, bottom)).build();
    }
    static WindowInsetsAnimation animation(int type) {
        return new WindowInsetsAnimation(type, null, 250);
    }
    static void equal(int... expected) {
        if (applied.size() != expected.length) throw new AssertionError(applied);
        for (int i=0; i<expected.length; i++)
            if (applied.get(i) != expected[i]) throw new AssertionError(applied);
        applied.clear();
    }
    public static void main(String[] args) throws Exception {
        Looper.prepareMainLooper();
        Class<?> at = Class.forName("android.app.ActivityThread");
        Object thread = at.getMethod("systemMain").invoke(null);
        Context context = (Context)at.getMethod("getSystemContext").invoke(thread);
        View root = new View(context);
        Method install = Class.forName("com.anlandnext.awl.SurfaceInsetsController")
                .getDeclaredMethod("install", View.class, Consumer.class);
        install.setAccessible(true);
        install.invoke(null, root, (Consumer<WindowInsets>) i ->
                applied.add(i.getInsets(WindowInsets.Type.ime()).bottom));
        root.dispatchApplyWindowInsets(insets(0)); equal(0);

        WindowInsetsAnimation ime = animation(WindowInsets.Type.ime());
        root.dispatchWindowInsetsAnimationPrepare(ime);
        root.dispatchApplyWindowInsets(insets(600)); equal();
        root.dispatchWindowInsetsAnimationProgress(insets(0), List.of(ime));
        root.dispatchWindowInsetsAnimationProgress(insets(200), List.of(ime));
        equal(0, 200);
        root.layout(0, 0, 3200, 2136); equal(200);
        root.dispatchWindowInsetsAnimationProgress(insets(600), List.of(ime));
        root.dispatchWindowInsetsAnimationEnd(ime); equal(600, 600);
        System.out.println("PASS end-state dispatch cannot jump ahead; relayout keeps the displayed frame");

        WindowInsetsAnimation hide = animation(WindowInsets.Type.ime());
        root.dispatchWindowInsetsAnimationPrepare(hide);
        root.dispatchApplyWindowInsets(insets(0)); equal();
        // System cancellation is legal before onStart/onProgress.
        root.dispatchWindowInsetsAnimationEnd(hide); equal(0);
        System.out.println("PASS cancellation before onStart applies the final target");

        WindowInsetsAnimation keyboard = animation(WindowInsets.Type.ime());
        WindowInsetsAnimation bars = animation(WindowInsets.Type.navigationBars());
        root.dispatchWindowInsetsAnimationPrepare(keyboard);
        root.dispatchWindowInsetsAnimationPrepare(bars);
        root.dispatchApplyWindowInsets(insets(700)); equal();
        root.dispatchWindowInsetsAnimationProgress(insets(250), List.of(keyboard,bars)); equal(250);
        root.dispatchWindowInsetsAnimationEnd(bars); equal();
        root.dispatchWindowInsetsAnimationProgress(insets(500), List.of(keyboard)); equal(500);
        root.dispatchWindowInsetsAnimationEnd(keyboard); equal(700);
        root.dispatchApplyWindowInsets(insets(900)); equal(900);
        System.out.println("PASS overlapping bar/IME animations and nonanimated keyboard size changes");
    }
}
