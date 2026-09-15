package com.anland.shell.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
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
 * the best raster candidate wins — png over webp over other rasters, larger
 * size first (size taken from the hicolor NNxNN path segment). Anything
 * undecodable (SVG/XPM-only entries) falls back to a generated letter tile.
 */
public final class IconLoader {

    /** Receives the bitmap on the main thread (memory hits call back
     *  synchronously on the loading thread, which is the UI thread). */
    public interface Listener {
        void onIcon(String key, Bitmap icon);
    }

    private static final int MEM_KB = 4 * 1024;   /* ~4 MiB of bitmaps */
    private static final int TILE_PX = 96;
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
        dir = new File(context.getApplicationContext().getFilesDir(), "icons");
    }

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
                bmp = decodeFile(new File(dir, hash(key)));
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

    /** Best raster candidate: png > webp > other rasters, then size desc.
     *  Non-raster (svg/xpm) or empty → null. */
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
            else
                continue;   /* svg / xpm / unknown: not decodable by BitmapFactory */
            int size = 0;
            Matcher m = SIZE.matcher(path);
            if (m.find())
                try {
                    size = Integer.parseInt(m.group(1));
                } catch (NumberFormatException ignored) {
                }
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
            return BitmapFactory.decodeByteArray(raw, 0, raw.length);
        } catch (IllegalArgumentException e) {
            return null;   /* corrupt base64 — try the next candidate */
        }
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

    /** Generated placeholder: first character on a colored circle. */
    public static Bitmap letterTile(String name, int sizePx) {
        Bitmap b = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        int color = TILE_COLORS[Math.abs(name.isEmpty() ? 0 : name.charAt(0)) % TILE_COLORS.length];
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        c.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f, p);
        p.setColor(Color.WHITE);
        p.setTextSize(sizePx * 0.45f);
        p.setTextAlign(Paint.Align.CENTER);
        String ch = name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase(Locale.ROOT);
        Paint.FontMetrics fm = p.getFontMetrics();
        c.drawText(ch, sizePx / 2f, sizePx / 2f - (fm.ascent + fm.descent) / 2f, p);
        return b;
    }
}
