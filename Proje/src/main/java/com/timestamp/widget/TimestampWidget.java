package com.timestamp.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;
import android.widget.RemoteViews;
import android.widget.Toast;

import java.io.File;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/*
 * Home screen widget: one tap appends one timestamp line to one text document.
 *
 * No service, no alarm, no boot receiver, updatePeriodMillis=0 -> nothing runs
 * in the background. A tap starts the process, writes a line, wipes the cache
 * dirs and leaves the process empty for the system to reclaim.
 *
 * Prefs "w", three short keys per widget id:
 *   u<id> document uri   n<id> label on the widget   t<id> last accepted tap
 *
 * Everything here is kept deliberately terse: message strings, toasts and
 * string resources all end up in classes.dex / resources.arsc, and this APK is
 * meant to stay in the ~5 KB range.
 */
public class TimestampWidget extends AppWidgetProvider {

    static final String TAG = "TsW";

    /** Explicit broadcast, so the action only has to be unique inside this app. */
    private static final String TICK = "t";

    /** Taps landing inside this window after an accepted one are dropped. */
    private static final long BLOCK_MS = 4000L;

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("w", Context.MODE_PRIVATE);
    }

    static void render(Context c, int id) {
        RemoteViews rv = new RemoteViews(c.getPackageName(), R.layout.widget);
        rv.setTextViewText(R.id.label, prefs(c).getString("n" + id, ""));
        Intent i = new Intent(c, TimestampWidget.class)
                .setAction(TICK)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
        // request code = widget id -> one pending intent per widget
        rv.setOnClickPendingIntent(R.id.label, PendingIntent.getBroadcast(c, id, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        AppWidgetManager.getInstance(c).updateAppWidget(id, rv);
    }

    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        for (int id : ids) render(c, id);
    }

    @Override
    public void onDeleted(Context c, int[] ids) {
        SharedPreferences.Editor e = prefs(c).edit();
        for (int id : ids) e.remove("u" + id).remove("n" + id).remove("t" + id);
        e.commit();
        wipe(c);
    }

    @Override
    public void onReceive(Context c, Intent i) {
        super.onReceive(c, i);
        if (TICK.equals(i.getAction())) {
            stamp(c, i.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 0));
            wipe(c);
        }
    }

    /*
     * One try block, one exit path. javac copies a finally body into every exit
     * of its try, so returns inside a try/finally are paid for in dex bytes.
     */
    private static void stamp(Context c, int id) {
        try {
            SharedPreferences p = prefs(c);
            String kt = "t" + id;
            long now = System.currentTimeMillis();
            long since = now - p.getLong(kt, 0L);
            // since < 0 means the clock moved backwards: accept and re-anchor
            if (since >= 0 && since < BLOCK_MS) return;
            String u = p.getString("u" + id, null);
            if (u == null) {
                toast(c, "Dosya yok");
                return;
            }
            String line = new SimpleDateFormat("yyyy.MM.dd HH.mm.ss", Locale.US)
                    .format(new Date(now));
            OutputStream o = c.getContentResolver().openOutputStream(Uri.parse(u), "wa");
            o.write((line + "\r\n").getBytes());
            o.close();
            // commit, not apply: the process is expected to die right after this
            p.edit().putLong(kt, now).commit();
            toast(c, line);
        } catch (Throwable t) {
            Log.w(TAG, t);   // nothing was written, so the tap stays retryable
            toast(c, "Yazılamadı");
        }
    }

    static void toast(Context c, String s) {
        Toast.makeText(c, s, Toast.LENGTH_SHORT).show();
    }

    /* Keeps Settings > Storage > Cache at zero: nothing here is ever reused. */
    static void wipe(Context c) {
        try {
            del(c.getCacheDir());
            del(c.getCodeCacheDir());
        } catch (Throwable t) {
            Log.w(TAG, t);
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
}
