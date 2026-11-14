package com.satlink.geckoviewtest;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Log;

import com.yausername.ffmpeg.FFmpeg;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Collections;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import kotlin.Unit;
import kotlin.jvm.functions.Function3;

/**
 * Foreground media download service.
 *
 * Downloads continue when the browser is backgrounded or the screen is off. Pause is implemented
 * by stopping the active yt-dlp process while preserving its .part files; resume runs the same
 * command with --continue so the partial download is reused whenever the server supports ranges.
 */
public class MediaDownloadService extends Service {
    private static final String TAG = "SatLinkDownload";

    public static final String ACTION_START = "com.satlink.geckoviewtest.action.START_DOWNLOAD";
    public static final String ACTION_PAUSE = "com.satlink.geckoviewtest.action.PAUSE_DOWNLOAD";
    public static final String ACTION_RESUME = "com.satlink.geckoviewtest.action.RESUME_DOWNLOAD";
    public static final String ACTION_CANCEL = "com.satlink.geckoviewtest.action.CANCEL_DOWNLOAD";
    public static final String ACTION_RETRY = "com.satlink.geckoviewtest.action.RETRY_DOWNLOAD";
    public static final String ACTION_QUERY = "com.satlink.geckoviewtest.action.QUERY_DOWNLOAD";
    public static final String ACTION_STATUS = "com.satlink.geckoviewtest.action.DOWNLOAD_STATUS";

    public static final String EXTRA_TASK_ID = "task_id";
    public static final String EXTRA_SOURCE_URL = "source_url";
    public static final String EXTRA_DIRECT_URL = "direct_url";
    public static final String EXTRA_DIRECT_AUDIO_URL = "direct_audio_url";
    public static final String EXTRA_FORMAT_ID = "format_id";
    public static final String EXTRA_MEDIA_TYPE = "media_type";
    public static final String EXTRA_AUDIO_QUALITY = "audio_quality";
    public static final String EXTRA_HAS_AUDIO = "has_audio";
    public static final String EXTRA_OUTPUT_EXT = "output_ext";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_ESTIMATED_SIZE = "estimated_size";
    public static final String EXTRA_USE_BROWSER_SESSION = "use_browser_session";
    public static final String EXTRA_MANIFEST = "manifest_source";
    public static final String EXTRA_CAPTURED_DIRECT = "captured_direct";
    public static final String EXTRA_VIDEO_CODEC = "video_codec";
    public static final String EXTRA_AUDIO_CODEC = "audio_codec";
    public static final String EXTRA_COOKIE_FILE = "cookie_file";
    public static final String EXTRA_COOKIE_HEADER = "cookie_header";
    public static final String EXTRA_USER_AGENT = "user_agent";

    public static final String EXTRA_STATE = "state";
    public static final String EXTRA_PROGRESS = "progress";
    public static final String EXTRA_ETA = "eta";
    public static final String EXTRA_SPEED = "speed";
    public static final String EXTRA_DOWNLOADED = "downloaded";
    public static final String EXTRA_TOTAL = "total";
    public static final String EXTRA_MESSAGE = "message";

    public static final int TYPE_VIDEO = 1;
    public static final int TYPE_AUDIO = 2;

    public static final String STATE_QUEUED = "queued";
    public static final String STATE_RUNNING = "running";
    public static final String STATE_PAUSED = "paused";
    public static final String STATE_COMPLETED = "completed";
    public static final String STATE_FAILED = "failed";
    public static final String STATE_CANCELLED = "cancelled";

    private static final String CHANNEL_ACTIVE = "satlink_download_active";
    private static final String CHANNEL_COMPLETE = "satlink_download_complete";
    private static final int FOREGROUND_BASE_ID = 41000;
    private static final Pattern SPEED_PATTERN = Pattern.compile("\\bat\\s+([^\\s]+/s)", Pattern.CASE_INSENSITIVE);

    // Static so the downloads screen can list running video/audio downloads.
    private static final Map<String, TaskState> tasks = new ConcurrentHashMap<>();

    /** Read-only view of one video/audio download for the downloads screen. */
    static final class Snapshot {
        String id, title, state, speed, message;
        int progress;
        long eta, downloaded, total;
    }

    static List<Snapshot> snapshot() {
        List<Snapshot> list = new ArrayList<>();
        for (TaskState task : tasks.values()) {
            if (STATE_COMPLETED.equals(task.state) || STATE_CANCELLED.equals(task.state)) continue;
            Snapshot item = new Snapshot();
            item.id = task.taskId;
            item.title = TextUtils.isEmpty(task.title) ? "فيديو" : task.title;
            item.state = task.state;
            item.speed = task.speed == null ? "" : task.speed;
            item.message = task.message == null ? "" : task.message;
            item.progress = task.progress;
            item.eta = task.etaSeconds;
            item.downloaded = task.downloadedBytes;
            item.total = task.totalBytes;
            list.add(item);
        }
        return list;
    }
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final AtomicBoolean engineReady = new AtomicBoolean(false);
    private final Object foregroundLock = new Object();

    private NotificationManager notificationManager;
    private String foregroundTaskId;

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        createNotificationChannels();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || TextUtils.isEmpty(intent.getAction())) {
            return START_NOT_STICKY;
        }

        String action = intent.getAction();
        String taskId = intent.getStringExtra(EXTRA_TASK_ID);

        switch (action) {
            case ACTION_START:
                startTask(TaskState.fromIntent(intent));
                break;
            case ACTION_PAUSE:
                pauseTask(taskId);
                break;
            case ACTION_RESUME:
                resumeTask(taskId, intent);
                break;
            case ACTION_RETRY:
                retryTask(taskId, intent);
                break;
            case ACTION_CANCEL:
                cancelTask(taskId, intent);
                break;
            case ACTION_QUERY:
                queryTask(taskId);
                break;
            default:
                break;
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startTask(TaskState incoming) {
        if (incoming == null || TextUtils.isEmpty(incoming.taskId) || TextUtils.isEmpty(incoming.downloadUrl())) {
            return;
        }

        TaskState existing = tasks.putIfAbsent(incoming.taskId, incoming);
        TaskState task = existing != null ? existing : incoming;
        if (existing != null) {
            broadcast(task);
            return;
        }

        task.state = STATE_QUEUED;
        task.message = "بانتظار بدء التحميل...";
        ensureForeground(task);
        broadcast(task);
        submitTask(task);
    }

    private void submitTask(TaskState task) {
        if (task == null || task.cancelled || task.paused || !task.executing.compareAndSet(false, true)) {
            return;
        }
        executor.execute(() -> runTask(task));
    }

    private void runTask(TaskState task) {
        PowerManager.WakeLock wakeLock = null;
        try {
            PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
            if (powerManager != null) {
                wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                        "SatLinkBrowser:MediaDownload");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire();
            }
            initializeEngine();
            if (task.cancelled || task.paused) return;

            task.resumeRequested = false;
            task.processStopRequested = false;
            task.state = STATE_RUNNING;
            task.message = task.progress > 0 ? "جاري استكمال التحميل..." : "جاري بدء التحميل...";
            ensureForeground(task);
            broadcast(task);

            File workDir = getTaskWorkDirectory(task.taskId);
            if (!workDir.exists() && !workDir.mkdirs()) {
                throw new IllegalStateException("تعذر إنشاء مجلد التحميل المؤقت.");
            }

            List<File> outputs;
            try {
                executeWithYouTubeRetry(task, workDir);
                if (task.cancelled || task.paused) return;
                outputs = findFinalMediaFiles(workDir, task);
            } catch (Throwable pageError) {
                if (!canUseSafeDirectFallback(task) || task.cancelled || task.paused) {
                    if (pageError instanceof Exception) throw (Exception) pageError;
                    throw new Exception(pageError);
                }
                task.message = "تعذر رابط الصفحة — محاولة تنزيل الرابط المباشر الآمن...";
                updateNotification(task);
                broadcast(task);
                task.processId = "satlink-direct-" + task.taskId + "-" + System.currentTimeMillis();
                YoutubeDL.getInstance().execute(
                        buildDirectRequest(task, workDir), task.processId, progressCallback(task));
                if (task.cancelled || task.paused) return;
                outputs = findFinalMediaFiles(workDir, task);
            }
            if (outputs.isEmpty()) {
                throw new IllegalStateException("اكتمل المحرك لكن لم يعثر على الملف النهائي.");
            }

            List<String> savedNames = new ArrayList<>();
            for (File output : outputs) {
                savedNames.add(publishToDownloads(output));
            }

            task.progress = 100;
            task.state = STATE_COMPLETED;
            task.message = "اكتمل: " + TextUtils.join("، ", savedNames);
            task.downloadedBytes = task.totalBytes > 0 ? task.totalBytes : task.downloadedBytes;
            showCompletedNotification(task);
            broadcast(task);
            deleteRecursively(workDir);
            tasks.remove(task.taskId);
            releaseForegroundIfNeeded(task.taskId);
        } catch (Throwable error) {
            if (task.cancelled) {
                task.state = STATE_CANCELLED;
                task.message = "تم إلغاء التحميل";
                notificationManager.cancel(notificationId(task.taskId));
                broadcast(task);
                return;
            } else if (task.processStopRequested || task.paused) {
                task.state = task.resumeRequested ? STATE_QUEUED : STATE_PAUSED;
                task.message = task.resumeRequested ? "جاري استئناف التحميل..." : "متوقف مؤقتاً — اضغط استئناف";
            } else {
                task.state = STATE_FAILED;
                task.message = friendlyDownloadFailure(error);
                Log.e(TAG, "Download failed: " + task.taskId, error);
            }
            updateNotification(task);
            broadcast(task);
        } finally {
            if (wakeLock != null && wakeLock.isHeld()) {
                try { wakeLock.release(); } catch (Throwable ignored) { }
            }
            task.processId = null;
            task.postProcess = null;
            task.processStopRequested = false;
            task.executing.set(false);
            if (task.resumeRequested && !task.cancelled && !task.paused) {
                task.resumeRequested = false;
                submitTask(task);
            }
        }
    }

    private void executeWithYouTubeRetry(TaskState task, File workDir) throws Exception {
        task.processId = "satlink-service-" + task.taskId + "-" + System.currentTimeMillis();
        try {
            YoutubeDL.getInstance().execute(
                    buildRequest(task, workDir), task.processId, progressCallback(task));
        } catch (Throwable firstError) {
            if (task.capturedDirect || !isYouTubeTask(task) || !looksLikeThrottle(firstError)
                    || task.cancelled || task.paused) {
                if (firstError instanceof Exception) throw (Exception) firstError;
                throw new Exception(firstError);
            }
            task.message = "يوتيوب حدّد الطلب الأول — إعادة المحاولة بنفس إعدادات التحليل...";
            updateNotification(task);
            broadcast(task);
            task.processId = "satlink-service-retry-" + task.taskId + "-" + System.currentTimeMillis();
            YoutubeDLRequest retry = buildRequest(task, workDir);
            YoutubeDL.getInstance().execute(retry, task.processId, progressCallback(task));
        }
    }

    private Function3<Float, Long, String, Unit> progressCallback(TaskState task) {
        return new Function3<Float, Long, String, Unit>() {
            @Override
            public Unit invoke(Float progress, Long eta, String line) {
                if (task.cancelled || task.paused) return Unit.INSTANCE;
                int value = progress == null ? task.progress
                        : Math.max(0, Math.min(100, Math.round(progress)));
                task.progress = Math.max(task.progress, value);
                task.etaSeconds = eta == null ? 0L : Math.max(0L, eta);
                task.speed = extractSpeed(line);
                task.totalBytes = task.estimatedSize > 0 ? task.estimatedSize : task.totalBytes;
                if (task.totalBytes > 0) {
                    task.downloadedBytes = Math.round(task.totalBytes * (value / 100d));
                }
                task.message = buildProgressMessage(task, line);
                updateNotification(task);
                broadcast(task);
                return Unit.INSTANCE;
            }
        };
    }

    private static boolean canUseSafeDirectFallback(TaskState task) {
        if (task == null || TextUtils.isEmpty(task.directUrl)) return false;
        if (task.mediaType == TYPE_AUDIO) return true;
        // A single progressive stream is safe to download directly. Adaptive video+audio pairs
        // must be handled by yt-dlp from the original page so the bundled FFmpeg environment is used.
        return task.hasAudio && TextUtils.isEmpty(task.directAudioUrl)
                && !isTransportStream(task);
    }

    private YoutubeDLRequest buildDirectRequest(TaskState task, File workDir) {
        YoutubeDLRequest request = new YoutubeDLRequest(task.directUrl);
        EngineRuntimeOptions.apply(getApplicationContext(), request);
        request.addOption("--no-mtime");
        request.addOption("--newline");
        request.addOption("--ignore-config");
        request.addOption("--continue");
        request.addOption("--part");
        request.addOption("--socket-timeout", "30");
        request.addOption("--retries", "10");
        request.addOption("--fragment-retries", isPinterestTask(task) ? "30" : "15");
        request.addOption("--retry-sleep", isPinterestTask(task) ? "fragment:3" : "fragment:1");
        request.addOption("--concurrent-fragments", isPinterestTask(task) ? "1" : "8");
        if (!isPinterestTask(task)) request.addOption("--http-chunk-size", "10M");
        request.addOption("--buffer-size", isPinterestTask(task) ? "256K" : "1M");
        request.addOption("--no-resize-buffer");
        request.addOption("-o", new File(workDir,
                task.mediaType == TYPE_AUDIO ? "SatLink direct audio.%(ext)s"
                        : "SatLink direct video.%(ext)s").getAbsolutePath());
        if (!TextUtils.isEmpty(task.sourceUrl)) request.addOption("--referer", task.sourceUrl);
        if (!TextUtils.isEmpty(task.userAgent)) request.addOption("--user-agent", task.userAgent);
        applyTaskCookies(request, task);
        if (task.mediaType == TYPE_AUDIO) {
            request.addOption("--extract-audio");
            request.addOption("--audio-format", "mp3");
            request.addOption("--audio-quality", Math.max(128, task.audioQuality) + "K");
        }
        return request;
    }

    private static boolean isYouTubeTask(TaskState task) {
        return task != null && (isYouTubeLike(task.sourceUrl) || isYouTubeLike(task.directUrl));
    }

    private static boolean isYouTubeLike(String url) {
        if (TextUtils.isEmpty(url)) return false;
        String lower = url.toLowerCase(Locale.US);
        return lower.contains("youtube.com/") || lower.contains("youtu.be/")
                || lower.contains("googlevideo.com/") || lower.contains("videoplayback");
    }

    private static boolean isPinterestTask(TaskState task) {
        return task != null && (isPinterestLike(task.sourceUrl) || isPinterestLike(task.directUrl));
    }

    private static boolean isPinterestLike(String url) {
        if (TextUtils.isEmpty(url)) return false;
        String lower = url.toLowerCase(Locale.US);
        return lower.contains("pinterest.com/") || lower.contains("pin.it/")
                || lower.contains("pinimg.com/");
    }

    private static boolean looksLikeThrottle(Throwable error) {
        String message = error == null ? "" : String.valueOf(error.getMessage()).toLowerCase(Locale.US);
        return message.contains("429") || message.contains("too many requests")
                || message.contains("unable to fetch gvs") || message.contains("sign in to confirm");
    }

    private static String friendlyDownloadFailure(Throwable error) {
        String message = safeMessage(error).toLowerCase(Locale.US);
        if (message.contains("403") || message.contains("forbidden")) {
            return "انتهت صلاحية رابط الوسائط المؤقت. افتح المنشور وشغّل الفيديو لحظة ثم أعد المحاولة.";
        }
        if (message.contains("429") || message.contains("too many requests")) {
            return "الموقع حدّد الطلبات مؤقتاً. انتظر قليلاً ثم أعد المحاولة.";
        }
        if (message.contains("impersonate")) {
            return "هذا الموقع يحتاج طريقة تحميل غير مدعومة حالياً.";
        }
        return "تعذر إكمال التحميل من هذا الموقع.";
    }

    private void initializeEngine() throws Exception {
        if (engineReady.get()) return;
        synchronized (engineReady) {
            if (engineReady.get()) return;
            EngineBootstrap.initialize(getApplicationContext());
            engineReady.set(true);
        }
    }

    private YoutubeDLRequest buildRequest(TaskState task, File workDir) throws Exception {
        YoutubeDLRequest request;
        if (task.capturedDirect && !TextUtils.isEmpty(task.directUrl)) {
            // Generic direct files and paired HTTPS streams can use captured info. Manifest
            // protocols still need the generic extractor to parse their segment lists.
            String path = Uri.parse(task.directUrl).getPath();
            boolean manifest = task.manifest || path != null && (path.toLowerCase(Locale.US).endsWith(".m3u8")
                    || path.toLowerCase(Locale.US).endsWith(".mpd"));
            if (manifest) {
                request = new YoutubeDLRequest(task.directUrl);
            } else {
                Map<String, Object> info = CapturedDownloadPlan.create(task.taskId, task.title,
                        task.directUrl, task.directAudioUrl, task.formatId, task.outputExt,
                        task.videoCodec, task.audioCodec, task.hasAudio,
                        task.mediaType == TYPE_AUDIO && TextUtils.isEmpty(task.videoCodec),
                        task.userAgent, task.sourceUrl);
                File infoFile = new File(workDir, "captured-info.json");
                try (FileOutputStream out = new FileOutputStream(infoFile)) {
                    out.write(new JSONObject(info).toString().getBytes(StandardCharsets.UTF_8));
                }
                request = new YoutubeDLRequest(Collections.<String>emptyList());
                request.addOption("--load-info-json", infoFile.getAbsolutePath());
            }
        } else {
            request = new YoutubeDLRequest(task.downloadUrl());
        }
        request.addOption("--no-playlist");
        request.addOption("--no-mtime");
        request.addOption("--newline");
        request.addOption("--ignore-config");
        request.addOption("--geo-bypass");
        request.addOption("--legacy-server-connect");
        request.addOption("--continue");
        request.addOption("--part");
        request.addOption("--socket-timeout", "30");
        request.addOption("--retries", "10");
        request.addOption("--fragment-retries", isPinterestTask(task) ? "30" : "15");
        request.addOption("--retry-sleep", isPinterestTask(task) ? "fragment:3" : "fragment:1");
        request.addOption("--concurrent-fragments", isPinterestTask(task) ? "1" : "8");
        if (!isPinterestTask(task)) request.addOption("--http-chunk-size", "10M");
        request.addOption("--buffer-size", isPinterestTask(task) ? "256K" : "1M");
        request.addOption("--no-resize-buffer");
        String outputTemplate = isTransportStream(task)
                ? "%(title).180B [%(format_id)s].ts"
                : "%(title).180B [%(format_id)s].%(ext)s";
        request.addOption("-o", new File(workDir, outputTemplate).getAbsolutePath());

        EngineRuntimeOptions.apply(getApplicationContext(), request);
        request.addOption("--extractor-retries", "5");
        if (task.useBrowserSession && !TextUtils.isEmpty(task.sourceUrl)) {
            request.addOption("--referer", task.sourceUrl);
        }
        if (task.useBrowserSession && !TextUtils.isEmpty(task.userAgent)) {
            request.addOption("--user-agent", task.userAgent);
        }
        applyTaskCookies(request, task);
        if (task.useBrowserSession && isYouTubeTask(task)) {
            request.addOption("--add-header", "Origin:https://www.youtube.com");
            request.addOption("--add-header", "Accept-Language:en-US,en;q=0.9");
        }
        if (isPinterestTask(task)) {
            request.addOption("--add-header", "Origin:https://www.pinterest.com");
            request.addOption("--add-header", "Accept-Language:ar-IQ,ar;q=0.9,en-US;q=0.8,en;q=0.7");
            request.addOption("--add-header", "Cache-Control:no-cache");
        }

        if (task.mediaType == TYPE_AUDIO) {
            request.addOption("-f", TextUtils.isEmpty(task.formatId) ? "bestaudio/best" : task.formatId);
            request.addOption("--extract-audio");
            request.addOption("--audio-format", "mp3");
            request.addOption("--audio-quality", Math.max(128, task.audioQuality) + "K");
        } else {
            String output = TextUtils.isEmpty(task.outputExt) ? "mp4" : task.outputExt.toLowerCase(Locale.US);
            boolean webm = "webm".equals(output);
            boolean mkv = "mkv".equals(output);
            boolean transportStream = "ts".equals(output) || "mpegts".equals(output);

            if (!TextUtils.isEmpty(task.formatId)) {
                if (task.hasAudio || task.formatId.contains("+") || task.formatId.contains("/")) {
                    request.addOption("-f", task.formatId);
                } else {
                    String preferredAudio = webm ? "webm" : "m4a";
                    request.addOption("-f", task.formatId + "+bestaudio[ext=" + preferredAudio + "]/"
                            + task.formatId + "+bestaudio/" + task.formatId);
                }
            } else {
                request.addOption("-f", "bestvideo+bestaudio/best");
            }

            if (transportStream) {
                // TS is exposed only for native HLS sources. yt-dlp performs the download inside
                // its own managed environment; libffmpeg.so is never executed directly.
                request.addOption("--hls-use-mpegts");
            } else if (mkv) {
                request.addOption("--merge-output-format", "mkv");
                request.addOption("--remux-video", "mkv");
            } else if (webm) {
                request.addOption("--merge-output-format", "webm");
            } else {
                request.addOption("--merge-output-format", "mp4");
            }
        }
        return request;
    }

    private static void applyTaskCookies(YoutubeDLRequest request, TaskState task) {
        if (request == null || task == null || !task.useBrowserSession) return;
        if (!TextUtils.isEmpty(task.cookieFile) && new File(task.cookieFile).isFile()) {
            request.addOption("--cookies", task.cookieFile);
        } else if (!TextUtils.isEmpty(task.cookieHeader)) {
            request.addOption("--add-header", "Cookie:" + task.cookieHeader);
        }
    }

    private static boolean isTransportStream(TaskState task) {
        if (task == null || TextUtils.isEmpty(task.outputExt)) return false;
        String ext = task.outputExt.toLowerCase(Locale.US);
        return "ts".equals(ext) || "mpegts".equals(ext);
    }

    private void pauseTask(String taskId) {
        TaskState task = tasks.get(taskId);
        if (task == null || task.cancelled || STATE_COMPLETED.equals(task.state)) return;
        task.paused = true;
        task.processStopRequested = true;
        task.resumeRequested = false;
        task.state = STATE_PAUSED;
        task.message = "متوقف مؤقتاً — اضغط استئناف";
        destroyProcess(task);
        updateNotification(task);
        broadcast(task);
    }

    private void resumeTask(String taskId, Intent fallbackIntent) {
        TaskState task = tasks.get(taskId);
        if (task == null) {
            task = TaskState.fromIntent(fallbackIntent);
            if (task == null) return;
            tasks.put(task.taskId, task);
        }
        task.cancelled = false;
        task.paused = false;
        task.resumeRequested = true;
        task.state = STATE_QUEUED;
        task.message = "جاري استئناف التحميل...";
        ensureForeground(task);
        broadcast(task);
        if (!task.executing.get()) {
            task.resumeRequested = false;
            submitTask(task);
        }
    }

    private void retryTask(String taskId, Intent fallbackIntent) {
        TaskState task = tasks.get(taskId);
        if (task == null) {
            task = TaskState.fromIntent(fallbackIntent);
            if (task == null) return;
            tasks.put(task.taskId, task);
        }
        task.cancelled = false;
        task.paused = false;
        task.resumeRequested = false;
        task.state = STATE_QUEUED;
        task.message = "إعادة المحاولة...";
        ensureForeground(task);
        broadcast(task);
        submitTask(task);
    }

    private void cancelTask(String taskId, Intent fallbackIntent) {
        TaskState task = tasks.get(taskId);
        if (task == null) {
            task = TaskState.fromIntent(fallbackIntent);
            if (task == null) return;
        }
        task.cancelled = true;
        task.paused = false;
        task.resumeRequested = false;
        task.state = STATE_CANCELLED;
        task.message = "تم إلغاء التحميل";
        destroyProcess(task);
        deleteRecursively(getTaskWorkDirectory(task.taskId));
        broadcast(task);
        tasks.remove(task.taskId);
        // Remove (not detach) the foreground notification, then clear it.
        releaseForegroundIfNeeded(task.taskId, true);
        notificationManager.cancel(notificationId(task.taskId));
    }

    private void queryTask(String taskId) {
        TaskState task = tasks.get(taskId);
        if (task != null) {
            broadcast(task);
        } else if (tasks.isEmpty() && foregroundTaskId == null) {
            stopSelf();
        }
    }

    private void destroyProcess(TaskState task) {
        if (task == null) return;
        if (!TextUtils.isEmpty(task.processId)) {
            try {
                YoutubeDL.getInstance().destroyProcessById(task.processId);
            } catch (Throwable error) {
                Log.w(TAG, "Could not stop process " + task.processId, error);
            }
        }
        Process postProcess = task.postProcess;
        if (postProcess != null) {
            try { postProcess.destroy(); } catch (Throwable ignored) { }
            try { postProcess.destroyForcibly(); } catch (Throwable ignored) { }
        }
    }

    private void createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel active = new NotificationChannel(
                CHANNEL_ACTIVE, "تنزيلات SatLink", NotificationManager.IMPORTANCE_LOW);
        active.setDescription("تقدم تنزيل الفيديو والصوت");
        active.setSound(null, null);
        notificationManager.createNotificationChannel(active);

        NotificationChannel complete = new NotificationChannel(
                CHANNEL_COMPLETE, "التنزيلات المكتملة", NotificationManager.IMPORTANCE_DEFAULT);
        complete.setDescription("إشعار عند اكتمال التنزيل");
        notificationManager.createNotificationChannel(complete);
    }

    private void ensureForeground(TaskState task) {
        Notification notification = buildActiveNotification(task);
        synchronized (foregroundLock) {
            if (foregroundTaskId == null) {
                foregroundTaskId = task.taskId;
                startForeground(notificationId(task.taskId), notification);
            } else {
                notificationManager.notify(notificationId(task.taskId), notification);
            }
        }
    }

    private void updateNotification(TaskState task) {
        if (task == null) return;
        notificationManager.notify(notificationId(task.taskId), buildActiveNotification(task));
    }

    private Notification buildActiveNotification(TaskState task) {
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this, notificationId(task.taskId), openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ACTIVE)
                : new Notification.Builder(this);

        String title = TextUtils.isEmpty(task.title)
                ? (task.mediaType == TYPE_AUDIO ? "تنزيل صوت MP3" : "تنزيل فيديو")
                : task.title;
        builder.setSmallIcon(R.drawable.ic_stat_download)
                .setContentTitle(title)
                .setContentText(task.message)
                .setContentIntent(contentIntent)
                .setOnlyAlertOnce(true)
                .setOngoing(STATE_RUNNING.equals(task.state) || STATE_QUEUED.equals(task.state))
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setVisibility(Notification.VISIBILITY_PUBLIC);

        if (task.progress > 0 && task.progress < 100 && !STATE_PAUSED.equals(task.state)) {
            builder.setProgress(100, task.progress, false);
        } else if (STATE_RUNNING.equals(task.state) || STATE_QUEUED.equals(task.state)) {
            builder.setProgress(100, 0, true);
        } else {
            builder.setProgress(0, 0, false);
        }

        if (STATE_RUNNING.equals(task.state) || STATE_QUEUED.equals(task.state)) {
            builder.addAction(new Notification.Action.Builder(
                    R.drawable.ic_stat_pause, "إيقاف مؤقت",
                    servicePendingIntent(task, ACTION_PAUSE, 1)).build());
            builder.addAction(new Notification.Action.Builder(
                    R.drawable.ic_stat_cancel, "إلغاء",
                    servicePendingIntent(task, ACTION_CANCEL, 2)).build());
        } else if (STATE_PAUSED.equals(task.state)) {
            builder.addAction(new Notification.Action.Builder(
                    R.drawable.ic_stat_resume, "استئناف",
                    servicePendingIntent(task, ACTION_RESUME, 3)).build());
            builder.addAction(new Notification.Action.Builder(
                    R.drawable.ic_stat_cancel, "إلغاء",
                    servicePendingIntent(task, ACTION_CANCEL, 4)).build());
        } else if (STATE_FAILED.equals(task.state)) {
            builder.addAction(new Notification.Action.Builder(
                    R.drawable.ic_stat_resume, "إعادة المحاولة",
                    servicePendingIntent(task, ACTION_RETRY, 5)).build());
            builder.addAction(new Notification.Action.Builder(
                    R.drawable.ic_stat_cancel, "إلغاء",
                    servicePendingIntent(task, ACTION_CANCEL, 6)).build());
        }
        return builder.build();
    }

    private PendingIntent servicePendingIntent(TaskState task, String action, int salt) {
        Intent intent = task.toIntent(this, action);
        int requestCode = notificationId(task.taskId) * 10 + salt;
        return PendingIntent.getService(this, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void showCompletedNotification(TaskState task) {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this, notificationId(task.taskId), openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_COMPLETE)
                : new Notification.Builder(this);
        Notification notification = builder
                .setSmallIcon(R.drawable.ic_stat_download)
                .setContentTitle("اكتمل التنزيل")
                .setContentText(task.message)
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .build();
        notificationManager.notify(notificationId(task.taskId), notification);
    }

    private void releaseForegroundIfNeeded(String finishedTaskId) {
        releaseForegroundIfNeeded(finishedTaskId, false);
    }

    private void releaseForegroundIfNeeded(String finishedTaskId, boolean removeNotification) {
        synchronized (foregroundLock) {
            if (!TextUtils.equals(foregroundTaskId, finishedTaskId)) return;
            TaskState replacement = null;
            for (TaskState candidate : tasks.values()) {
                if (!TextUtils.equals(candidate.taskId, finishedTaskId)) {
                    replacement = candidate;
                    break;
                }
            }
            if (replacement != null) {
                foregroundTaskId = replacement.taskId;
                startForeground(notificationId(replacement.taskId), buildActiveNotification(replacement));
            } else {
                foregroundTaskId = null;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(removeNotification ? STOP_FOREGROUND_REMOVE : STOP_FOREGROUND_DETACH);
                } else {
                    stopForeground(removeNotification);
                }
                stopSelf();
            }
        }
    }

    private void broadcast(TaskState task) {
        Intent update = new Intent(ACTION_STATUS);
        update.setPackage(getPackageName());
        update.putExtra(EXTRA_TASK_ID, task.taskId);
        update.putExtra(EXTRA_STATE, task.state);
        update.putExtra(EXTRA_PROGRESS, task.progress);
        update.putExtra(EXTRA_ETA, task.etaSeconds);
        update.putExtra(EXTRA_SPEED, task.speed);
        update.putExtra(EXTRA_DOWNLOADED, task.downloadedBytes);
        update.putExtra(EXTRA_TOTAL, task.totalBytes);
        update.putExtra(EXTRA_MESSAGE, task.message);
        sendBroadcast(update);
    }

    private File getTaskWorkDirectory(String taskId) {
        File external = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        File root = external != null
                ? new File(external, "SatLinkWork")
                : new File(getFilesDir(), "SatLinkWork");
        return new File(root, taskId);
    }

    private List<File> findFinalMediaFiles(File directory, TaskState task) {
        List<File> result = new ArrayList<>();
        collectMediaFiles(directory, result);
        String expected = task.mediaType == TYPE_AUDIO ? "mp3" : task.outputExt;
        if (!TextUtils.isEmpty(expected)) {
            List<File> matching = new ArrayList<>();
            for (File file : result) {
                if (expected.equalsIgnoreCase(extension(file.getName()))) matching.add(file);
            }
            if (!matching.isEmpty()) return matching;
        }
        return result;
    }

    private void collectMediaFiles(File file, List<File> output) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) collectMediaFiles(child, output);
            }
            return;
        }
        String name = file.getName().toLowerCase(Locale.US);
        if (name.endsWith(".part") || name.endsWith(".ytdl") || name.endsWith(".tmp")) return;
        String ext = extension(name);
        if ("mp3".equals(ext) || "mp4".equals(ext) || "webm".equals(ext)
                || "mkv".equals(ext) || "ts".equals(ext) || "mpegts".equals(ext)) {
            output.add(file);
        }
    }

    private String publishToDownloads(File source) throws Exception {
        String uniqueName = uniquePublicName(source.getName());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentResolver resolver = getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, uniqueName);
            values.put(MediaStore.Downloads.MIME_TYPE, mimeType(uniqueName));
            values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/SatLink");
            values.put(MediaStore.Downloads.IS_PENDING, 1);
            Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IllegalStateException("تعذر إنشاء الملف داخل التنزيلات.");
            try (InputStream input = new FileInputStream(source);
                 OutputStream output = resolver.openOutputStream(uri, "w")) {
                if (output == null) throw new IllegalStateException("تعذر فتح ملف التنزيل النهائي.");
                copy(input, output);
            } catch (Throwable error) {
                resolver.delete(uri, null, null);
                throw error;
            }
            values.clear();
            values.put(MediaStore.Downloads.IS_PENDING, 0);
            resolver.update(uri, values, null, null);
        } else {
            File publicDir = new File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "SatLink");
            if (!publicDir.exists() && !publicDir.mkdirs()) {
                throw new IllegalStateException("تعذر إنشاء Downloads/SatLink.");
            }
            File destination = new File(publicDir, uniqueName);
            try (InputStream input = new FileInputStream(source);
                 OutputStream output = new FileOutputStream(destination)) {
                copy(input, output);
            }
        }
        return uniqueName;
    }

    private String uniquePublicName(String requested) {
        String clean = sanitizeFilename(requested);
        String ext = extension(clean);
        String base = TextUtils.isEmpty(ext) ? clean : clean.substring(0, clean.length() - ext.length() - 1);
        String suffix = TextUtils.isEmpty(ext) ? "" : "." + ext;
        String candidate = clean;
        int index = 1;
        while (publicNameExists(candidate)) {
            candidate = base + " (" + index + ")" + suffix;
            index++;
        }
        return candidate;
    }

    private boolean publicNameExists(String name) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            String[] projection = {MediaStore.Downloads._ID};
            String selection = MediaStore.Downloads.RELATIVE_PATH + "=? AND "
                    + MediaStore.Downloads.DISPLAY_NAME + "=?";
            String[] args = {Environment.DIRECTORY_DOWNLOADS + "/SatLink/", name};
            try (Cursor cursor = getContentResolver().query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    projection, selection, args, null)) {
                return cursor != null && cursor.moveToFirst();
            } catch (Throwable ignored) {
                return false;
            }
        }
        File publicDir = new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "SatLink");
        return new File(publicDir, name).exists();
    }

    private static void copy(InputStream input, OutputStream output) throws Exception {
        byte[] buffer = new byte[128 * 1024];
        int count;
        while ((count = input.read(buffer)) != -1) {
            output.write(buffer, 0, count);
        }
        output.flush();
    }

    private static String buildProgressMessage(TaskState task, String line) {
        List<String> parts = new ArrayList<>();
        parts.add(task.progress + "%");
        if (task.downloadedBytes > 0 && task.totalBytes > 0) {
            parts.add(formatBytes(task.downloadedBytes) + " / " + formatBytes(task.totalBytes));
        }
        if (!TextUtils.isEmpty(task.speed)) parts.add(task.speed);
        if (task.etaSeconds > 0) parts.add("متبقي " + formatEta(task.etaSeconds));
        if (parts.size() == 1 && !TextUtils.isEmpty(line)) {
            String clean = line.replace('\n', ' ').replace('\r', ' ').trim();
            if (clean.length() > 90) clean = clean.substring(0, 90);
            if (!TextUtils.isEmpty(clean)) parts.add(clean);
        }
        return TextUtils.join(" • ", parts);
    }

    private static String extractSpeed(String line) {
        if (TextUtils.isEmpty(line)) return "";
        Matcher matcher = SPEED_PATTERN.matcher(line);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String formatEta(long seconds) {
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        if (hours > 0) return String.format(Locale.US, "%d:%02d:%02d", hours, minutes, secs);
        return String.format(Locale.US, "%02d:%02d", minutes, secs);
    }

    private static String formatBytes(long bytes) {
        if (bytes <= 0) return "";
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        double value = bytes;
        int unit = 0;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024d;
            unit++;
        }
        return String.format(Locale.US,
                value >= 100 ? "%.0f %s" : value >= 10 ? "%.1f %s" : "%.2f %s",
                value, units[unit]);
    }

    private static String mimeType(String filename) {
        String ext = extension(filename);
        if ("mp3".equals(ext)) return "audio/mpeg";
        if ("webm".equals(ext)) return "video/webm";
        if ("mkv".equals(ext)) return "video/x-matroska";
        if ("ts".equals(ext) || "mpegts".equals(ext)) return "video/mp2t";
        return "video/mp4";
    }

    private static String extension(String name) {
        if (TextUtils.isEmpty(name)) return "";
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "";
        return name.substring(dot + 1).toLowerCase(Locale.US);
    }

    private static String sanitizeFilename(String name) {
        if (TextUtils.isEmpty(name)) return "SatLink_" + System.currentTimeMillis() + ".mp4";
        String clean = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (clean.length() > 190) {
            String ext = extension(clean);
            String suffix = TextUtils.isEmpty(ext) ? "" : "." + ext;
            clean = clean.substring(0, Math.max(1, 190 - suffix.length())) + suffix;
        }
        return clean;
    }

    private static String safeMessage(Throwable error) {
        if (error == null) return "خطأ غير معروف";
        String message = error.getLocalizedMessage();
        if (TextUtils.isEmpty(message)) message = error.getMessage();
        if (TextUtils.isEmpty(message)) message = error.getClass().getSimpleName();
        if (message.length() > 180) message = message.substring(0, 180);
        return message;
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursively(child);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    @Override
    public void onDestroy() {
        for (TaskState task : tasks.values()) {
            if (STATE_RUNNING.equals(task.state)) destroyProcess(task);
        }
        executor.shutdownNow();
        super.onDestroy();
    }

    private static int notificationId(String taskId) {
        return FOREGROUND_BASE_ID + Math.abs(taskId.hashCode() % 15000);
    }

    private static final class TaskState {
        String taskId;
        String sourceUrl;
        String directUrl;
        String directAudioUrl;
        String formatId;
        String outputExt;
        String title;
        int mediaType;
        int audioQuality;
        boolean hasAudio;
        boolean capturedDirect;
        boolean useBrowserSession = true;
        boolean manifest;
        String videoCodec;
        String audioCodec;
        long estimatedSize;
        String cookieFile;
        String cookieHeader;
        String userAgent;

        volatile String processId;
        volatile String state = STATE_QUEUED;
        volatile String message = "";
        volatile int progress;
        volatile long etaSeconds;
        volatile String speed = "";
        volatile long downloadedBytes;
        volatile long totalBytes;
        volatile boolean paused;
        volatile boolean cancelled;
        volatile boolean resumeRequested;
        volatile boolean processStopRequested;
        volatile Process postProcess;
        final AtomicBoolean executing = new AtomicBoolean(false);

        String downloadUrl() {
            if (capturedDirect && !TextUtils.isEmpty(directUrl)) return directUrl;
            // When a yt-dlp format id exists, always re-open the original page URL.
            // Direct videoplayback URLs from YouTube are temporary and often expire before download starts.
            if (!TextUtils.isEmpty(sourceUrl)
                    && (!TextUtils.isEmpty(formatId) || isYouTubeLike(sourceUrl)
                    || isPinterestLike(sourceUrl))) {
                return sourceUrl;
            }
            return TextUtils.isEmpty(directUrl) ? sourceUrl : directUrl;
        }

        static TaskState fromIntent(Intent intent) {
            if (intent == null) return null;
            TaskState task = new TaskState();
            task.taskId = intent.getStringExtra(EXTRA_TASK_ID);
            task.sourceUrl = intent.getStringExtra(EXTRA_SOURCE_URL);
            task.directUrl = intent.getStringExtra(EXTRA_DIRECT_URL);
            task.directAudioUrl = intent.getStringExtra(EXTRA_DIRECT_AUDIO_URL);
            task.formatId = intent.getStringExtra(EXTRA_FORMAT_ID);
            task.outputExt = intent.getStringExtra(EXTRA_OUTPUT_EXT);
            task.title = intent.getStringExtra(EXTRA_TITLE);
            task.mediaType = intent.getIntExtra(EXTRA_MEDIA_TYPE, TYPE_VIDEO);
            task.audioQuality = intent.getIntExtra(EXTRA_AUDIO_QUALITY, 320);
            task.hasAudio = intent.getBooleanExtra(EXTRA_HAS_AUDIO, false);
            task.capturedDirect = intent.getBooleanExtra(EXTRA_CAPTURED_DIRECT, false);
            task.useBrowserSession = intent.getBooleanExtra(EXTRA_USE_BROWSER_SESSION, true);
            task.manifest = intent.getBooleanExtra(EXTRA_MANIFEST, false);
            task.videoCodec = intent.getStringExtra(EXTRA_VIDEO_CODEC);
            task.audioCodec = intent.getStringExtra(EXTRA_AUDIO_CODEC);
            task.estimatedSize = intent.getLongExtra(EXTRA_ESTIMATED_SIZE, 0L);
            task.cookieFile = intent.getStringExtra(EXTRA_COOKIE_FILE);
            task.cookieHeader = intent.getStringExtra(EXTRA_COOKIE_HEADER);
            task.userAgent = intent.getStringExtra(EXTRA_USER_AGENT);
            task.totalBytes = task.estimatedSize;
            if (TextUtils.isEmpty(task.taskId)) return null;
            return task;
        }

        Intent toIntent(Service service, String action) {
            Intent intent = new Intent(service, MediaDownloadService.class);
            intent.setAction(action);
            intent.putExtra(EXTRA_TASK_ID, taskId);
            intent.putExtra(EXTRA_SOURCE_URL, sourceUrl);
            intent.putExtra(EXTRA_DIRECT_URL, directUrl);
            intent.putExtra(EXTRA_DIRECT_AUDIO_URL, directAudioUrl);
            intent.putExtra(EXTRA_FORMAT_ID, formatId);
            intent.putExtra(EXTRA_OUTPUT_EXT, outputExt);
            intent.putExtra(EXTRA_TITLE, title);
            intent.putExtra(EXTRA_MEDIA_TYPE, mediaType);
            intent.putExtra(EXTRA_AUDIO_QUALITY, audioQuality);
            intent.putExtra(EXTRA_HAS_AUDIO, hasAudio);
            intent.putExtra(EXTRA_CAPTURED_DIRECT, capturedDirect);
            intent.putExtra(EXTRA_MANIFEST, manifest);
            intent.putExtra(EXTRA_USE_BROWSER_SESSION, useBrowserSession);
            intent.putExtra(EXTRA_VIDEO_CODEC, videoCodec);
            intent.putExtra(EXTRA_AUDIO_CODEC, audioCodec);
            intent.putExtra(EXTRA_ESTIMATED_SIZE, estimatedSize);
            intent.putExtra(EXTRA_COOKIE_FILE, cookieFile);
            intent.putExtra(EXTRA_COOKIE_HEADER, cookieHeader);
            intent.putExtra(EXTRA_USER_AGENT, userAgent);
            return intent;
        }
    }
}
