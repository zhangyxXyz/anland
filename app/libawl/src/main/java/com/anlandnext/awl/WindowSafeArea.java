package com.anlandnext.awl;

/** Geometry only: all measurements come from the current Android window. */
final class WindowSafeArea {
    /** Rootful Xwayland owns a complete desktop, including its background.
     * Its compositor/panels own control placement; don't shrink its wallpaper.
     * This is the Xwayland protocol identity, not a user-editable window title. */
    static boolean isDesktop(String appId) {
        return "org.freedesktop.Xwayland".equals(appId);
    }

    static int[] contentMargins(String appId, int x, int y, int width, int height,
                                int[] base, int[][] corners) {
        return isDesktop(appId) ? base.clone() : margins(x, y, width, height, base, corners);
    }
    // Corners in Android order TL, TR, BR, BL; each {centerX, centerY, radius}.
    // The returned {left, top, right, bottom} also respects existing obstructions.
    static int[] margins(int x, int y, int width, int height, int[] base, int[][] corners) {
        int[] out = base.clone();
        for (int i = 0; i < 4; i++) {
            if (corners[i] == null || corners[i][2] <= 0) continue;
            boolean left = i == 0 || i == 3, top = i == 0 || i == 1;
            int cx = corners[i][0], cy = corners[i][1], radius = corners[i][2];
            int px = left ? x + out[0] : x + width - out[2];
            int py = top ? y + out[1] : y + height - out[3];
            int dx = left ? cx - px : px - cx;
            int dy = top ? cy - py : py - cy;
            if (dx <= 0 || dy <= 0 || (long) dx * dx + (long) dy * dy <= (long) radius * radius)
                continue; // this content corner is already outside the clipped quadrant
            int offset = (int) Math.floor(radius / Math.sqrt(2));
            if (left) out[0] = Math.max(out[0], cx - offset - x);
            else out[2] = Math.max(out[2], x + width - cx - offset);
            if (top) out[1] = Math.max(out[1], cy - offset - y);
            else out[3] = Math.max(out[3], y + height - cy - offset);
        }
        return out;
    }
}
