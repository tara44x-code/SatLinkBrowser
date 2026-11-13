package com.satlink.geckoviewtest;

import android.content.Context;
import com.yausername.youtubedl_android.YoutubeDLRequest;

/** Matches SL.Tube DownloadRequestFactory.info + VrkaDownloadManager.analyze. */
final class SLTubeAnalysisRequest {
    private SLTubeAnalysisRequest() { }
    static YoutubeDLRequest create(Context context, String pageUrl) {
        YoutubeDLRequest request = new YoutubeDLRequest(pageUrl);
        request.addOption("--no-warnings");
        request.addOption("--legacy-server-connect");
        request.addOption("--no-playlist");
        EngineRuntimeOptions.apply(context, request);
        request.addOption("--dump-single-json");
        request.addOption("--skip-download");
        request.addOption("--socket-timeout", "20");
        request.addOption("--retries", "1");
        return request;
    }
}
