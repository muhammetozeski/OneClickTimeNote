package com.timestamp.widget;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.Log;
import android.widget.Toast;

/*
 * Widget configuration activity. It has no UI of its own (translucent theme):
 * dropping the widget opens the system "create document" dialog straight away,
 * and the activity finishes as soon as the dialog returns.
 *
 * Cancelling the dialog leaves RESULT_CANCELED, so the launcher drops the
 * half placed widget instead of leaving one without a file behind it.
 */
public class SetupActivity extends Activity {

    private static final int REQ_CREATE = 1;

    private int widgetId = AppWidgetManager.INVALID_APPWIDGET_ID;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setResult(RESULT_CANCELED);

        Bundle extras = getIntent() == null ? null : getIntent().getExtras();
        if (extras != null) {
            widgetId = extras.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID,
                    AppWidgetManager.INVALID_APPWIDGET_ID);
        }
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            Log.w(TimestampWidget.TAG, "setup started without a widget id");
            finish();
            return;
        }
        if (state != null) return;   // recreated while the dialog is up: do not stack a second one

        Intent create = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        create.addCategory(Intent.CATEGORY_OPENABLE);
        create.setType("text/plain");
        create.putExtra(Intent.EXTRA_TITLE, getString(R.string.default_file));
        create.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(create, REQ_CREATE);
        } catch (Throwable t) {
            Log.e(TimestampWidget.TAG, "no document picker on this device", t);
            try {
                Toast.makeText(this, R.string.no_picker, Toast.LENGTH_LONG).show();
            } catch (Throwable inner) {
                Log.w(TimestampWidget.TAG, "toast failed", inner);
            } finally {
                finish();
            }
        }
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        if (req != REQ_CREATE) {
            super.onActivityResult(req, result, data);
            return;
        }
        Uri uri = (result == RESULT_OK && data != null) ? data.getData() : null;
        if (uri == null) {
            finish();   // cancelled - the widget is never placed
            return;
        }
        try {
            getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Throwable t) {
            // The grant still holds for now; it just may not survive a reboot.
            Log.w(TimestampWidget.TAG, "could not persist uri permission", t);
        }

        String label = labelFor(uri);
        try {
            TimestampWidget.prefs(this).edit()
                    .putString(TimestampWidget.KEY_URI + widgetId, uri.toString())
                    .putString(TimestampWidget.KEY_NAME + widgetId, label)
                    .remove(TimestampWidget.KEY_LAST + widgetId)
                    .commit();
            TimestampWidget.render(this, AppWidgetManager.getInstance(this), widgetId);
            setResult(RESULT_OK, new Intent()
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId));
        } catch (Throwable t) {
            Log.e(TimestampWidget.TAG, "could not store the widget setup", t);
            try {
                Toast.makeText(this, R.string.setup_failed, Toast.LENGTH_LONG).show();
            } catch (Throwable inner) {
                Log.w(TimestampWidget.TAG, "toast failed", inner);
            }
        } finally {
            finish();
        }
    }

    /** Name the user typed in the dialog: display name minus a trailing ".txt". */
    private String labelFor(Uri uri) {
        String name = null;
        Cursor cur = null;
        try {
            cur = getContentResolver().query(uri,
                    new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (cur != null && cur.moveToFirst()) {
                int col = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (col >= 0) name = cur.getString(col);
            }
        } catch (Throwable t) {
            Log.w(TimestampWidget.TAG, "display name query failed", t);
        } finally {
            if (cur != null) {
                try {
                    cur.close();
                } catch (Throwable t) {
                    Log.w(TimestampWidget.TAG, "cursor close failed", t);
                }
            }
        }
        if (name == null || name.length() == 0) {
            String seg = uri.getLastPathSegment();          // fallback: ".../documents/primary:Notes/log.txt"
            if (seg != null) {
                int slash = seg.lastIndexOf('/');
                name = slash >= 0 ? seg.substring(slash + 1) : seg;
            }
        }
        if (name == null || name.length() == 0) return getString(R.string.no_file);
        if (name.length() > 4 && name.regionMatches(true, name.length() - 4, ".txt", 0, 4)) {
            name = name.substring(0, name.length() - 4);
        }
        return name;
    }

    @Override
    protected void onStop() {
        super.onStop();
        TimestampWidget.wipeCache(this);
    }
}
