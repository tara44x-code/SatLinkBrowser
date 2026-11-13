package com.satlink.geckoviewtest;

import android.content.Context;
import android.util.Log;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import com.yausername.ffmpeg.FFmpeg;
import java.io.File;
import java.util.Collections;

/** Shared bootstrap for capture and the foreground download service. */
final class EngineBootstrap {
    private static boolean ready;
    private EngineBootstrap() { }

    static synchronized void initialize(Context context) throws Exception {
        if (ready) return;
        YoutubeDL.getInstance().init(context);
        FFmpeg.getInstance().init(context);
        String current;
        try { current = version(); } catch (Exception error) { current = ""; }
        File engine = new File(context.getNoBackupFilesDir(), "youtubedl-android/yt-dlp/yt-dlp");
        boolean installed = BundledEngineInstaller.install(engine, context.getResources().openRawResource(R.raw.ytdlp),
                current, EngineBootstrap::version);
        Log.i("SatLinkEngine", "yt-dlp " + (installed ? BundledEngineInstaller.VERSION : current) + "; QuickJS native runtime configured per request");
        ready = true;
    }

    private static String version() throws Exception {
        YoutubeDLRequest request = new YoutubeDLRequest(Collections.<String>emptyList());
        request.addOption("--ignore-config");
        request.addOption("--version");
        return YoutubeDL.getInstance().execute(request).getOut().trim();
    }
}
