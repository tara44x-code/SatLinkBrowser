package com.satlink.geckoviewtest;

import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps junk out of the captured-video list: tiny files (tracking pixels, ad
 * placeholders), web pages and images that a page fetched with a video-like
 * address, and ranks player frames so real video hosts are analysed before ads.
 * Pure Java, so the rules run in the unit tests.
 */
final class CapturedMediaFilter {
    private CapturedMediaFilter() { }

    /** Smaller than this is never a watchable video or song file. */
    static final long MIN_MEDIA_BYTES = 256L * 1024L;
    /** Probe answers: not media. */
    static final long JUNK = -1L;
    /** Probe answers: could not tell (kept, shown without a size). */
    static final long UNKNOWN = 0L;

    private static final Pattern CONTENT_RANGE = Pattern.compile("/(\\d+)\\s*$");

    static boolean manifest(String url, String mime) {
        String u = url == null ? "" : url.toLowerCase(Locale.ROOT);
        String m = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
        return u.contains(".m3u8") || u.contains(".mpd") || m.contains("mpegurl") || m.contains("dash+xml");
    }

    static boolean tooSmall(long size) {
        return size > 0 && size < MIN_MEDIA_BYTES;
    }

    /** Verdict from a response's type and total length. */
    static long verdict(String contentType, long total) {
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (type.startsWith("text/") || type.contains("json") || type.startsWith("image/")
                || type.contains("javascript") || type.contains("xml") && !type.contains("dash")) return JUNK;
        if (tooSmall(total)) return JUNK;
        return total > 0 ? total : UNKNOWN;
    }

    /** Asks the server for one byte to learn the file's real size and type. Blocking. */
    static long probe(String url, Map<String, String> headers) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(8000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("Range", "bytes=0-0");
            connection.setRequestProperty("Accept-Encoding", "identity");
            if (headers != null) for (Map.Entry<String, String> header : headers.entrySet())
                if (header.getValue() != null && !header.getValue().isEmpty())
                    connection.setRequestProperty(header.getKey(), header.getValue());
            int code = connection.getResponseCode();
            if (code == 404 || code == 410) return JUNK;
            if (code != 200 && code != 206) return UNKNOWN;
            long total = -1;
            if (code == 206) {
                String range = connection.getHeaderField("Content-Range");
                Matcher matcher = CONTENT_RANGE.matcher(range == null ? "" : range);
                if (matcher.find()) total = Long.parseLong(matcher.group(1));
            } else {
                total = connection.getContentLengthLong();
            }
            return verdict(connection.getContentType(), total);
        } catch (Exception error) {
            return UNKNOWN;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /** Hosts that serve embedded movie/series players and that yt-dlp knows. */
    private static final String[] PLAYER_HOSTS = {
            "streamwish", "wishfast", "wishembed", "vidhide", "filemoon", "voe.", "dood", "d0000d",
            "mixdrop", "streamtape", "uqload", "vidmoly", "ok.ru", "okstream", "upstream", "updown",
            "videotube", "streamruby", "hgcloud", "mp4upload", "vidguard", "lulustream", "vidoza",
            "dailymotion", "vk.com", "vkvideo", "player.vimeo", "earnvids", "savefiles", "luluvdo",
    };

    /**
     * Orders captured frame pages for analysis: known player hosts first, then other
     * hosts that look like players, newest first among equals. Ads and the page
     * itself are dropped. [framesNewestFirst] must already exclude obvious ads.
     */
    static List<String> rankPlayerFrames(List<String> framesNewestFirst, String pageUrl, int limit) {
        String pageHost = host(pageUrl);
        List<String> scored = new ArrayList<>();
        List<Integer> scores = new ArrayList<>();
        for (String url : framesNewestFirst) {
            if (url == null || !url.startsWith("https://") || url.equals(pageUrl) || scored.contains(url)) continue;
            String lower = url.toLowerCase(Locale.ROOT);
            int score = 0;
            for (String known : PLAYER_HOSTS) if (lower.contains(known)) { score += 10; break; }
            String frameHost = host(url);
            if (!frameHost.isEmpty() && !frameHost.equals(pageHost)) score += 3;
            if (lower.contains("/embed") || lower.contains("/e/") || lower.contains("/v/")
                    || lower.contains("player") || lower.contains("/watch")) score += 2;
            // Stable insertion: higher score first, earlier (newer) first among equals.
            int at = 0;
            while (at < scores.size() && scores.get(at) >= score) at++;
            scored.add(at, url);
            scores.add(at, score);
        }
        return scored.size() > limit ? new ArrayList<>(scored.subList(0, limit)) : scored;
    }

    private static String host(String url) {
        try {
            String host = new URI(url).getHost();
            return host == null ? "" : host.toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "");
        } catch (Exception error) {
            return "";
        }
    }
}
