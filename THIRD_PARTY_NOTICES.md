# Third-party components

SatLink Browser uses the components below. Check each project's own repository for the authoritative, current license text.

### 1. GeckoView
- **Project**: Mozilla GeckoView (`org.mozilla.geckoview:geckoview`)
- **License**: Mozilla Public License 2.0 (MPL-2.0)
- **Source**: https://mozilla.github.io/geckoview/

### 2. youtubedl-android
- **Project**: youtubedl-android (`io.github.junkfood02.youtubedl-android:library:0.18.1`)
- **License**: GNU General Public License v3.0 (GPL-3.0)
- **Source**: https://github.com/yausername/youtubedl-android

Because this library is GPL-3.0, the application as a whole must be distributed under terms compatible with GPL-3.0.

### 3. yt-dlp
- **Project**: yt-dlp, bundled unmodified as `app/src/main/res/raw/ytdlp` (version 2026.08.19), including its vendored EJS components
- **License**: The Unlicense; bundled dependencies carry their own licenses
- **Source**: https://github.com/yt-dlp/yt-dlp and https://github.com/yt-dlp/ejs

### 4. FFmpeg
- **Project**: FFmpeg (`io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1`)
- **License**: LGPL-2.1+ or GPL depending on the build configuration of the packaged binaries; see the youtubedl-android project
- **Source**: https://ffmpeg.org/

### 5. Python and QuickJS
- Packaged inside youtubedl-android. See that project for their licenses (Python Software Foundation License; QuickJS is MIT).
