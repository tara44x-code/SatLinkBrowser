package com.satlink.geckoviewtest;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Multi-connection HTTP/HTTPS file downloader (up to 32 connections).
 *
 * The file is split into byte ranges that are fetched in parallel and written at their own
 * offsets. When a connection finishes early it takes half of the largest remaining range, so
 * every connection stays busy until the end. Progress is saved as a small text state so a paused
 * or interrupted download continues where it stopped. Servers without range support fall back to
 * one connection. Pure Java: no Android classes, so it runs in the unit tests too.
 */
final class SegmentedDownloader {
    static final int MAX_CONNECTIONS = 32;
    static final long MIN_SPLIT_BYTES = 512L * 1024L;
    private static final int BUFFER = 64 * 1024;
    private static final int MAX_ATTEMPTS = 8;
    private static final int MAX_REDIRECTS = 8;
    private static final Pattern CONTENT_RANGE = Pattern.compile("bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)", Pattern.CASE_INSENSITIVE);

    interface Listener {
        /** Called about once per second from a worker thread. */
        void onProgress(long downloaded, long total, int activeConnections);
        /** A compact resume state; store it and pass it back to resume. */
        void onState(String state);
    }

    /** Thrown for problems that a retry cannot fix (HTTP 4xx, changed file, no space...). */
    static final class FatalException extends IOException {
        FatalException(String message) { super(message); }
    }

    static final class Result {
        final long total;
        final boolean complete;
        Result(long total, boolean complete) { this.total = total; this.complete = complete; }
    }

    private static final class Segment {
        final long start;
        long pos;
        long end; // inclusive; -1 when the total size is unknown
        boolean owned;
        Segment(long start, long pos, long end) { this.start = start; this.pos = pos; this.end = end; }
        long remaining() { return end < 0 ? Long.MAX_VALUE : Math.max(0, end - pos + 1); }
    }

    private final String url;
    private final Map<String, String> headers;
    private final int connections;
    private final Listener listener;
    private final Object lock = new Object();
    private final List<Segment> segments = new ArrayList<>();
    private final List<HttpURLConnection> open = new ArrayList<>();
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final AtomicInteger active = new AtomicInteger();
    private volatile IOException failure;
    private volatile String finalUrl;
    private long total = -1;
    private boolean ranges;
    int connectTimeoutMs = 20000;
    int readTimeoutMs = 30000;
    long retryBaseDelayMs = 1000L;

    SegmentedDownloader(String url, Map<String, String> headers, int connections, Listener listener) {
        this.url = url;
        this.headers = headers == null ? new LinkedHashMap<>() : new LinkedHashMap<>(headers);
        this.connections = Math.max(1, Math.min(MAX_CONNECTIONS, connections));
        this.listener = listener;
    }

    /** Stops all connections; download() then returns with complete=false and the saved state. */
    void stop() {
        stopped.set(true);
        synchronized (lock) {
            for (HttpURLConnection connection : open) {
                try { connection.disconnect(); } catch (RuntimeException ignored) { /* already closed */ }
            }
        }
    }

    boolean isStopped() { return stopped.get(); }

    /**
     * Downloads into the channel, which must allow positional writes. Blocks until the file is
     * complete, stop() is called, or a fatal error occurs (thrown).
     */
    Result download(FileChannel channel, String resumeState) throws IOException, InterruptedException {
        Probe probe = probe();
        total = probe.total;
        ranges = probe.ranges && probe.total > 0;
        finalUrl = probe.url;
        if (total == 0) {
            channel.truncate(0);
            return new Result(0, true);
        }
        boolean resumed = ranges && restore(resumeState, probe.total);
        if (resumed && channel.size() > total) channel.truncate(total);
        if (!resumed) {
            // Previous partial data cannot be trusted for a fresh start.
            channel.truncate(0);
            segments.clear();
            if (!ranges) {
                segments.add(new Segment(0, 0, total > 0 ? total - 1 : -1));
            } else {
                long count = Math.max(1, Math.min(connections, total / MIN_SPLIT_BYTES));
                long size = total / count;
                for (int i = 0; i < count; i++) {
                    long start = i * size;
                    long end = i == count - 1 ? total - 1 : start + size - 1;
                    segments.add(new Segment(start, start, end));
                }
            }
        }
        saveState();
        if (ranges && downloaded() >= total) return new Result(total, true);

        int workers = ranges ? connections : 1;
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < workers; i++) {
            Thread thread = new Thread(() -> work(channel), "satlink-segment-" + i);
            thread.setDaemon(true);
            threads.add(thread);
            thread.start();
        }
        long lastReport = 0;
        while (true) {
            boolean alive = false;
            for (Thread thread : threads) if (thread.isAlive()) { alive = true; break; }
            long now = System.currentTimeMillis();
            if (now - lastReport >= 1000L || !alive) {
                lastReport = now;
                saveState();
                if (listener != null) listener.onProgress(downloaded(), total, active.get());
            }
            if (!alive) break;
            if (failure != null) stop();
            Thread.sleep(100L);
        }
        for (Thread thread : threads) thread.join();
        try { channel.force(false); } catch (IOException ignored) { /* best effort flush */ }
        saveState();
        IOException error = failure;
        if (error != null) throw error;
        boolean complete;
        synchronized (lock) { complete = allDone(); }
        if (complete && total <= 0) total = downloaded();
        if (complete && downloaded() != total) throw new IOException("Downloaded size does not match the file size");
        return new Result(total, complete);
    }

    long downloaded() {
        synchronized (lock) {
            long sum = 0;
            for (Segment segment : segments) sum += segment.pos - segment.start;
            return sum;
        }
    }

    long total() { return total; }

    String state() {
        synchronized (lock) {
            StringBuilder out = new StringBuilder("satlink-v1\n").append(total).append('\n');
            for (Segment segment : segments)
                out.append(segment.start).append(' ').append(segment.pos).append(' ').append(segment.end).append('\n');
            return out.toString();
        }
    }

    private void saveState() {
        if (listener != null) listener.onState(state());
    }

    private boolean restore(String state, long expectedTotal) {
        if (state == null || !state.startsWith("satlink-v1\n")) return false;
        try {
            String[] lines = state.split("\n");
            if (Long.parseLong(lines[1].trim()) != expectedTotal) return false; // file changed on the server
            List<Segment> restored = new ArrayList<>();
            for (int i = 2; i < lines.length; i++) {
                if (lines[i].trim().isEmpty()) continue;
                String[] parts = lines[i].trim().split(" ");
                long start = Long.parseLong(parts[0]), pos = Long.parseLong(parts[1]), end = Long.parseLong(parts[2]);
                if (start < 0 || pos < start || end >= expectedTotal || pos > end + 1) return false;
                restored.add(new Segment(start, pos, end));
            }
            if (restored.isEmpty()) return false;
            segments.clear();
            segments.addAll(restored);
            return true;
        } catch (RuntimeException error) {
            return false;
        }
    }

    private boolean allDone() {
        for (Segment segment : segments) if (segment.end < 0 || segment.pos <= segment.end) return false;
        return true;
    }

    /** Next range for an idle connection: an unowned range, or half of the largest busy one. */
    private Segment claim() {
        synchronized (lock) {
            if (stopped.get()) return null;
            for (Segment segment : segments) {
                if (!segment.owned && segment.remaining() > 0) { segment.owned = true; return segment; }
            }
            if (!ranges) return null;
            Segment largest = null;
            for (Segment segment : segments)
                if (segment.owned && (largest == null || segment.remaining() > largest.remaining())) largest = segment;
            if (largest == null || largest.remaining() < 2 * MIN_SPLIT_BYTES) return null;
            long middle = largest.pos + largest.remaining() / 2;
            Segment taken = new Segment(middle, middle, largest.end);
            largest.end = middle - 1;
            taken.owned = true;
            segments.add(taken);
            return taken;
        }
    }

    private void work(FileChannel channel) {
        Segment segment;
        while ((segment = claim()) != null) {
            int attempt = 0;
            while (!stopped.get()) {
                try {
                    fetch(segment, channel);
                    break;
                } catch (StopSignal stop) {
                    return;
                } catch (FatalException fatal) {
                    fail(fatal);
                    return;
                } catch (IOException error) {
                    if (stopped.get()) return;
                    if (!ranges && segment.pos > 0) {
                        // A single stream cannot continue mid-file without ranges.
                        fail(new FatalException("Connection lost and the server does not support resuming"));
                        return;
                    }
                    if (++attempt >= MAX_ATTEMPTS) { fail(error); return; }
                    sleep(retryBaseDelayMs * Math.min(16, 1L << (attempt - 1)));
                }
            }
        }
    }

    private void fail(IOException error) {
        if (failure == null) failure = error;
        stop();
    }

    private void sleep(long ms) {
        long until = System.currentTimeMillis() + ms;
        while (!stopped.get() && System.currentTimeMillis() < until) {
            try { Thread.sleep(50L); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
        }
    }

    private static final class StopSignal extends IOException {
        StopSignal() { super("stopped"); }
    }

    private void fetch(Segment segment, FileChannel channel) throws IOException {
        long from, to;
        synchronized (lock) { from = segment.pos; to = segment.end; }
        if (to >= 0 && from > to) return;
        HttpURLConnection connection = connect(finalUrl, ranges ? "bytes=" + from + "-" + to : null);
        synchronized (lock) {
            if (stopped.get()) { connection.disconnect(); throw new StopSignal(); }
            open.add(connection);
        }
        active.incrementAndGet();
        try {
            int code = connection.getResponseCode();
            if (ranges && code != HttpURLConnection.HTTP_PARTIAL) {
                if (code >= 400 && code < 500 && code != 408 && code != 429)
                    throw new FatalException("HTTP " + code);
                throw new IOException("Expected a partial response, got HTTP " + code);
            }
            if (!ranges && code != HttpURLConnection.HTTP_OK) {
                if (code >= 400 && code < 500 && code != 408 && code != 429)
                    throw new FatalException("HTTP " + code);
                throw new IOException("HTTP " + code);
            }
            if (ranges) {
                Matcher range = CONTENT_RANGE.matcher(String.valueOf(connection.getHeaderField("Content-Range")));
                if (!range.find() || Long.parseLong(range.group(1)) != from)
                    throw new IOException("Server returned a different range");
            }
            byte[] buffer = new byte[BUFFER];
            try (InputStream in = connection.getInputStream()) {
                while (true) {
                    if (stopped.get()) throw new StopSignal();
                    int read = in.read(buffer);
                    if (read < 0) break;
                    long position, allowed;
                    synchronized (lock) {
                        position = segment.pos;
                        allowed = segment.end < 0 ? read : Math.min(read, segment.end - segment.pos + 1);
                    }
                    if (allowed <= 0) break; // another connection took over the rest of this range
                    ByteBuffer data = ByteBuffer.wrap(buffer, 0, (int) allowed);
                    long at = position;
                    while (data.hasRemaining()) at += channel.write(data, at);
                    synchronized (lock) { segment.pos = position + allowed; }
                    if (allowed < read) break;
                }
            }
            synchronized (lock) {
                if (segment.end >= 0 && segment.pos <= segment.end) throw new IOException("Connection closed early");
                if (segment.end < 0) segment.end = segment.pos - 1; // unknown size: stream ended
            }
        } catch (IOException error) {
            if (stopped.get() && !(error instanceof FatalException)) throw new StopSignal();
            throw error;
        } finally {
            active.decrementAndGet();
            synchronized (lock) { open.remove(connection); }
            connection.disconnect();
        }
    }

    private static final class Probe {
        long total = -1;
        boolean ranges;
        String url;
    }

    private Probe probe() throws IOException {
        HttpURLConnection connection = connect(url, "bytes=0-0");
        try {
            int code = connection.getResponseCode();
            Probe probe = new Probe();
            probe.url = connection.getURL().toString();
            if (code == HttpURLConnection.HTTP_PARTIAL) {
                Matcher range = CONTENT_RANGE.matcher(String.valueOf(connection.getHeaderField("Content-Range")));
                if (range.find() && !"*".equals(range.group(3))) {
                    probe.total = Long.parseLong(range.group(3));
                    probe.ranges = true;
                }
            } else if (code == HttpURLConnection.HTTP_OK) {
                probe.total = connection.getContentLengthLong();
            } else if (code == 416) {
                probe.total = 0;
            } else if (code >= 400 && code < 500 && code != 408 && code != 429) {
                throw new FatalException("HTTP " + code);
            } else {
                throw new IOException("HTTP " + code);
            }
            return probe;
        } finally {
            connection.disconnect();
        }
    }

    /** Opens a connection, following redirects across http and https. */
    private HttpURLConnection connect(String target, String range) throws IOException {
        String current = target;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            URL address = new URL(current);
            String protocol = address.getProtocol().toLowerCase(Locale.ROOT);
            if (!protocol.equals("http") && !protocol.equals("https")) throw new FatalException("Unsupported link");
            HttpURLConnection connection = (HttpURLConnection) address.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(connectTimeoutMs);
            connection.setReadTimeout(readTimeoutMs);
            connection.setRequestProperty("Accept-Encoding", "identity");
            for (Map.Entry<String, String> header : headers.entrySet()) {
                if (header.getValue() != null && !header.getValue().isEmpty())
                    connection.setRequestProperty(header.getKey(), header.getValue());
            }
            if (range != null) connection.setRequestProperty("Range", range);
            int code = connection.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null || location.isEmpty()) throw new IOException("Redirect without a location");
                current = new URL(address, location).toString();
                continue;
            }
            return connection;
        }
        throw new IOException("Too many redirects");
    }
}
