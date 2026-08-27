package com.timestamp.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.util.Log;
import android.widget.RemoteViews;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

/*
 * Home screen widget that appends a timestamp to one text document per tap.
 *
 * No service, no alarm, no boot receiver, updatePeriodMillis=0 -> nothing ever
 * runs in the background. A tap starts the process, writes one line, wipes the
 * cache dirs and the process is left empty for the system to reclaim.
 *
 * Stored state per widget id (SharedPreferences "w"):
 *   u<id> -> document uri        n<id> -> label shown on the widget
 *   t<id> -> last accepted tap (millis), the whole debounce state
 */
public class TimestampWidget extends AppWidgetProvider {

    static final String TAG = "TsWidget";

    static final String PREFS = "w";
    static final String KEY_URI = "u";
    static final String KEY_NAME = "n";
    static final String KEY_LAST = "t";

    /** Taps that land inside this window after an accepted one are dropped. */
    static final long BLOCK_MS = 4000L;

    private static final String ACTION_TICK = "com.timestamp.widget.TICK";
    private static final String STAMP = "yyyy.MM.dd HH.mm.ss";
    private static final String EOL = "\r\n";

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static void render(Context c, AppWidgetManager m, int id) {
        String name = prefs(c).getString(KEY_NAME + id, null);
        RemoteViews rv = new RemoteViews(c.getPackageName(), R.layout.widget);
        rv.setTextViewText(R.id.label, name == null ? c.getString(R.string.no_file) : name);
        rv.setOnClickPendingIntent(R.id.label, tapIntent(c, id));
        m.updateAppWidget(id, rv);
    }

    private static PendingIntent tapIntent(Context c, int id) {
        Intent i = new Intent(c, TimestampWidget.class);
        i.setAction(ACTION_TICK);
        i.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        // request code = widget id -> every widget keeps its own PendingIntent
        return PendingIntent.getBroadcast(c, id, i, flags);
    }

    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        for (int id : ids) render(c, m, id);
    }

    @Override
    public void onDeleted(Context c, int[] ids) {
        SharedPreferences sp = prefs(c);
        ArrayList<String> dropped = new ArrayList<String>();
        SharedPreferences.Editor e = sp.edit();
        for (int id : ids) {
            String uri = sp.getString(KEY_URI + id, null);
            if (uri != null) dropped.add(uri);
            e.remove(KEY_URI + id).remove(KEY_NAME + id).remove(KEY_LAST + id);
        }
        e.commit();

        for (int i = 0; i < dropped.size(); i++) {
            String uri = dropped.get(i);
            if (stillUsed(sp, uri)) continue;   // a second widget points at the same file
            try {
                c.getContentResolver().releasePersistableUriPermission(Uri.parse(uri),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (Throwable t) {
                Log.w(TAG, "could not release uri permission: " + uri, t);
            }
        }
        wipeCache(c);
    }

    private static boolean stillUsed(SharedPreferences sp, String uri) {
        try {
            for (Map.Entry<String, ?> en : sp.getAll().entrySet()) {
                if (en.getKey().startsWith(KEY_URI) && uri.equals(en.getValue())) return true;
            }
        } catch (Throwable t) {
            Log.w(TAG, "prefs scan failed, keeping permission", t);
            return true;    // unsure -> do not revoke someone else's access
        }
        return false;
    }

    @Override
    public void onReceive(Context c, Intent i) {
        super.onReceive(c, i);
        if (i == null || !ACTION_TICK.equals(i.getAction())) return;
        try {
            stamp(c, i.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,
                    AppWidgetManager.INVALID_APPWIDGET_ID));
        } catch (Throwable t) {
            Log.e(TAG, "tap handling failed", t);
        } finally {
            wipeCache(c);
        }
    }

    private static void stamp(Context c, int id) {
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) {
            Log.w(TAG, "tap without a widget id");
            return;
        }
        SharedPreferences sp = prefs(c);
        long now = System.currentTimeMillis();
        long since = now - sp.getLong(KEY_LAST + id, 0L);
        // since < 0 means the clock moved backwards: accept and re-anchor
        if (since >= 0 && since < BLOCK_MS) return;

        String uri = sp.getString(KEY_URI + id, null);
        if (uri == null) {
            toast(c, R.string.no_file);
            return;
        }
        String line = new SimpleDateFormat(STAMP, Locale.US).format(new Date(now)) + EOL;
        if (append(c, Uri.parse(uri), line)) {
            sp.edit().putLong(KEY_LAST + id, now).commit();   // apply() may not survive process death
        } else {
            toast(c, R.string.write_failed);
        }
    }

    private static boolean append(Context c, Uri uri, String line) {
        byte[] data;
        try {
            data = line.getBytes("UTF-8");
        } catch (Throwable t) {
            Log.w(TAG, "UTF-8 unavailable, using platform charset", t);
            data = line.getBytes();
        }
        ContentResolver cr = c.getContentResolver();

        // 1) real append - what every SAF provider backed by a file supports
        OutputStream out = null;
        try {
            out = cr.openOutputStream(uri, "wa");
            if (out != null) {
                out.write(data);
                out.flush();
                return true;
            }
            Log.w(TAG, "append stream was null");
        } catch (Throwable t) {
            Log.w(TAG, "append mode 'wa' refused, falling back to rewrite", t);
        } finally {
            close(out);
        }

        // 2) provider without append support: read what is there, add the line, write it back
        InputStream in = null;
        OutputStream rewrite = null;
        try {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            in = cr.openInputStream(uri);
            if (in != null) {
                byte[] chunk = new byte[4096];
                int n;
                while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
            }
            buf.write(data);
            rewrite = cr.openOutputStream(uri, "wt");
            if (rewrite != null) {
                rewrite.write(buf.toByteArray());
                rewrite.flush();
                return true;
            }
            Log.e(TAG, "rewrite stream was null");
        } catch (Throwable t) {
            Log.e(TAG, "rewrite append failed", t);
        } finally {
            close(in);
            close(rewrite);
        }
        return false;
    }

    /* Keeps Settings > Storage > Cache at zero: nothing here is ever reused. */
    static void wipeCache(Context c) {
        try {
            del(c.getCacheDir());
            if (Build.VERSION.SDK_INT >= 21) del(c.getCodeCacheDir());
        } catch (Throwable t) {
            Log.w(TAG, "cache wipe failed", t);
        }
    }

    private static void del(File f) {
        if (f == null) return;
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) del(k);
        }
        f.delete();
    }

    private static void close(Closeable ch) {
        if (ch == null) return;
        try {
            ch.close();
        } catch (Throwable t) {
            Log.w(TAG, "close failed", t);
        }
    }

    private static void toast(Context c, int res) {
        try {
            Toast.makeText(c, res, Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Log.w(TAG, "toast failed", t);
        }
    }
}
