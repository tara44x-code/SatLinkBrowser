package com.satlink.geckoviewtest;

import android.app.DownloadManager;
import android.database.Cursor;
import android.net.Uri;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Read the app's native downloads, including existing requests and unfinished files. */
final class GeneralDownloadCatalog {
    static final class Entry {
        long id, downloaded, total, modified;
        int status, reason;
        String name, mime, localUri;
        boolean complete() {return status == DownloadManager.STATUS_SUCCESSFUL;}
        boolean active() {return status == DownloadManager.STATUS_PENDING
                || status == DownloadManager.STATUS_RUNNING || status == DownloadManager.STATUS_PAUSED;}
        int percent() {
            if (total <= 0) return -1;
            return (int)Math.min(100d, Math.max(0d, (double)downloaded / (double)total * 100d));
        }
        String state() {
            switch(status) {
                case DownloadManager.STATUS_PENDING: return "بانتظار بدء التنزيل";
                case DownloadManager.STATUS_RUNNING: return "جاري التنزيل";
                case DownloadManager.STATUS_PAUSED: return "متوقف مؤقتاً — " + reason;
                case DownloadManager.STATUS_SUCCESSFUL: return "اكتمل التنزيل";
                case DownloadManager.STATUS_FAILED: return "فشل التنزيل — رمز " + reason;
                default: return "حالة التنزيل: " + status;
            }
        }
    }

    static List<Entry> read(DownloadManager manager) {
        List<Entry> entries = new ArrayList<>();
        if (manager == null) return entries;
        // Query is scoped by DownloadManager to requests owned by this app's UID.
        // No status/extension filter: APK/ISO/ZIP/etc and previously queued downloads appear.
        try (Cursor cursor = manager.query(new DownloadManager.Query())) {
            if (cursor == null) return entries;
            while (cursor.moveToNext()) {
                Entry item = new Entry();
                item.id = number(cursor, DownloadManager.COLUMN_ID);
                item.name = string(cursor, DownloadManager.COLUMN_TITLE);
                item.mime = string(cursor, DownloadManager.COLUMN_MEDIA_TYPE);
                item.localUri = string(cursor, DownloadManager.COLUMN_LOCAL_URI);
                item.downloaded = number(cursor, DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR);
                item.total = number(cursor, DownloadManager.COLUMN_TOTAL_SIZE_BYTES);
                item.status = (int)number(cursor, DownloadManager.COLUMN_STATUS);
                item.reason = (int)number(cursor, DownloadManager.COLUMN_REASON);
                item.modified = number(cursor, DownloadManager.COLUMN_LAST_MODIFIED_TIMESTAMP);
                if (item.name.isEmpty()) {
                    String last = Uri.parse(item.localUri).getLastPathSegment();
                    item.name = last == null || last.isEmpty() ? "ملف " + item.id : last;
                }
                entries.add(item);
            }
        }
        entries.sort((a,b) -> Long.compare(b.modified,a.modified));
        return entries;
    }

    static Set<String> representedNames(List<Entry> entries) {
        Set<String> names = new HashSet<>();
        for (Entry item : entries) {
            // A native status card also represents any indexed partial file at this destination.
            Uri uri = Uri.parse(item.localUri);
            String path = uri.getPath();
            if (path != null && path.contains("/Download/SatLink/")) {
                String name = uri.getLastPathSegment();
                if (name != null) names.add(name);
            }
        }
        return names;
    }

    static Uri openUri(DownloadManager manager, Entry item) {
        return manager == null || !item.complete() ? null : manager.getUriForDownloadedFile(item.id);
    }

    static boolean remove(DownloadManager manager, Entry item) {
        return manager != null && manager.remove(item.id) > 0;
    }

    private static String string(Cursor c,String name) {
        int index = c.getColumnIndex(name);
        String value = index < 0 ? "" : c.getString(index);
        return value == null ? "" : value;
    }
    private static long number(Cursor c,String name) {
        int index = c.getColumnIndex(name);
        return index < 0 ? -1L : c.getLong(index);
    }
}
