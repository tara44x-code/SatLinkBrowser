package com.satlink.geckoviewtest;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Saves a response body that only the page can supply (blob:, data: and similar downloads)
 * into Downloads/SatLink. Plain http/https files keep using Android's download manager.
 */
final class StreamFileSaver {
    private StreamFileSaver() { }

    /** Blocking; call from a background thread. Returns the stored file name. */
    static String save(Context context, InputStream body, String requestedName, String mime) throws IOException {
        String name = GeneralFilePolicy.safeName(requestedName);
        try (InputStream in = body) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return saveScoped(context, in, name, mime);
            return saveLegacy(in, name);
        }
    }

    private static String saveScoped(Context context, InputStream in, String name, String mime) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, name);
        values.put(MediaStore.Downloads.MIME_TYPE, mime == null || mime.isEmpty() ? "application/octet-stream" : mime);
        values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/SatLink");
        values.put(MediaStore.Downloads.IS_PENDING, 1);
        Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IOException("Cannot create the download entry");
        try {
            try (OutputStream out = resolver.openOutputStream(uri)) {
                if (out == null) throw new IOException("Cannot open the download entry");
                copy(in, out);
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.Downloads.IS_PENDING, 0);
            resolver.update(uri, done, null, null);
            return name;
        } catch (IOException | RuntimeException error) {
            try { resolver.delete(uri, null, null); } catch (RuntimeException ignored) { /* entry already gone */ }
            throw error;
        }
    }

    private static String saveLegacy(InputStream in, String name) throws IOException {
        File folder = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "SatLink");
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("Cannot create Downloads/SatLink");
        File target = uniqueFile(folder, name);
        try (OutputStream out = new FileOutputStream(target)) {
            copy(in, out);
        } catch (IOException | RuntimeException error) {
            if (target.exists() && !target.delete()) target.deleteOnExit();
            throw error;
        }
        return target.getName();
    }

    static File uniqueFile(File folder, String name) {
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String suffix = dot > 0 ? name.substring(dot) : "";
        File candidate = new File(folder, name);
        for (int index = 1; candidate.exists() && index < 10000; index++)
            candidate = new File(folder, stem + " (" + index + ")" + suffix);
        return candidate;
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int count;
        while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
        out.flush();
    }
}
