package com.anlandnext.awl;

/** Insets for the client surface, not for individual Android overlay controls. */
final class WindowSafeArea {
    /** Every client owns its background and controls in the same rendered buffer.
     * Rounded display corners clip that buffer; shrinking the entire surface to
     * a control-safe rectangle exposes the host background along all four edges.
     * Desktop and independent windows therefore use the same full-bleed policy,
     * independent of app identity (which can arrive after the first frame).
     * Real obstructions still win, and inset-mode IME makes the client reflow. */
    static int[] contentMargins(int[] systemInsets, int imeBottom, boolean imeOverlay) {
        int[] out = systemInsets.clone();
        if (!imeOverlay) out[3] = Math.max(out[3], imeBottom);
        return out;
    }
}
