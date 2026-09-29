package com.anlandnext.awl;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

/** A window's complete task identity, loaded off the main thread before launch.
 * Keep this scoped to a window, not a package: browser apps and dialogs can
 * have different identities even when they share a Linux process. */
final class WindowTaskIdentity {
    final String title;
    final AwlClient.DesktopInfo desktop;
    final Bitmap icon;

    private WindowTaskIdentity(String title, AwlClient.DesktopInfo desktop, Bitmap icon) {
        this.title = title;
        this.desktop = desktop;
        this.icon = icon;
    }

    static WindowTaskIdentity load(long id) {
        java.util.List<AwlClient.WinInfo> windows = AwlClient.list();
        if (windows == null) return null;
        AwlClient.WinInfo window = null;
        for (AwlClient.WinInfo candidate : windows) if (candidate.id == id) { window = candidate; break; }
        if (window == null) return null;
        AwlClient.DesktopInfo desktop = AwlClient.desktopInfo(id);
        int[] wh = new int[2];
        byte[] pixels = AwlClient.icon(id, wh);
        Bitmap bitmap = null;
        if (pixels != null && wh[0] > 0 && wh[1] > 0
                && (long) wh[0] * wh[1] * 4 == pixels.length) {
            try {
                bitmap = Bitmap.createBitmap(wh[0], wh[1], Bitmap.Config.ARGB_8888);
                bitmap.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(pixels));
            } catch (RuntimeException e) {
                Log.w("anland-awlwin", "toplevel icon decode failed", e);
                bitmap = null;
            }
        }
        if (bitmap == null && desktop != null && desktop.icon != null && desktop.icon.length > 0) {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(desktop.icon, 0, desktop.icon.length, bounds);
            if (bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth <= 4096 && bounds.outHeight <= 4096) {
                bounds.inJustDecodeBounds = false;
                bounds.inSampleSize = Math.max(1, Math.max(bounds.outWidth, bounds.outHeight) / 128);
                bitmap = BitmapFactory.decodeByteArray(desktop.icon, 0, desktop.icon.length, bounds);
            } else {
                // Render desktop SVG icons into a bounded bitmap. Do not
                // install an external-file/network resolver for SVG assets.
                try {
                    String xml = new String(desktop.icon, java.nio.charset.StandardCharsets.UTF_8);
                    if (xml.contains("<svg") && !xml.contains("<!DOCTYPE") && !xml.contains("<!ENTITY")) {
                        com.caverock.androidsvg.SVG svg = com.caverock.androidsvg.SVG.getFromString(xml);
                        bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888);
                        new android.graphics.Canvas(bitmap).drawPicture(svg.renderToPicture(128, 128));
                    }
                } catch (Exception e) { Log.w("anland-awlwin", "desktop SVG decode failed", e); }
            }
        }
        return new WindowTaskIdentity(window.title, desktop, bitmap);
    }
}
