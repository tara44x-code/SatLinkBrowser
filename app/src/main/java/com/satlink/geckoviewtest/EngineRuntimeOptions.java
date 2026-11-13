package com.satlink.geckoviewtest;

import android.content.Context;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import java.io.File;

/** The packaged QuickJS binary is an extracted native executable, not a PATH command named qjs. */
final class EngineRuntimeOptions {
    private EngineRuntimeOptions() { }

    static void apply(Context context, YoutubeDLRequest request) {
        File quickJs = new File(context.getApplicationInfo().nativeLibraryDir, "libqjs.so");
        if (quickJs.isFile() && quickJs.canExecute()) {
            request.addOption("--js-runtimes", "quickjs:" + quickJs.getAbsolutePath());
        }
        // SL.Tube enables matching EJS components independently of runtime discovery.
        request.addOption("--remote-components", "ejs:github");
    }
}
