package com.anland.shell.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.LruCache;

import com.anland.shell.ds.AppEntry;
import com.anland.shell.ds.DsCli;
import com.anland.shell.ds.RootExec;

import java.io.File;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Async container-app icon loading with a memory LruCache and a PNG disk
 * cache, so a grid fill costs at most one container round trip per icon and
 * nothing on later runs.
 *
 * Lookup follows the .desktop icon convention: an absolute path is fetched
 * directly; otherwise the icon theme dirs are searched (DsCli.findIcons) and
 * adequate-resolution rasters and scalable vectors beat tiny raster variants.
 * Transparent padding is normalized without changing the image aspect ratio.
 * SVG icons are rendered into bounded bitmaps; unsupported/corrupt entries
 * fall back to a generated letter tile. No application-specific icon table.
 */
public final class IconLoader {

    /** Receives the bitmap on the main thread (memory hits call back
     *  synchronously on the loading thread, which is the UI thread). */
    public interface Listener {
        void onIcon(String key, Bitmap icon);
    }

    private static final int MEM_KB = 4 * 1024;   /* ~4 MiB of bitmaps */
    private static final int TILE_PX = 192;
    private static final Pattern SIZE = Pattern.compile("(\\d+)x\\d+");

    private static final int[] TILE_COLORS = {
            0xFF5C6BC0, 0xFF00897B, 0xFFD81B60, 0xFFF4511E,
            0xFF3949AB, 0xFF7CB342, 0xFF6D4C41, 0xFF00ACC1,
    };

    private final File dir;   /* files/icons disk cache */
    private final LruCache<String, Bitmap> mem = new LruCache<String, Bitmap>(MEM_KB) {
        @Override protected int sizeOf(String key, Bitmap v) {
            return Math.max(1, v.getByteCount() / 1024);
        }
    };
    private final ExecutorService pool = Executors.newFixedThreadPool(4, new ThreadFactory() {
        private final AtomicInteger n = new AtomicInteger();
        @Override public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "iconload-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    });
    private final Handler main = new Handler(Looper.getMainLooper());

    public IconLoader(Context context) {
        // Versioned cache prevents old blank/undersized renders surviving upgrade.
        dir = new File(context.getApplicationContext().getFilesDir(), "icons-v2");
    }

    public void close() { pool.shutdown(); }

    /** Synchronous memory-cache peek (used before pinning a shortcut). */
    public Bitmap peek(AppEntry app) {
        return app.icon.isEmpty() ? null : mem.get(app.iconKey());
    }

    /** Load the icon for one app; always delivers something (worst case the
     *  generated letter tile). */
    public void load(final AppEntry app, final Listener cb) {
        final String key = app.iconKey();
        if (!app.icon.isEmpty()) {
            Bitmap hit = mem.get(key);
            if (hit != null) {
                cb.onIcon(key, hit);
                return;
            }
        }
        pool.execute(() -> {
            Bitmap bmp = null;
            if (!app.icon.isEmpty()) {
                File cached = new File(dir, hash(key));
                // Theme/icon package updates are picked up without clearing app data.
                if (System.currentTimeMillis() - cached.lastModified() < 24L * 60 * 60 * 1000)
                    bmp = decodeFile(cached);
                if (bmp == null)
                    bmp = fetchFromContainer(app, key);
            }
            if (bmp == null)
                bmp = letterTile(app.name, TILE_PX);
            if (!app.icon.isEmpty())
                mem.put(key, bmp);
            final Bitmap b = bmp;
            main.post(() -> cb.onIcon(key, b));
        });
    }

    // ----------------------------------------------------------------- fetch

    /** Find, fetch and decode the best candidate from the container. */
    private Bitmap fetchFromContainer(AppEntry app, String key) {
        List<String> candidates = new ArrayList<>();
        if (app.icon.startsWith("/")) {
            candidates.add(app.icon);
        } else {
            RootExec.Result r = DsCli.findIcons(app.container, app.icon);
            if (r.stdout != null) {
                for (String line : r.stdout.split("\n")) {
                    line = line.trim();
                    if (!line.isEmpty())
                        candidates.add(line);
                }
            }
        }
        String best = pickBest(candidates);
        while (best != null) {
            Bitmap b = fetchDecode(app.container, best);
            if (b != null) {
                saveDisk(key, b);
                return b;
            }
            candidates.remove(best);
            best = pickBest(candidates);
        }
        return null;
    }

    /** Prefer a sharp app icon; never choose a 16px raster over a scalable vector. */
    private static String pickBest(List<String> candidates) {
        String best = null;
        int bestScore = -1, bestSize = -1;
        for (String path : candidates) {
            int dot = path.lastIndexOf('.');
            String ext = dot < 0 ? "" : path.substring(dot + 1).toLowerCase(Locale.ROOT);
            int score;
            if ("png".equals(ext))
                score = 3;
            else if ("webp".equals(ext))
                score = 2;
            else if ("jpg".equals(ext) || "jpeg".equals(ext) || "bmp".equals(ext) || "gif".equals(ext))
                score = 1;
            else if ("svg".equals(ext))
                score = 0;
            else
                continue;   /* xpm / unknown: unsupported */
            int size = 0;
            Matcher m = SIZE.matcher(path);
            if (m.find())
                try {
                    size = Integer.parseInt(m.group(1));
                } catch (NumberFormatException ignored) {
                }
            // A vector has no intrinsic raster size. Known application directories
            // are preferred over unrelated action/category icons with the same name.
            score += "svg".equals(ext) ? 40 : (size >= 128 ? 50 : size >= 64 ? 35 : size == 0 ? 30 : 10);
            if (path.contains("/apps/")) score += 100;
            if (path.contains("/hicolor/")) score += 10;
            if (path.contains("/symbolic/") || path.contains("-symbolic.")) score -= 20;
            if (score > bestScore || (score == bestScore && size > bestSize)) {
                best = path;
                bestScore = score;
                bestSize = size;
            }
        }
        return best;
    }

    private static Bitmap fetchDecode(String container, String path) {
        RootExec.Result r = DsCli.fetchIconB64(container, path);
        if (!r.ok || r.stdout.trim().isEmpty())
            return null;
        try {
            byte[] raw = Base64.decode(r.stdout.trim(), Base64.DEFAULT);
            if (raw.length > 1024 * 1024) return null;
            if (path.toLowerCase(Locale.ROOT).endsWith(".svg")) {
                String xml = new String(raw, java.nio.charset.StandardCharsets.UTF_8);
                String upper = xml.toUpperCase(Locale.ROOT);
                if (upper.contains("<!DOCTYPE") || upper.contains("<!ENTITY")) return null;
                // No resolver for external files or network assets. Render to a fixed
                // allocation rather than trusting dimensions supplied by the document.
                com.caverock.androidsvg.SVG svg = com.caverock.androidsvg.SVG.getFromString(xml);
                Bitmap bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888);
                new Canvas(bitmap).drawPicture(svg.renderToPicture(256, 256));
                return normalize(bitmap);
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(raw, 0, raw.length, options);
            if (options.outWidth <= 0 || options.outHeight <= 0 || options.outWidth > 4096 || options.outHeight > 4096) return null;
            options.inJustDecodeBounds = false;
            options.inSampleSize = 1;
            while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 256) options.inSampleSize *= 2;
            return normalize(BitmapFactory.decodeByteArray(raw, 0, raw.length, options));
        } catch (Exception e) {
            return null;   /* corrupt base64/icon — try the next candidate */
        }
    }

    /** Crop transparent padding, then contain in a shared optical box. Empty
     * decodes are failures, so the next candidate or generic tile can be used. */
    static Bitmap normalize(Bitmap source) {
        if (source == null) return null;
        int w=source.getWidth(), h=source.getHeight(), left=w, top=h, right=-1, bottom=-1;
        int[] row=new int[w];
        for(int y=0;y<h;y++) {
            source.getPixels(row,0,w,0,y,w,1);
            for(int x=0;x<w;x++) if(Color.alpha(row[x])>=24) {
                left=Math.min(left,x);right=Math.max(right,x);top=Math.min(top,y);bottom=Math.max(bottom,y);
            }
        }
        if(right<left || bottom<top)return null;
        float scale=(TILE_PX*.9f)/Math.max(right-left+1,bottom-top+1);
        float dw=(right-left+1)*scale,dh=(bottom-top+1)*scale;
        Bitmap result=Bitmap.createBitmap(TILE_PX,TILE_PX,Bitmap.Config.ARGB_8888);
        new Canvas(result).drawBitmap(source,new Rect(left,top,right+1,bottom+1),new RectF((TILE_PX-dw)/2,(TILE_PX-dh)/2,(TILE_PX+dw)/2,(TILE_PX+dh)/2),new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG));
        return result;
    }

    /** Flat neutral glyphs need theme contrast; full-color artwork is untouched. */
    public static boolean isMonochrome(Bitmap image) {
        int low=255,high=0,count=0;
        int[] row=new int[image.getWidth()];
        for(int y=0;y<image.getHeight();y++) {
            image.getPixels(row,0,row.length,0,y,row.length,1);
            for(int pixel:row)if(Color.alpha(pixel)>128) {
                int r=Color.red(pixel),g=Color.green(pixel),b=Color.blue(pixel);
                if(Math.max(r,Math.max(g,b))-Math.min(r,Math.min(g,b))>8)return false;
                low=Math.min(low,r);high=Math.max(high,r);count++;
                if(high-low>20)return false;
            }
        }
        return count>0;
    }

    // ------------------------------------------------------------------ disk

    private static Bitmap decodeFile(File f) {
        if (!f.isFile())
            return null;
        Bitmap b = BitmapFactory.decodeFile(f.getPath());
        if (b == null)
            // noinspection ResultOfMethodCallIgnored
            f.delete();
        return b;
    }

    private void saveDisk(String key, Bitmap b) {
        try {
            // noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            try (FileOutputStream out = new FileOutputStream(new File(dir, hash(key)))) {
                b.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
        } catch (Exception ignored) {
            /* disk cache is best-effort */
        }
    }

    private static String hash(String key) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte x : d)
                sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(key.hashCode());
        }
    }

    // ----------------------------------------------------------- letter tile

    /** Generated placeholder: Unicode initial on a rounded tile; no app table. */
    public static Bitmap letterTile(String name, int sizePx) {
        Bitmap b = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        int color = TILE_COLORS[Math.abs(name.isEmpty() ? 0 : name.charAt(0)) % TILE_COLORS.length];
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        float inset=sizePx*.05f;
        c.drawRoundRect(inset,inset,sizePx-inset,sizePx-inset,sizePx*.23f,sizePx*.23f,p);
        p.setColor(Color.WHITE);
        p.setTextSize(sizePx * 0.45f);
        p.setTextAlign(Paint.Align.CENTER);
        String ch = name.isEmpty() ? "?" : new String(Character.toChars(name.codePointAt(0))).toUpperCase(Locale.ROOT);
        Paint.FontMetrics fm = p.getFontMetrics();
        c.drawText(ch, sizePx / 2f, sizePx / 2f - (fm.ascent + fm.descent) / 2f, p);
        return b;
    }
}
