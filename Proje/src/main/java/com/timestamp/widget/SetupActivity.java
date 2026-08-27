package com.timestamp.widget;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.Log;

/*
 * Widget configuration activity, translucent and without a UI of its own:
 * dropping the widget opens the system create-document dialog straight away and
 * this finishes as soon as the dialog returns.
 *
 * Cancelling leaves RESULT_CANCELED, so the launcher drops the half placed
 * widget instead of leaving one behind with no file under it.
 */
public class SetupActivity extends Activity {

    private int id;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setResult(RESULT_CANCELED);
        id = getIntent().getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 0);  // 0 = INVALID
        if (id == 0) {
            finish();
            return;
        }
        if (s != null) return;   // recreated while the dialog is up: do not stack a second one
        try {
            startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_TITLE, "zaman-damgasi.txt")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION), 1);
        } catch (Throwable t) {
            Log.w(TimestampWidget.TAG, t);
            TimestampWidget.toast(this, "Dosya penceresi açılamadı");
            finish();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        Uri u = (res == RESULT_OK && data != null) ? data.getData() : null;
        if (u != null) {
            try {
                getContentResolver().takePersistableUriPermission(u,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                TimestampWidget.prefs(this).edit()
                        .putString("u" + id, u.toString())
                        .putString("n" + id, name(u))
                        .remove("t" + id)
                        .commit();
                TimestampWidget.render(this, id);
                setResult(RESULT_OK, new Intent()
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id));
            } catch (Throwable t) {
                Log.w(TimestampWidget.TAG, t);   // stays CANCELED, the widget is not placed
                TimestampWidget.toast(this, "Widget kurulamadı");
            }
        }
        finish();
    }

    /** What the user typed in the dialog: display name without a trailing ".txt". */
    private String name(Uri u) {
        String n = null;
        Cursor c = getContentResolver().query(u, null, null, null, null);
        if (c != null) {
            // No try/finally here: javac would copy the close into every exit of
            // the block, and the caller's catch already covers a query blowing up.
            int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
            if (i >= 0 && c.moveToFirst()) n = c.getString(i);
            c.close();
        }
        if (n == null) n = u.getLastPathSegment();      // e.g. "primary:Notes/log.txt"
        if (n == null) return "";
        int slash = n.lastIndexOf('/');
        if (slash >= 0) n = n.substring(slash + 1);
        return n.endsWith(".txt") ? n.substring(0, n.length() - 4) : n;
    }

    @Override
    protected void onStop() {
        super.onStop();
        TimestampWidget.wipe(this);
    }
}
