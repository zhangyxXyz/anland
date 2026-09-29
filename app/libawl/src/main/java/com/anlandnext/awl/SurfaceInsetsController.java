package com.anlandnext.awl;

import android.annotation.TargetApi;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsAnimation;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/** One source of layout insets, including the IME's actual animation frame. */
@TargetApi(30)
final class SurfaceInsetsController extends WindowInsetsAnimation.Callback {
    private final Set<WindowInsetsAnimation> animations = new HashSet<>();
    private final Consumer<WindowInsets> apply;
    private WindowInsets target, displayed;

    static void install(View root, Consumer<WindowInsets> apply) {
        SurfaceInsetsController controller = new SurfaceInsetsController(apply);
        root.setWindowInsetsAnimationCallback(controller);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            controller.target = insets;
            // Android dispatches the END state before onStart/onProgress.
            // Applying it now jumps ahead of the keyboard, then back again.
            if (controller.animations.isEmpty()) controller.display(insets);
            return insets;
        });
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            // RootWindowInsets may already contain the end state. Reuse the
            // displayed frame when layout/rotation makes the root measurable.
            if (controller.displayed != null) controller.apply.accept(controller.displayed);
        });
    }

    private SurfaceInsetsController(Consumer<WindowInsets> apply) {
        super(DISPATCH_MODE_CONTINUE_ON_SUBTREE);
        this.apply = apply;
    }

    private void display(WindowInsets insets) {
        displayed = insets;
        apply.accept(insets);
    }

    @Override public void onPrepare(WindowInsetsAnimation animation) {
        animations.add(animation);
    }

    @Override public WindowInsets onProgress(WindowInsets insets,
                                             List<WindowInsetsAnimation> running) {
        display(insets);
        return insets;
    }

    @Override public void onEnd(WindowInsetsAnimation animation) {
        animations.remove(animation);
        // Also handles cancellation before onStart and overlapping bar/IME
        // animations; only the last ending animation may apply the target.
        if (animations.isEmpty() && target != null) display(target);
    }
}
