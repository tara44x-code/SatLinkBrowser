package com.satlink.geckoviewtest;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Downloads general files with up to 32 connections and torrents/magnets through aria2c.
 * Runs as a foreground service, keeps a persistent task list for pause/resume across restarts,
 * and writes straight into Downloads/SatLink.
 */
public class FileDownloadService extends Service {
    private static final String TAG = "SatLinkFiles";
    static final String ACTION_START = "com.satlink.geckoviewtest.files.START";
    static final String ACTION_PAUSE = "com.satlink.geckoviewtest.files.PAUSE";
    static final String ACTION_RESUME = "com.satlink.geckoviewtest.files.RESUME";
    static final String ACTION_CANCEL = "com.satlink.geckoviewtest.files.CANCEL";
    static final String ACTION_REMOVE = "com.satlink.geckoviewtest.files.REMOVE";
    static final String EXTRA_ID = "id", EXTRA_KIND = "kind", EXTRA_URL = "url", EXTRA_NAME = "name",
            EXTRA_MIME = "mime", EXTRA_REFERER = "referer", EXTRA_AGENT = "agent", EXTRA_COOKIES = "cookies";
    static final String EXTRA_OPEN_DOWNLOADS = "satlink_open_downloads";
    static final String KIND_HTTP = "http", KIND_TORRENT = "torrent";
    static final String QUEUED = "queued", RUNNING = "running", PAUSED = "paused", FAILED = "failed", DONE = "done";
    static final int DEFAULT_CONNECTIONS = 32;
    private static final String PREFS = "satlink_files", KEY_TASKS = "tasks", KEY_CONNECTIONS = "connections";
    private static final String CHANNEL_ACTIVE = "satlink_files_active", CHANNEL_DONE = "satlink_files_done";
    private static final int FOREGROUND_ID = 7300;
    private static final int PARALLEL_TASKS = 3;

    /** One download as shown in the downloads screen. */
    static final class Task {
        String id = UUID.randomUUID().toString(), kind = KIND_HTTP, url = "", name = "", mime = "",
                referer = "", agent = "", cookies = "", state = QUEUED, error = "",
                contentUri = "", filePath = "", workDir = "", resume = "";
        long downloaded, total = -1, createdAt = System.currentTimeMillis();
        boolean exportOnFinish;
        // Live values, not saved.
        volatile long speed;
        volatile int connections, peers;

        JSONObject toJson() throws Exception {
            JSONObject json = new JSONObject();
            json.put("id", id).put("kind", kind).put("url", url).put("name", name).put("mime", mime)
                    .put("referer", referer).put("agent", agent).put("cookies", cookies).put("state", state)
                    .put("error", error).put("contentUri", contentUri).put("filePath", filePath)
                    .put("workDir", workDir).put("resume", resume).put("downloaded", downloaded)
                    .put("total", total).put("createdAt", createdAt).put("exportOnFinish", exportOnFinish);
            return json;
        }

        static Task from(JSONObject json) {
            Task task = new Task();
            task.id = json.optString("id", task.id);
            task.kind = json.optString("kind", KIND_HTTP);
            task.url = json.optString("url");
            task.name = json.optString("name");
            task.mime = json.optString("mime");
            task.referer = json.optString("referer");
            task.agent = json.optString("agent");
            task.cookies = json.optString("cookies");
            task.state = json.optString("state", PAUSED);
            task.error = json.optString("error");
            task.contentUri = json.optString("contentUri");
            task.filePath = json.optString("filePath");
            task.workDir = json.optString("workDir");
            task.resume = json.optString("resume");
            task.downloaded = json.optLong("downloaded");
            task.total = json.optLong("total", -1);
            task.createdAt = json.optLong("createdAt", System.currentTimeMillis());
            task.exportOnFinish = json.optBoolean("exportOnFinish");
            return task;
        }

        Task copy() {
            try {
                Task copy = from(toJson());
                copy.speed = speed; copy.connections = connections; copy.peers = peers;
                return copy;
            } catch (Exception error) { return this; }
        }

        boolean active() { return QUEUED.equals(state) || RUNNING.equals(state); }
        int percent() { return total > 0 ? (int) Math.min(100, downloaded * 100 / total) : -1; }
    }

    private static final Map<String, Task> TASKS = new ConcurrentHashMap<>();
    private static boolean loaded;

    // ---------------------------------------------------------------- shared with the activity

    static synchronized void load(Context context) {
        if (loaded) return;
        loaded = true;
        try {
            JSONArray array = new JSONArray(prefs(context).getString(KEY_TASKS, "[]"));
            for (int i = 0; i < array.length(); i++) {
                Task task = Task.from(array.getJSONObject(i));
                // The process ended while these were running; the user resumes them.
                if (task.active()) task.state = PAUSED;
                TASKS.put(task.id, task);
            }
        } catch (Exception error) { Log.w(TAG, "Could not read saved downloads", error); }
    }

    static synchronized void save(Context context) {
        JSONArray array = new JSONArray();
        for (Task task : TASKS.values()) {
            if (DONE.equals(task.state)) continue;
            try { array.put(task.toJson()); } catch (Exception ignored) { /* skip a broken row */ }
        }
        prefs(context).edit().putString(KEY_TASKS, array.toString()).apply();
    }

    /** Newest first; copies, safe to read on the UI thread. */
    static List<Task> snapshot(Context context) {
        load(context);
        List<Task> list = new ArrayList<>();
        for (Task task : TASKS.values()) list.add(task.copy());
        list.sort((a, b) -> Long.compare(b.createdAt, a.createdAt));
        return list;
    }

    /** File names of unfinished tasks, to avoid a second file with the same name. */
    static Set<String> activeNames(Context context) {
        load(context);
        Set<String> names = new HashSet<>();
        for (Task task : TASKS.values()) if (!DONE.equals(task.state)) names.add(task.name);
        return names;
    }

    static int connections(Context context) {
        int value = prefs(context).getInt(KEY_CONNECTIONS, DEFAULT_CONNECTIONS);
        return Math.max(1, Math.min(SegmentedDownloader.MAX_CONNECTIONS, value));
    }

    static void setConnections(Context context, int value) {
        prefs(context).edit().putInt(KEY_CONNECTIONS, Math.max(1, Math.min(SegmentedDownloader.MAX_CONNECTIONS, value))).apply();
    }

    static void startHttp(Context context, String url, String name, String mime, String referer, String agent, String cookies) {
        Intent intent = new Intent(context, FileDownloadService.class).setAction(ACTION_START)
                .putExtra(EXTRA_KIND, KIND_HTTP).putExtra(EXTRA_URL, url).putExtra(EXTRA_NAME, name)
                .putExtra(EXTRA_MIME, mime).putExtra(EXTRA_REFERER, referer).putExtra(EXTRA_AGENT, agent)
                .putExtra(EXTRA_COOKIES, cookies);
        startForegroundCompat(context, intent);
    }

    static void startTorrent(Context context, String source, String name, String referer, String agent, String cookies) {
        Intent intent = new Intent(context, FileDownloadService.class).setAction(ACTION_START)
                .putExtra(EXTRA_KIND, KIND_TORRENT).putExtra(EXTRA_URL, source).putExtra(EXTRA_NAME, name)
                .putExtra(EXTRA_REFERER, referer).putExtra(EXTRA_AGENT, agent).putExtra(EXTRA_COOKIES, cookies);
        startForegroundCompat(context, intent);
    }

    static void command(Context context, String action, String id) {
        Intent intent = new Intent(context, FileDownloadService.class).setAction(action).putExtra(EXTRA_ID, id);
        if (ACTION_RESUME.equals(action)) startForegroundCompat(context, intent);
        else context.startService(intent);
    }

    private static void startForegroundCompat(Context context, Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent);
        else context.startService(intent);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    // ---------------------------------------------------------------- service

    private interface Stoppable { void stop(); }

    private final ExecutorService executor = Executors.newFixedThreadPool(PARALLEL_TASKS);
    private final Map<String, Stoppable> running = new ConcurrentHashMap<>();
    private final Set<String> cancelRequested = ConcurrentHashMap.newKeySet();
    private final Set<String> pauseRequested = ConcurrentHashMap.newKeySet();
    private NotificationManager notifications;
    private boolean foreground;
    private final Runnable ticker = this::tick;
    private android.os.Handler handler;

    @Override public void onCreate() {
        super.onCreate();
        notifications = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        handler = new android.os.Handler(getMainLooper());
        createChannels();
        load(this);
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        // startForegroundService() requires startForeground() promptly, whatever the command.
        goForeground();
        if (ACTION_START.equals(action)) {
            Task task = new Task();
            task.kind = KIND_TORRENT.equals(intent.getStringExtra(EXTRA_KIND)) ? KIND_TORRENT : KIND_HTTP;
            task.url = value(intent, EXTRA_URL);
            task.name = value(intent, EXTRA_NAME);
            task.mime = value(intent, EXTRA_MIME);
            task.referer = value(intent, EXTRA_REFERER);
            task.agent = value(intent, EXTRA_AGENT);
            task.cookies = value(intent, EXTRA_COOKIES);
            if (task.name.isEmpty()) task.name = KIND_TORRENT.equals(task.kind) ? "Torrent" : "download.bin";
            if (!task.url.isEmpty()) {
                TASKS.put(task.id, task);
                submit(task);
            }
        } else if (intent != null) {
            Task task = TASKS.get(value(intent, EXTRA_ID));
            if (task != null) {
                if (ACTION_PAUSE.equals(action)) pause(task);
                else if (ACTION_RESUME.equals(action)) resume(task);
                else if (ACTION_CANCEL.equals(action)) cancel(task);
                else if (ACTION_REMOVE.equals(action) && !task.active()) { TASKS.remove(task.id); save(this); }
            }
        }
        save(this);
        stopIfIdle();
        return START_NOT_STICKY;
    }

    private static String value(Intent intent, String key) {
        String value = intent.getStringExtra(key);
        return value == null ? "" : value;
    }

    private void submit(Task task) {
        task.state = QUEUED;
        task.error = "";
        save(this);
        executor.execute(() -> run(task));
        handler.removeCallbacks(ticker);
        handler.post(ticker);
    }

    private void pause(Task task) {
        if (QUEUED.equals(task.state)) task.state = PAUSED; // not started yet: run() skips it
        pauseRequested.add(task.id);
        Stoppable stoppable = running.get(task.id);
        if (stoppable != null) stoppable.stop();
    }

    private void resume(Task task) {
        if (task.active() || DONE.equals(task.state)) return;
        pauseRequested.remove(task.id);
        submit(task);
    }

    private void cancel(Task task) {
        cancelRequested.add(task.id);
        Stoppable stoppable = running.get(task.id);
        if (stoppable != null) stoppable.stop();
        else {
            task.state = PAUSED; // a queued run() must not start it

            // Not running: remove what was written so far.
            new Thread(() -> { discard(task); TASKS.remove(task.id); cancelRequested.remove(task.id); save(this); }).start();
        }
    }

    @Override public void onTimeout(int startId, int foregroundServiceType) {
        // Android 15+ limits dataSync services to six hours a day; stop cleanly, keep progress.
        for (Task task : TASKS.values()) if (task.active()) pause(task);
        stopForegroundCompat();
        stopSelf();
    }

    @Override public void onDestroy() {
        handler.removeCallbacks(ticker);
        for (Stoppable stoppable : running.values()) stoppable.stop();
        executor.shutdown();
        save(this);
        super.onDestroy();
    }

    private void run(Task task) {
        if (!QUEUED.equals(task.state) || cancelRequested.contains(task.id)) return;
        pauseRequested.remove(task.id);
        task.state = RUNNING;
        task.error = "";
        try {
            boolean complete = KIND_TORRENT.equals(task.kind) ? runTorrent(task) : runHttp(task);
            if (cancelRequested.remove(task.id)) {
                discard(task);
                TASKS.remove(task.id);
            } else if (complete) {
                task.state = DONE;
                task.speed = 0;
                notifyDone(task);
            } else {
                task.state = PAUSED;
            }
        } catch (Throwable error) {
            Log.w(TAG, "Download failed: " + task.name, error);
            if (cancelRequested.remove(task.id)) {
                discard(task);
                TASKS.remove(task.id);
            } else {
                task.state = FAILED;
                task.error = describe(error);
            }
        } finally {
            running.remove(task.id);
            pauseRequested.remove(task.id);
            task.speed = 0;
            task.connections = 0;
            save(this);
            handler.post(this::stopIfIdle);
        }
    }

    private static String describe(Throwable error) {
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        if (message.contains("ENOSPC") || message.toLowerCase(Locale.ROOT).contains("no space")) return "لا توجد مساحة كافية";
        if (message.startsWith("HTTP 403")) return "الموقع رفض التنزيل (403). افتح الصفحة من جديد ثم حاول";
        if (message.startsWith("HTTP 404") || message.startsWith("HTTP 410")) return "الملف غير موجود على الخادم";
        return message.length() > 160 ? message.substring(0, 160) : message;
    }

    // ---------------------------------------------------------------- http

    private boolean runHttp(Task task) throws Exception {
        Map<String, String> headers = new LinkedHashMap<>();
        if (!task.agent.isEmpty()) headers.put("User-Agent", task.agent);
        if (!task.referer.isEmpty()) headers.put("Referer", task.referer);
        if (!task.cookies.isEmpty()) headers.put("Cookie", task.cookies);
        final long[] lastBytes = {task.downloaded};
        final long[] lastTime = {System.currentTimeMillis()};
        final long[] lastSave = {0};
        SegmentedDownloader downloader = new SegmentedDownloader(task.url, headers, connections(this),
                new SegmentedDownloader.Listener() {
                    @Override public void onProgress(long downloaded, long total, int active) {
                        long now = System.currentTimeMillis();
                        long elapsed = Math.max(1, now - lastTime[0]);
                        long instant = Math.max(0, downloaded - lastBytes[0]) * 1000L / elapsed;
                        task.speed = task.speed == 0 ? instant : (task.speed * 2 + instant) / 3;
                        lastBytes[0] = downloaded;
                        lastTime[0] = now;
                        task.downloaded = downloaded;
                        task.total = total;
                        task.connections = active;
                    }
                    @Override public void onState(String state) {
                        task.resume = state;
                        long now = System.currentTimeMillis();
                        if (now - lastSave[0] > 3000L) { lastSave[0] = now; save(FileDownloadService.this); }
                    }
                });
        running.put(task.id, downloader::stop);
        if (cancelRequested.contains(task.id)) return false;
        Output output = openOutput(task);
        try {
            SegmentedDownloader.Result result = downloader.download(output.channel, task.resume);
            task.downloaded = downloader.downloaded();
            task.total = result.total;
            if (!result.complete) return false;
        } finally {
            output.close();
        }
        finishOutput(task);
        task.resume = "";
        return true;
    }

    private static final class Output {
        FileChannel channel;
        ParcelFileDescriptor descriptor;
        void close() {
            try { if (channel != null) channel.close(); } catch (IOException ignored) { /* closing */ }
            try { if (descriptor != null) descriptor.close(); } catch (IOException ignored) { /* closing */ }
        }
    }

    /** Opens (or creates) the destination in Downloads/SatLink for positional writes. */
    private Output openOutput(Task task) throws IOException {
        Output output = new Output();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentResolver resolver = getContentResolver();
            Uri uri = task.contentUri.isEmpty() ? null : Uri.parse(task.contentUri);
            if (uri != null) {
                try { output.descriptor = resolver.openFileDescriptor(uri, "rw"); }
                catch (FileNotFoundException | SecurityException | IllegalArgumentException missing) { uri = null; }
            }
            if (uri == null) {
                // The pending entry is gone (or never existed): start a fresh file.
                task.resume = "";
                task.downloaded = 0;
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, GeneralFilePolicy.safeName(task.name));
                values.put(MediaStore.Downloads.MIME_TYPE, GeneralFilePolicy.mime(task.name, task.mime));
                values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/SatLink");
                values.put(MediaStore.Downloads.IS_PENDING, 1);
                uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri == null) throw new IOException("Cannot create the file in Downloads/SatLink");
                task.contentUri = uri.toString();
                task.name = displayName(uri, task.name);
                output.descriptor = resolver.openFileDescriptor(uri, "rw");
            }
            if (output.descriptor == null) throw new IOException("Cannot open the download file");
            output.channel = new FileOutputStream(output.descriptor.getFileDescriptor()).getChannel();
        } else {
            File file = task.filePath.isEmpty() ? null : new File(task.filePath);
            if (file == null || !file.isFile()) {
                File folder = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "SatLink");
                if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("Cannot create Downloads/SatLink");
                file = file != null && file.getParentFile() != null && file.getParentFile().isDirectory()
                        ? file : StreamFileSaver.uniqueFile(folder, GeneralFilePolicy.safeName(task.name));
                task.filePath = file.getAbsolutePath();
                task.name = file.getName();
                task.resume = "";
            }
            output.channel = FileChannel.open(file.toPath(), StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        }
        save(this);
        return output;
    }

    private String displayName(Uri uri, String fallback) {
        try (Cursor cursor = getContentResolver().query(uri, new String[]{MediaStore.Downloads.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (!TextUtils.isEmpty(name)) return name;
            }
        } catch (RuntimeException ignored) { /* keep the requested name */ }
        return fallback;
    }

    private void finishOutput(Task task) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !task.contentUri.isEmpty()) {
            ContentValues done = new ContentValues();
            done.put(MediaStore.Downloads.IS_PENDING, 0);
            getContentResolver().update(Uri.parse(task.contentUri), done, null, null);
        } else if (!task.filePath.isEmpty()) {
            MediaScannerConnection.scanFile(this, new String[]{task.filePath}, null, null);
        }
    }

    // ---------------------------------------------------------------- torrent

    private boolean runTorrent(Task task) throws Exception {
        EngineBootstrap.initialize(getApplicationContext());
        com.yausername.aria2c.Aria2c.getInstance().init(getApplicationContext());
        File dir = torrentDir(task);
        String source = torrentSource(task, dir);
        File packages = new File(getNoBackupFilesDir(), "youtubedl-android/packages");
        File cert = new File(packages, "python/usr/etc/tls/cert.pem");
        File dht = new File(getFilesDir(), "aria2/dht.dat");
        File dhtDir = dht.getParentFile();
        if (dhtDir != null && !dhtDir.isDirectory() && !dhtDir.mkdirs()) Log.w(TAG, "Cannot create DHT folder");
        List<String> command = new ArrayList<>();
        command.add(new File(getApplicationInfo().nativeLibraryDir, "libaria2c.so").getAbsolutePath());
        command.addAll(TorrentSupport.arguments(source, dir.getAbsolutePath(), dht.getAbsolutePath(),
                cert.isFile() ? cert.getAbsolutePath() : "", android.os.Process.myPid()));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        StringBuilder libraries = new StringBuilder();
        for (String name : new String[]{"python", "ffmpeg", "aria2c"}) {
            File lib = new File(packages, name + "/usr/lib");
            if (lib.isDirectory()) libraries.append(lib.getAbsolutePath()).append(':');
        }
        libraries.append(getApplicationInfo().nativeLibraryDir);
        builder.environment().put("LD_LIBRARY_PATH", libraries.toString());
        builder.environment().put("HOME", getFilesDir().getAbsolutePath());
        builder.environment().put("TMPDIR", getCacheDir().getAbsolutePath());
        if (cert.isFile()) builder.environment().put("SSL_CERT_FILE", cert.getAbsolutePath());

        Process process = builder.start();
        running.put(task.id, () -> {
            process.destroy();
            new Thread(() -> {
                try { Thread.sleep(6000L); } catch (InterruptedException ignored) { return; }
                if (process.isAlive()) process.destroyForcibly();
            }).start();
        });
        if (cancelRequested.contains(task.id)) process.destroy();
        ArrayDeque<String> tail = new ArrayDeque<>();
        try (Reader reader = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
            StringBuilder line = new StringBuilder();
            int c;
            while ((c = reader.read()) != -1) {
                if (c != '\n' && c != '\r') { if (line.length() < 4096) line.append((char) c); continue; }
                if (line.length() == 0) continue;
                String text = line.toString();
                line.setLength(0);
                handleTorrentLine(task, dir, text);
                if (!text.startsWith("[#") && !text.startsWith("FILE:") && !text.startsWith("***") && !text.startsWith("==") && !text.startsWith("--")) {
                    tail.addLast(text);
                    if (tail.size() > 12) tail.removeFirst();
                }
            }
        }
        int exit = process.waitFor();
        if (exit == 0) {
            String hash = TorrentSupport.infoHash(task.url);
            if (!hash.isEmpty() && !task.exportOnFinish) deleteQuietly(new File(dir, hash + ".torrent"));
            if (task.total > 0) task.downloaded = task.total;
            if (task.exportOnFinish) exportTree(dir);
            else scanTree(task);
            return true;
        }
        if (cancelRequested.contains(task.id) || pauseRequested.contains(task.id)) return false;
        throw new IOException(torrentError(exit, tail));
    }

    private static String torrentError(int exit, ArrayDeque<String> tail) {
        String detail = "";
        for (String line : tail) if (line.contains("errorCode") || line.contains("Exception") || line.contains("ERROR")) detail = line;
        if (detail.isEmpty() && !tail.isEmpty()) detail = tail.peekLast();
        return "aria2c (" + exit + "): " + detail;
    }

    private void handleTorrentLine(Task task, File dir, String text) {
        String path = TorrentSupport.filePath(text);
        if (!path.isEmpty()) {
            String top = TorrentSupport.topLevel(path, dir.getAbsolutePath());
            if (!top.isEmpty() && !top.endsWith(".torrent")) {
                task.name = top;
                task.filePath = new File(dir, top).getAbsolutePath();
            }
            return;
        }
        TorrentSupport.Progress progress = TorrentSupport.parse(text);
        if (progress == null || progress.seeding) return;
        // A magnet first fetches metadata (0B/0B), then the real files under a new id.
        if (progress.total >= Math.max(1, task.total) || progress.total == task.total) {
            task.total = progress.total;
            task.downloaded = progress.done;
            task.speed = progress.speed;
            task.connections = progress.connections;
            task.peers = progress.seeds;
        } else if (task.total <= 0) {
            task.connections = progress.connections;
            task.peers = progress.seeds;
        }
    }

    /** Public Downloads/SatLink when the app may write there directly; otherwise a private folder. */
    private File torrentDir(Task task) throws IOException {
        if (!task.workDir.isEmpty()) {
            File saved = new File(task.workDir);
            if (saved.isDirectory() || saved.mkdirs()) return saved;
        }
        File publicDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "SatLink");
        boolean direct = Build.VERSION.SDK_INT != Build.VERSION_CODES.Q && canWrite(publicDir);
        File dir;
        if (direct) {
            dir = publicDir;
            task.exportOnFinish = false;
        } else {
            File base = getExternalFilesDir("torrents");
            if (base == null) base = new File(getFilesDir(), "torrents");
            dir = new File(base, task.id);
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create the torrent folder");
            task.exportOnFinish = true;
        }
        task.workDir = dir.getAbsolutePath();
        save(this);
        return dir;
    }

    private static boolean canWrite(File dir) {
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) return false;
            File probe = new File(dir, ".satlink-write-test");
            try (FileOutputStream out = new FileOutputStream(probe)) { out.write(1); }
            return probe.delete();
        } catch (IOException | SecurityException error) {
            return false;
        }
    }

    /** Magnet link, a saved copy of its metadata, or a local copy of a .torrent link. */
    private String torrentSource(Task task, File dir) throws Exception {
        if (TorrentSupport.isMagnet(task.url)) {
            String hash = TorrentSupport.infoHash(task.url);
            File metadata = hash.isEmpty() ? null : new File(dir, hash + ".torrent");
            if (metadata != null && metadata.isFile() && metadata.length() > 0) return metadata.getAbsolutePath();
            return task.url;
        }
        File stored = new File(new File(getFilesDir(), "torrents"), task.id + ".torrent");
        if (stored.isFile() && stored.length() > 0) return stored.getAbsolutePath();
        File parent = stored.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot store the torrent file");
        Map<String, String> headers = new LinkedHashMap<>();
        if (!task.agent.isEmpty()) headers.put("User-Agent", task.agent);
        if (!task.referer.isEmpty()) headers.put("Referer", task.referer);
        if (!task.cookies.isEmpty()) headers.put("Cookie", task.cookies);
        SegmentedDownloader fetch = new SegmentedDownloader(task.url, headers, 1, null);
        try (FileChannel channel = FileChannel.open(stored.toPath(), StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            if (!fetch.download(channel, null).complete) throw new IOException("Could not download the .torrent file");
        }
        return stored.getAbsolutePath();
    }

    private void scanTree(Task task) {
        if (task.filePath.isEmpty()) return;
        List<String> paths = new ArrayList<>();
        collect(new File(task.filePath), paths);
        if (!paths.isEmpty()) MediaScannerConnection.scanFile(this, paths.toArray(new String[0]), null, null);
    }

    private static void collect(File file, List<String> out) {
        if (file.isFile()) { out.add(file.getAbsolutePath()); return; }
        File[] children = file.listFiles();
        if (children != null) for (File child : children) collect(child, out);
    }

    /** Android 10: move finished torrent files from the private folder into Downloads/SatLink. */
    private void exportTree(File dir) throws IOException {
        List<String> paths = new ArrayList<>();
        collect(dir, paths);
        String base = dir.getAbsolutePath() + "/";
        for (String path : paths) {
            if (path.endsWith(".aria2") || path.endsWith(".torrent")) continue;
            String relative = path.substring(base.length());
            int slash = relative.lastIndexOf('/');
            String folder = slash < 0 ? "" : "/" + relative.substring(0, slash);
            String name = slash < 0 ? relative : relative.substring(slash + 1);
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, name);
            values.put(MediaStore.Downloads.MIME_TYPE, GeneralFilePolicy.mime(name, ""));
            values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/SatLink" + folder);
            values.put(MediaStore.Downloads.IS_PENDING, 1);
            Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IOException("Cannot save " + name);
            try (InputStream in = new FileInputStream(path); OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new IOException("Cannot save " + name);
                byte[] buffer = new byte[256 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.Downloads.IS_PENDING, 0);
            getContentResolver().update(uri, done, null, null);
        }
        deleteQuietly(dir);
    }

    /** Removes partial data of a cancelled task. */
    private void discard(Task task) {
        try {
            if (KIND_HTTP.equals(task.kind)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !task.contentUri.isEmpty())
                    getContentResolver().delete(Uri.parse(task.contentUri), null, null);
                else if (!task.filePath.isEmpty()) deleteQuietly(new File(task.filePath));
                return;
            }
            if (task.exportOnFinish && !task.workDir.isEmpty()) { deleteQuietly(new File(task.workDir)); return; }
            if (!task.filePath.isEmpty()) {
                deleteQuietly(new File(task.filePath));
                deleteQuietly(new File(task.filePath + ".aria2"));
            }
            String hash = TorrentSupport.infoHash(task.url);
            if (!hash.isEmpty() && !task.workDir.isEmpty()) deleteQuietly(new File(task.workDir, hash + ".torrent"));
            deleteQuietly(new File(new File(getFilesDir(), "torrents"), task.id + ".torrent"));
        } catch (RuntimeException error) {
            Log.w(TAG, "Could not remove partial download", error);
        }
    }

    private static void deleteQuietly(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.isDirectory() ? file.listFiles() : null;
        if (children != null) for (File child : children) deleteQuietly(child);
        if (!file.delete()) Log.w(TAG, "Could not delete " + file.getName());
    }

    // ---------------------------------------------------------------- notifications

    private void createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel active = new NotificationChannel(CHANNEL_ACTIVE, "تنزيل الملفات", NotificationManager.IMPORTANCE_LOW);
        active.setSound(null, null);
        notifications.createNotificationChannel(active);
        notifications.createNotificationChannel(new NotificationChannel(CHANNEL_DONE, "اكتمال تنزيل الملفات", NotificationManager.IMPORTANCE_DEFAULT));
    }

    private PendingIntent openDownloads(int code) {
        Intent open = new Intent(this, MainActivity.class).putExtra(EXTRA_OPEN_DOWNLOADS, true)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(this, code, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification.Builder builder(String channel) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? new Notification.Builder(this, channel) : new Notification.Builder(this);
    }

    private Notification progressNotification() {
        int count = 0;
        long done = 0, total = 0, speed = 0;
        boolean unknown = false;
        Task first = null;
        for (Task task : TASKS.values()) {
            if (!task.active()) continue;
            count++;
            if (first == null) first = task;
            done += task.downloaded;
            if (task.total > 0) total += task.total; else unknown = true;
            speed += task.speed;
        }
        Notification.Builder builder = builder(CHANNEL_ACTIVE)
                .setSmallIcon(R.drawable.ic_stat_download)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openDownloads(1));
        if (count == 0) return builder.setContentTitle("SatLink").setContentText("جاري التحضير...").build();
        String title = count == 1 ? first.name : "تنزيل " + count + " ملفات";
        String text = (total > 0 && !unknown ? (done * 100 / total) + "%  •  " : "") + Formats.bytes(done)
                + (total > 0 && !unknown ? " / " + Formats.bytes(total) : "") + "  •  " + Formats.bytes(speed) + "/ث";
        builder.setContentTitle(title).setContentText(text);
        if (total > 0 && !unknown) builder.setProgress(100, (int) (done * 100 / total), false);
        else builder.setProgress(0, 0, true);
        return builder.build();
    }

    private void goForeground() {
        Notification notification = progressNotification();
        if (!foreground) {
            startForeground(FOREGROUND_ID, notification);
            foreground = true;
        } else {
            notifications.notify(FOREGROUND_ID, notification);
        }
    }

    private void tick() {
        boolean active = false;
        for (Task task : TASKS.values()) if (task.active()) { active = true; break; }
        if (!active) { stopIfIdle(); return; }
        if (foreground) notifications.notify(FOREGROUND_ID, progressNotification());
        handler.postDelayed(ticker, 1000L);
    }

    private void notifyDone(Task task) {
        Notification notification = builder(CHANNEL_DONE)
                .setSmallIcon(R.drawable.ic_stat_download)
                .setContentTitle("اكتمل التنزيل")
                .setContentText(task.name)
                .setAutoCancel(true)
                .setContentIntent(openDownloads(task.id.hashCode()))
                .build();
        notifications.notify(task.id.hashCode(), notification);
    }

    private void stopIfIdle() {
        for (Task task : TASKS.values()) if (task.active()) return;
        if (!running.isEmpty()) return;
        stopForegroundCompat();
        stopSelf();
    }

    private void stopForegroundCompat() {
        if (!foreground) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE);
        else stopForeground(true);
        foreground = false;
    }

    /** Small formatting helpers shared with the downloads screen. */
    static final class Formats {
        static String bytes(long value) {
            if (value <= 0) return "0 B";
            String[] units = {"B", "KB", "MB", "GB", "TB"};
            double size = value;
            int unit = 0;
            while (size >= 1024 && unit < units.length - 1) { size /= 1024; unit++; }
            return String.format(Locale.US, size >= 100 ? "%.0f %s" : size >= 10 ? "%.1f %s" : "%.2f %s", size, units[unit]);
        }
    }
}
