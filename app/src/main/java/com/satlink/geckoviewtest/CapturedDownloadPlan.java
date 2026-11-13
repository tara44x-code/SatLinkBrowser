package com.satlink.geckoviewtest;

import java.net.URI;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Creates yt-dlp info data from actual browser streams; never runs FFmpeg directly. */
final class CapturedDownloadPlan {
    private CapturedDownloadPlan() { }
    private static boolean empty(String value) { return value == null || value.isEmpty(); }

    static Map<String, Object> create(String id, String title, String url, String audioUrl,
            String formatId, String outputExt, String videoCodec, String audioCodec,
            boolean hasAudio, boolean audioOnly, String userAgent, String referer) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (!empty(userAgent)) headers.put("User-Agent", userAgent);
        if (!empty(referer)) headers.put("Referer", referer);
        List<Map<String, Object>> formats = new ArrayList<>();
        formats.add(format(empty(formatId) ? "captured-video" : formatId, url,
                audioOnly ? audioExtension(url) : videoExtension(url, outputExt),
                audioOnly ? "none" : codec(videoCodec, "unknown"),
                audioOnly || hasAudio ? codec(audioCodec, "unknown") : "none", headers));
        if (!audioOnly && !hasAudio && !empty(audioUrl)) {
            formats.add(format("captured-audio", audioUrl, audioExtension(audioUrl),
                    "none", codec(audioCodec, "unknown"), headers));
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("_type", "video"); root.put("id", id);
        root.put("title", empty(title) ? "SatLink" : title);
        root.put("extractor", "generic"); root.put("extractor_key", "Generic");
        root.put("formats", formats);
        return root;
    }

    private static Map<String, Object> format(String id, String url, String ext,
            String vcodec, String acodec, Map<String, String> headers) {
        URI uri = URI.create(url);
        String scheme = uri.getScheme();
        if (!("http".equals(scheme) || "https".equals(scheme)) || uri.getHost() == null)
            throw new IllegalArgumentException("Captured media must have an HTTP URL");
        String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase();
        Map<String, Object> format = new LinkedHashMap<>();
        format.put("format_id", id); format.put("url", url);
        format.put("ext", empty(ext) ? "mp4" : ext);
        format.put("protocol", path.endsWith(".m3u8") ? "m3u8_native"
                : path.endsWith(".mpd") ? "http_dash_segments" : scheme);
        format.put("vcodec", vcodec); format.put("acodec", acodec);
        format.put("http_headers", headers);
        return format;
    }

    private static String videoExtension(String url, String outputExt) {
        String path=URI.create(url).getPath();
        if (path != null) {
            for (String ext : new String[]{"mp4", "webm", "mkv", "ts", "mov", "m4v"}) {
                if (path.toLowerCase().endsWith("."+ext)) return ext;
            }
        }
        if (url.contains("video%2Fwebm") || url.contains("video/webm")) return "webm";
        return "mp3".equals(outputExt) ? "mp4" : outputExt;
    }

    private static String audioExtension(String url) {
        URI uri=URI.create(url);
        String mime="";
        for (String param : (uri.getRawQuery()==null ? "" : uri.getRawQuery()).split("&")) {
            String[] pair=param.split("=",2);
            if (pair.length==2 && pair[0].equals("mime")) {
                try { mime=URLDecoder.decode(pair[1], "UTF-8"); }
                catch (java.io.UnsupportedEncodingException ignored) { }
            }
        }
        String path=uri.getPath()==null ? "" : uri.getPath().toLowerCase();
        if(mime.contains("webm") || path.endsWith(".webm")) return "webm";
        if(mime.contains("opus") || path.endsWith(".opus")) return "opus";
        if(mime.contains("mpeg") || path.endsWith(".mp3")) return "mp3";
        if(path.endsWith(".ogg")) return "ogg";
        return "m4a";
    }

    private static String codec(String value, String fallback) {
        if(empty(value)) return fallback;
        if(value.equalsIgnoreCase("H.264")) return "avc1";
        if(value.equalsIgnoreCase("H.265")) return "hevc";
        if(value.equalsIgnoreCase("AAC")) return "mp4a.40.2";
        return value.toLowerCase();
    }
}
