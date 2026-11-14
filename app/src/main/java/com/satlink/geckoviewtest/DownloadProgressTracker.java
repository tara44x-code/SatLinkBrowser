package com.satlink.geckoviewtest;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Overall progress of a yt-dlp download made of several files (video, then audio).
 * yt-dlp reports each file from 0 to 100%, so keeping the highest number seen made
 * the bar stick at 100% while the second file was still downloading. This adds the
 * finished files' sizes to the current file's progress. Pure Java.
 */
final class DownloadProgressTracker {
    private static final Pattern SIZE = Pattern.compile("of\\s+~?\\s*([0-9.]+\\s*[KMGT]?i?B)");
    private final long estimatedTotal;
    private final int expectedParts;
    private long finishedBytes;
    private int finishedParts;
    private long partSize;
    private double partPercent = -1;

    DownloadProgressTracker(long estimatedTotal, int expectedParts) {
        this.estimatedTotal = Math.max(0, estimatedTotal);
        this.expectedParts = Math.max(1, expectedParts);
    }

    /** One progress line: percent of the current file and the line yt-dlp printed. */
    void update(double percent, String line) {
        if (Double.isNaN(percent)) return;
        percent = Math.max(0, Math.min(100, percent));
        long size = parseSize(line);
        boolean newFile = partPercent >= 0 && (percent + 15 < partPercent
                || partPercent >= 99.5 && size > 0 && partSize > 0 && Math.abs(size - partSize) > partSize / 20);
        if (newFile) {
            finishedParts++;
            finishedBytes += partSize > 0 ? partSize : 0;
            partSize = 0;
        }
        if (size > 0) partSize = size;
        partPercent = percent;
    }

    long downloadedBytes() {
        return finishedBytes + (partSize > 0 ? Math.round(partSize * Math.max(0, partPercent) / 100d) : 0);
    }

    long totalBytes() {
        long known = finishedBytes + Math.max(0, partSize);
        return Math.max(estimatedTotal, known);
    }

    /** 0..99 while downloading; the caller sets 100 when the task completes. */
    int percent() {
        if (partPercent < 0) return 0;
        long total = totalBytes();
        double value;
        if (total > 0 && (partSize > 0 || finishedBytes > 0)) {
            value = downloadedBytes() * 100d / total;
        } else {
            int parts = Math.max(expectedParts, finishedParts + 1);
            value = (finishedParts * 100d + partPercent) / parts;
        }
        return (int) Math.max(0, Math.min(99, Math.floor(value)));
    }

    static long parseSize(String line) {
        if (line == null) return 0;
        Matcher matcher = SIZE.matcher(line);
        if (!matcher.find()) return 0;
        long size = TorrentSupport.size(matcher.group(1).replace(" ", ""));
        return Math.max(0, size);
    }
}
