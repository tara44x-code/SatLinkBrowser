package com.satlink.geckoviewtest;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.ContentUris;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.URLUtil;
import android.text.TextUtils;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.TextView;
import android.widget.Toast;
import android.webkit.MimeTypeMap;

import com.yausername.ffmpeg.FFmpeg;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import com.yausername.youtubedl_android.YoutubeDLResponse;

import org.json.JSONArray;
import org.json.JSONObject;
import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.MediaSession;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckoview.WebExtension;
import org.mozilla.geckoview.WebExtensionController;
import org.mozilla.geckoview.WebResponse;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import kotlin.Unit;
import kotlin.jvm.functions.Function3;

public class MainActivity extends Activity {
    private static final String TAG = "SatLinkMedia";
    private static final String EXTENSION_LOCATION = "resource://android/assets/media_capture/";
    private static final String EXTENSION_ID = "media-capture@satlink.local";
    private static final String NATIVE_APP = "satlink_capture";
    private static final int STORAGE_REQUEST = 2001;
    private static final int NOTIFICATION_REQUEST = 2002;
    private static final int XPI_PICK_REQUEST = 2003;

    private static GeckoRuntime runtime;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService engineUpdater = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService analysisWatchdog = Executors.newSingleThreadScheduledExecutor();
    private volatile String activeAnalysisProcess;
    private volatile int engineRevision;
    private String lastAnalysisError = "";
    private JSONArray lastPlayerFormats;
    private final AtomicBoolean engineReady = new AtomicBoolean(false);
    private final AtomicBoolean engineFailed = new AtomicBoolean(false);
    private final AtomicBoolean analyzing = new AtomicBoolean(false);

    private GeckoSession session;
    private GeckoView geckoView;
    private final List<BrowserTab> browserTabs = new ArrayList<>();
    private TextView tabsButton;
    private boolean destroyingTabs;
    private BrowserWorkspace browserWorkspace = new BrowserWorkspace();
    private Button forwardPageButton;
    private boolean restoringWorkspace;
    private final Runnable browserWorkspaceSave = () -> persistBrowserWorkspace();

    private WebExtension captureExtension;
    private WebExtension.Port capturePort;
    private final Set<WebExtension.Port> capturePorts = ConcurrentHashMap.newKeySet();

    private EditText addressBar;
    private ProgressBar progressBar;
    private ProgressBar analysisProgress;
    private Button backButton;
    private Button refreshPageButton;
    private Button captureButton;
    private View browserHeader;
    private View browserToolbar;
    private View bottomNavigation;
    private boolean inPageFullscreen = false;
    private FullscreenDisplayControls fullscreenDisplayControls;
    private FullscreenPlaybackControls fullscreenPlaybackControls;
    private final ExecutorService fileSaver = Executors.newSingleThreadExecutor();
    private boolean backNavigationPending;
    private boolean returnToBrowserFromHome;
    private long lastHomeBackPress;
    private Object systemBackCallback;
    private LinearLayout capturePanel;
    private LinearLayout videoResults;
    private LinearLayout audioResults;
    private ScrollView videoScroll;
    private ScrollView audioScroll;
    private TextView captureStatus;
    private TextView captureTitle;
    private TextView videoCount;
    private TextView audioCount;
    private TextView videoArrow;
    private TextView audioArrow;

    private View homeOverlay;
    private View downloadsOverlay;
    private View extensionsOverlay;
    private View tabsOverlay;
    private View favoritesOverlay;
    private LinearLayout favoritesList;
    private TextView favoritesEmpty;
    private EditText favoritesSearch;
    private Button addFavoriteButton;
    private Button showFavoritesButton;
    private final List<FavoriteItem> favorites = new ArrayList<>();
    private LinearLayout shortcutGrid;
    private LinearLayout downloadsList;
    private LinearLayout extensionsList;
    private LinearLayout tabsList;
    private EditText homeSearch;
    private EditText extensionUrlInput;
    private TextView navHome;
    private TextView navDownloads;
    private TextView navExtensions;
    private TextView navTabs;
    private TextView headerExtensionsButton;
    private String activeScreen = "home";

    private String pendingPageUrl;
    private String pendingPageTitle;
    private JSONArray lastCapturedItems;
    private volatile String preferredPlayerPageUrl;
    private volatile String latestUserAgent = "Mozilla/5.0 (Android 14; Mobile; rv:150.0) Gecko/150.0 Firefox/150.0";
    private boolean sharedNavigationPending;
    private boolean activityForeground = true;
    private boolean browserCanGoBack;
    private MediaSession browserMediaControl;
    private GeneralFileDownloads generalFileDownloads;
    private String latestActivePostUrl = "";
    private String latestActiveMediaKey = "";
    private volatile String latestCookieFilePath;
    private String latestCookieContents = "";
    private volatile String latestCookieHeader;
    private volatile String latestOrigin;
    private double capturedDurationSeconds;
    private volatile int captureRequestSerial;
    private int analyzedRequestSerial = -1;
    private boolean detailedFormatsShown;
    private long lastQuickRender;

    private final Map<String, DownloadUiBinding> downloadBindings = new ConcurrentHashMap<>();
    private BroadcastReceiver downloadReceiver;
    private final Runnable generalDownloadsRefresh = () -> {
        if (activityForeground && "downloads".equals(activeScreen)) refreshDownloads();
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        try {
            String version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            TextView badge = findViewById(R.id.proVersionBadge);
            if (version != null) badge.setText(version.replaceFirst("^(PRO \\S+).*", "$1"));
        } catch (Exception error) {Log.w(TAG, "Could not read app version", error);}
        registerDownloadReceiver();
        requestNotificationPermissionIfNeeded();

        geckoView = findViewById(R.id.geckoView);
        addressBar = findViewById(R.id.addressBar);
        progressBar = findViewById(R.id.progressBar);
        analysisProgress = findViewById(R.id.analysisProgress);
        backButton = findViewById(R.id.backButton);
        refreshPageButton = findViewById(R.id.refreshPageButton);
        Button goButton = findViewById(R.id.goButton);
        captureButton = findViewById(R.id.captureButton);
        browserHeader = findViewById(R.id.browserHeader);
        browserToolbar = findViewById(R.id.browserToolbar);
        bottomNavigation = findViewById(R.id.bottomNavigation);
        captureButton.setVisibility(View.GONE);
        Button closeCaptureButton = findViewById(R.id.closeCaptureButton);
        capturePanel = findViewById(R.id.capturePanel);
        videoResults = findViewById(R.id.videoResults);
        audioResults = findViewById(R.id.audioResults);
        videoScroll = findViewById(R.id.videoScroll);
        audioScroll = findViewById(R.id.audioScroll);
        captureStatus = findViewById(R.id.captureStatus);
        captureTitle = findViewById(R.id.captureTitle);
        videoCount = findViewById(R.id.videoCount);
        audioCount = findViewById(R.id.audioCount);
        videoArrow = findViewById(R.id.videoArrow);
        audioArrow = findViewById(R.id.audioArrow);
        homeOverlay = findViewById(R.id.homeOverlay);
        downloadsOverlay = findViewById(R.id.downloadsOverlay);
        extensionsOverlay = findViewById(R.id.extensionsOverlay);
        tabsOverlay = findViewById(R.id.tabsOverlay);
        favoritesOverlay = findViewById(R.id.favoritesOverlay);
        favoritesList = findViewById(R.id.favoritesList);
        favoritesEmpty = findViewById(R.id.favoritesEmpty);
        favoritesSearch = findViewById(R.id.favoritesSearch);
        addFavoriteButton = findViewById(R.id.addFavoriteButton);
        showFavoritesButton = findViewById(R.id.showFavoritesButton);
        shortcutGrid = findViewById(R.id.shortcutGrid);
        downloadsList = findViewById(R.id.downloadsList);
        extensionsList = findViewById(R.id.extensionsList);
        tabsList = findViewById(R.id.tabsList);
        homeSearch = findViewById(R.id.homeSearch);
        extensionUrlInput = findViewById(R.id.extensionUrlInput);
        navHome = findViewById(R.id.navHome);
        navDownloads = findViewById(R.id.navDownloads);
        navExtensions = findViewById(R.id.navExtensions);
        navTabs = findViewById(R.id.navTabs);
        headerExtensionsButton = findViewById(R.id.headerExtensionsButton);
        LinearLayout videoHeader = findViewById(R.id.videoHeader);
        LinearLayout audioHeader = findViewById(R.id.audioHeader);

        if (runtime == null) {
            runtime = GeckoRuntime.create(getApplicationContext());
        }
        configureExtensionPrompts();
        browserWorkspace = BrowserWorkspace.decode(getSharedPreferences("satlink_browser", MODE_PRIVATE)
                .getString("workspace", ""));
        SharedPreferences browserPrefs = getSharedPreferences("satlink_browser", MODE_PRIVATE);
        if (!browserPrefs.getBoolean("start_on_home_v1", false)) {
            // A fresh start opens the home screen, not the last page. Can be turned back on in settings.
            browserWorkspace.restoreTabs = false;
            browserPrefs.edit().putBoolean("start_on_home_v1", true)
                    .putString("workspace", browserWorkspace.encode()).apply();
        }
        forwardPageButton = findViewById(R.id.forwardPageButton);
        forwardPageButton.setOnClickListener(v -> goForwardInBrowser());

        tabsButton = findViewById(R.id.browserTabsButton);
        tabsButton.setOnClickListener(v -> showTabsScreen());
        session = createTabSession();
        browserTabs.add(new BrowserTab(session));
        session.open(runtime);
        geckoView.setSession(session);
        updateTabCount();

        installCaptureExtension();
        initializeDownloadEngine();
        refreshExtensionToolbar();

        goButton.setOnClickListener(v -> loadFromAddressBar());
        refreshPageButton.setOnClickListener(v -> refreshCurrentPage());
        setupPullToRefresh();
        closeCaptureButton.setOnClickListener(v -> closeCapturePanel());
        videoHeader.setOnClickListener(v -> toggleVideoList());
        audioHeader.setOnClickListener(v -> toggleAudioList());

        findViewById(R.id.homeGoButton).setOnClickListener(v -> loadFromHomeSearch());
        findViewById(R.id.homeDownloadsButton).setOnClickListener(v -> showDownloadsScreen());
        findViewById(R.id.refreshDownloadsButton).setOnClickListener(v -> refreshDownloads());
        findViewById(R.id.installExtensionButton).setOnClickListener(v -> installExtensionFromInput());
        findViewById(R.id.installExtensionFileButton).setOnClickListener(v -> pickExtensionFromDevice());
        findViewById(R.id.openAddonsStoreButton).setOnClickListener(v -> openUrl("https://addons.mozilla.org/android/"));
        findViewById(R.id.refreshExtensionsButton).setOnClickListener(v -> refreshExtensions());
        findViewById(R.id.newTabButton).setOnClickListener(v -> startNewTab());
        navHome.setOnClickListener(v -> {
            boolean fromPage = "browser".equals(activeScreen) && currentTabHasPage();
            showHomeScreen();
            returnToBrowserFromHome = fromPage;
        });
        navDownloads.setOnClickListener(v -> showDownloadsScreen());
        navExtensions.setOnClickListener(v -> showExtensionsScreen());
        navTabs.setOnClickListener(v -> showTabsScreen());
        findViewById(R.id.navCapture).setOnClickListener(v -> {
            if ("browser".equals(activeScreen)) requestMediaScan();
            else if (!TextUtils.isEmpty(addressBar.getText().toString())) {
                showBrowserScreen();
                requestMediaScan();
            } else {
                Toast.makeText(this, "افتح صفحة فيديو أولاً", Toast.LENGTH_SHORT).show();
            }
        });
        findViewById(R.id.headerMenuButton).setOnClickListener(v -> showBrowserMenu());
        headerExtensionsButton.setOnClickListener(v -> showExtensionQuickMenu());
        addFavoriteButton.setOnClickListener(v -> toggleCurrentFavorite());
        showFavoritesButton.setOnClickListener(v -> showFavoritesScreen());
        findViewById(R.id.closeFavoritesButton).setOnClickListener(v -> {
            if (!TextUtils.isEmpty(addressBar.getText().toString()) && !"about:blank".equals(addressBar.getText().toString())) showBrowserScreen();
            else showHomeScreen();
        });
        favoritesSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { renderFavorites(s == null ? "" : s.toString()); }
            @Override public void afterTextChanged(Editable s) {}
        });
        loadFavorites();
        updateFavoriteButtonState();

        homeSearch.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO ||
                    (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                loadFromHomeSearch();
                return true;
            }
            return false;
        });

        addressBar.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO ||
                    (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                loadFromAddressBar();
                return true;
            }
            return false;
        });

        backButton.setOnClickListener(v -> goBackInBrowser());
        registerSystemBack();

        renderShortcuts();
        boolean restored = restoreBrowserWorkspace();
        cleanStaleCookieFiles();
        if (savedInstanceState == null && getIntent() != null
                && getIntent().getBooleanExtra(FileDownloadService.EXTRA_OPEN_DOWNLOADS, false)) {
            if (!restored) session.loadUri("about:blank");
            showDownloadsScreen();
            return;
        }
        if (savedInstanceState == null && handleViewLink(getIntent())) return;
        if (savedInstanceState == null && handleSharedLink(getIntent())) return;
        if (!restored) {
            session.loadUri("about:blank");
            showHomeScreen();
        }

    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && intent.getBooleanExtra(FileDownloadService.EXTRA_OPEN_DOWNLOADS, false)) {
            intent.removeExtra(FileDownloadService.EXTRA_OPEN_DOWNLOADS);
            showDownloadsScreen();
            return;
        }
        if (handleViewLink(intent)) return;
        handleSharedLink(intent);
    }

    /** Opens an http/https link sent by another app (ACTION_VIEW) in its own tab. */
    private boolean handleViewLink(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction()) || intent.getData() == null) return false;
        String url = intent.getData().toString();
        if (!GeneralFilePolicy.http(url)) return false;
        // A recreated activity must not open the same link twice.
        intent.setAction(null);
        intent.setData(null);
        if (currentTabHasPage()) openInNewTab(url, false);
        else {
            closeCapturePanel();
            openUrl(url);
        }
        return true;
    }

    private boolean currentTabHasPage() {
        BrowserTab tab = findTab(session);
        return tab != null && !TextUtils.isEmpty(tab.url) && !"about:blank".equals(tab.url);
    }

    /** Session cookie snapshots are only needed while a page or its download is active. */
    private void cleanStaleCookieFiles() {
        final File cache = getCacheDir();
        worker.execute(() -> {
            try {
                File[] files = cache.listFiles();
                if (files == null) return;
                long limit = System.currentTimeMillis() - 7L * 24L * 60L * 60L * 1000L;
                for (File file : files) {
                    String name = file.getName();
                    if (name.startsWith("satlink_cookies_") && name.endsWith(".txt") && file.lastModified() < limit) {
                        if (!file.delete()) Log.w(TAG, "Could not remove old cookie snapshot");
                    }
                }
            } catch (Throwable error) { Log.w(TAG, "Cookie snapshot cleanup failed", error); }
        });
    }

    private boolean handleSharedLink(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) return false;
        CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
        if (text == null && intent.getClipData() != null && intent.getClipData().getItemCount() > 0)
            text = intent.getClipData().getItemAt(0).getText();
        String url = SharedUrlParser.firstHttpUrl(text);
        if (url.isEmpty()) {
            Toast.makeText(this, "المشاركة لا تحتوي رابط فيديو صالحاً", Toast.LENGTH_LONG).show();
            return false;
        }
        closeCapturePanel();
        lastCapturedItems = null;
        lastPlayerFormats = null;
        latestActivePostUrl = "";
        latestActiveMediaKey = "";
        capturedDurationSeconds = 0;
        latestCookieHeader = "";
        latestCookieFilePath = "";
        latestOrigin = "";
        latestUserAgent = "";
        preferredPlayerPageUrl = "";
        pendingPageTitle = "";
        pendingPageUrl = normalizePageUrl(url);
        sharedNavigationPending = true;
        openUrl(url);
        requestMediaScan();
        // A recreated activity must not consume the same share twice.
        intent.setAction(null);
        intent.removeExtra(Intent.EXTRA_TEXT);
        return true;
    }

    private void refreshCurrentPage() {
        if (session == null || !"browser".equals(activeScreen)) return;
        try {
            BrowserTab tab = findTab(session);
            if (tab != null && tab.loading) {
                session.stop();tab.loading = false;refreshPageButton.setText("↻");
                progressBar.setVisibility(View.GONE);
            } else {
                session.reload();
                Toast.makeText(this, "جاري إنعاش الصفحة", Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable error) {
            Log.w(TAG, "Page reload failed", error);
        }
    }

    private void setupPullToRefresh() {
        final float[] downY = {0f};
        final boolean[] tracking = {false};
        geckoView.setOnTouchListener((view, event) -> {
            if (!"browser".equals(activeScreen)) return false;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downY[0] = event.getY();
                    tracking[0] = downY[0] <= dp(180);
                    return false;
                case MotionEvent.ACTION_UP:
                    if (tracking[0] && event.getY() - downY[0] >= dp(120)) {
                        refreshCurrentPage();
                    }
                    tracking[0] = false;
                    return false;
                case MotionEvent.ACTION_CANCEL:
                    tracking[0] = false;
                    return false;
                default:
                    return false;
            }
        });
    }

    private void setupFloatingCaptureButton() {
        final float[] downRawX = {0f};
        final float[] downRawY = {0f};
        final float[] startX = {0f};
        final float[] startY = {0f};
        final boolean[] moved = {false};
        captureButton.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downRawX[0] = event.getRawX();
                    downRawY[0] = event.getRawY();
                    startX[0] = view.getX();
                    startY[0] = view.getY();
                    moved[0] = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - downRawX[0];
                    float dy = event.getRawY() - downRawY[0];
                    if (Math.abs(dx) > dp(6) || Math.abs(dy) > dp(6)) moved[0] = true;
                    float maxX = Math.max(0, ((View)view.getParent()).getWidth() - view.getWidth());
                    float maxY = Math.max(0, ((View)view.getParent()).getHeight() - view.getHeight());
                    view.setX(Math.max(0, Math.min(startX[0] + dx, maxX)));
                    view.setY(Math.max(0, Math.min(startY[0] + dy, maxY)));
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!moved[0]) showIndependentFloatingCatcher();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    return true;
                default:
                    return false;
            }
        });
    }


    private void showIndependentFloatingCatcher() {
        final Dialog dialog = new Dialog(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(16), dp(18), dp(16));
        box.setBackground(roundedBackground(Color.rgb(18, 14, 24), Color.rgb(255, 75, 159), 20));

        TextView title = new TextView(this);
        title.setText("SatLink Media Catcher PRO");
        title.setTextColor(Color.WHITE);
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(title);

        TextView info = new TextView(this);
        info.setText("أداة مستقلة عن زر الالتقاط القديم. تضغط بدء الالتقاط وتظهر النتائج في لوحة التحميل.");
        info.setTextColor(Color.LTGRAY);
        info.setTextSize(13);
        LinearLayout.LayoutParams infoParams = new LinearLayout.LayoutParams(-1, -2);
        infoParams.topMargin = dp(10);
        box.addView(info, infoParams);

        Button scan = new Button(this);
        scan.setText("التقاط الفيديو والصوت");
        scan.setTextColor(Color.WHITE);
        scan.setTextSize(14);
        scan.setAllCaps(false);
        scan.setBackgroundResource(R.drawable.capture_button_background);
        LinearLayout.LayoutParams scanParams = new LinearLayout.LayoutParams(-1, dp(50));
        scanParams.topMargin = dp(16);
        box.addView(scan, scanParams);

        Button close = new Button(this);
        close.setText("إغلاق");
        close.setTextColor(Color.WHITE);
        close.setTextSize(13);
        close.setAllCaps(false);
        close.setBackgroundResource(R.drawable.toolbar_icon_background);
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(-1, dp(46));
        closeParams.topMargin = dp(10);
        box.addView(close, closeParams);

        scan.setOnClickListener(v -> {
            dialog.dismiss();
            requestMediaScan();
        });
        close.setOnClickListener(v -> dialog.dismiss());

        dialog.setContentView(box);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setGravity(Gravity.CENTER);
        }
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout((int)(getResources().getDisplayMetrics().widthPixels * 0.90f),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private void applyPageFullscreen(boolean fullscreen) {
        inPageFullscreen = fullscreen;
        if (fullscreen && fullscreenDisplayControls == null) fullscreenDisplayControls = new FullscreenDisplayControls(this);
        if (fullscreenDisplayControls != null) fullscreenDisplayControls.setFullscreen(fullscreen);
        if (fullscreen && fullscreenPlaybackControls == null)
            fullscreenPlaybackControls = new FullscreenPlaybackControls(this, this::sendPlayerControl);
        if (fullscreenPlaybackControls != null) fullscreenPlaybackControls.setFullscreen(fullscreen);
        int uiVisibility = fullscreen
                ? (View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
                : View.SYSTEM_UI_FLAG_VISIBLE;
        getWindow().getDecorView().setSystemUiVisibility(uiVisibility);
        if (browserHeader != null) browserHeader.setVisibility(fullscreen ? View.GONE : View.VISIBLE);
        if (bottomNavigation != null) bottomNavigation.setVisibility(fullscreen ? View.GONE : View.VISIBLE);
        if (progressBar != null) progressBar.setVisibility(fullscreen ? View.GONE : progressBar.getVisibility());
        if (captureButton != null) {
            if (fullscreen) captureButton.setVisibility(View.GONE);
            else captureButton.setVisibility(View.GONE);
        }
        if (capturePanel != null && fullscreen) capturePanel.setVisibility(View.GONE);
    }

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent event) {
        boolean handled = super.dispatchTouchEvent(event);
        if (fullscreenDisplayControls != null) fullscreenDisplayControls.onScreenTouch(event);
        if (fullscreenPlaybackControls != null) fullscreenPlaybackControls.onScreenTouch(event);
        return handled;
    }

    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        boolean handled = super.dispatchKeyEvent(event);
        if (fullscreenDisplayControls != null) fullscreenDisplayControls.onScreenKey(event);
        if (fullscreenPlaybackControls != null) fullscreenPlaybackControls.onScreenKey(event);
        return handled;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void initializeDownloadEngine() {
        worker.execute(() -> {
            try {
                EngineBootstrap.initialize(getApplicationContext());

                engineReady.set(true);
                runOnUiThread(() -> {
                    if (capturePanel.getVisibility() == View.VISIBLE &&
                            !TextUtils.isEmpty(pendingPageUrl) && !analyzing.get()) {
                        analyzePage(pendingPageUrl);
                    }
                });
                engineUpdater.execute(() -> {
                    // YouTube changes frequently. Refresh yt-dlp at most once every 24 hours.
                    // Failure to update never blocks the bundled engine.
                    long now = System.currentTimeMillis();
                    long lastUpdate = getSharedPreferences("satlink_engine", MODE_PRIVATE)
                            .getLong("last_ytdlp_update", 0L);
                    if (now - lastUpdate > 24L * 60L * 60L * 1000L) {
                        try {
                            YoutubeDL.getInstance().updateYoutubeDL(
                                    getApplicationContext(), YoutubeDL.UpdateChannel._NIGHTLY);
                            engineRevision++;
                            runOnUiThread(() -> {
                                if (capturePanel.getVisibility() == View.VISIBLE && !hasStartedCaptureDownload() && !analyzing.get()) {
                                    analyzedRequestSerial = -1;
                                    if (!TextUtils.isEmpty(pendingPageUrl)) analyzePage(pendingPageUrl);
                                }
                            });
                            getSharedPreferences("satlink_engine", MODE_PRIVATE)
                                    .edit().putLong("last_ytdlp_update", now).apply();
                        } catch (Throwable updateError) {
                            Log.w(TAG, "yt-dlp update skipped; bundled engine will be used", updateError);
                        }
                    }

                });
            } catch (Throwable error) {
                engineFailed.set(true);
                Log.e(TAG, "Failed to initialize yt-dlp/FFmpeg", error);
                runOnUiThread(() -> {
                    if (capturePanel.getVisibility() == View.VISIBLE) {
                        analysisProgress.setVisibility(View.GONE);
                        captureStatus.setText("تعذر تشغيل محرك الجودات: " + safeMessage(error));
                        showCapturedFallback(lastCapturedItems);
                    }
                });
            }
        });
    }

    private void installCaptureExtension() {
        runtime.getWebExtensionController()
                .ensureBuiltIn(EXTENSION_LOCATION, EXTENSION_ID)
                .accept(extension -> {
                    captureExtension = extension;
                    CaptureMessageDelegate delegate = new CaptureMessageDelegate();
                    // Runtime delegate receives messages from the background script; the session
                    // delegate receives messages sent directly by the active page content script.
                    extension.setMessageDelegate(delegate, NATIVE_APP);
                    for (BrowserTab tab : browserTabs) tab.session.getWebExtensionController()
                            .setMessageDelegate(extension, delegate, NATIVE_APP);
                }, error -> Log.e(TAG, "Extension registration failed", error));
    }

    private void requestMediaScan() {
        capturePanel.setVisibility(View.VISIBLE);
        resetFormatLists();
        preferredPlayerPageUrl = "";
        captureTitle.setText("خيارات التحميل");
        captureStatus.setText("جاري التقاط جلسة الصفحة ثم تحليل الجودات...");
        analysisProgress.setVisibility(View.VISIBLE);

        String currentUrl = normalizePageUrl(addressBar.getText().toString().trim());
        if (!TextUtils.isEmpty(currentUrl)) pendingPageUrl = currentUrl;

        stopActiveAnalysis();
        final int requestSerial = ++captureRequestSerial;
        lastAnalysisError = "";
        detailedFormatsShown = false;
        lastQuickRender = 0;
        if (lastCapturedItems != null) showCapturedFallback(lastCapturedItems);
        boolean extensionRequested = requestScanFromAllFrames();
        captureButton.postDelayed(() -> {
            if (requestSerial == captureRequestSerial && capturePanel.getVisibility() == View.VISIBLE)
                requestScanFromAllFrames();
        }, 600L);

        if (engineFailed.get()) {
            analysisProgress.setVisibility(View.GONE);
            captureStatus.setText("محرك الجودات غير متاح؛ جاري فحص الروابط المباشرة داخل الصفحة.");
            showCapturedFallback(lastCapturedItems);
            return;
        }
        if (!engineReady.get()) {
            captureStatus.setText("محرك الجودات يتهيأ لأول تشغيل...");
            return;
        }

        if (!extensionRequested && !TextUtils.isEmpty(pendingPageUrl)) {
            analyzePage(pendingPageUrl);
            return;
        }

        // Give the extension a brief moment to return browser cookies, the exact User-Agent and
        // media requests. This prevents starting a second cookie-less YouTube request first.
        captureButton.postDelayed(() -> {
            if (requestSerial != captureRequestSerial || capturePanel.getVisibility() != View.VISIBLE || analyzing.get()) return;
            if (!TextUtils.isEmpty(pendingPageUrl)) analyzePage(pendingPageUrl);
        }, 800L);
    }

    private boolean requestScanFromAllFrames() {
        JSONObject message = new JSONObject();
        try { message.put("action", "scanMedia"); } catch (Exception ignored) {}
        boolean sent = false;
        for (WebExtension.Port port : new ArrayList<>(capturePorts)) {
            if (port.sender.session != session) continue;
            try {
                port.postMessage(message);
                sent = true;
            } catch (Throwable error) {
                capturePorts.remove(port);
                Log.w(TAG, "Disconnected capture frame removed", error);
            }
        }
        if (!sent && capturePort != null && capturePort.sender.session == session) {
            try { capturePort.postMessage(message); sent = true; }
            catch (Throwable error) { Log.w(TAG, "Could not request media scan", error); }
        }
        return sent;
    }

    private String strongestManifestUrl(JSONArray items) {
        if (items == null) return "";
        String mpd = "";
        for (int i = items.length() - 1; i >= 0; i--) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            String url = item.optString("url", "");
            String mime = item.optString("mime", "").toLowerCase(Locale.US);
            String lower = url.toLowerCase(Locale.US);
            if (lower.contains(".m3u8") || mime.contains("mpegurl")) return url;
            if (TextUtils.isEmpty(mpd) && (lower.contains(".mpd") || mime.contains("dash+xml"))) mpd = url;
        }
        return mpd;
    }

    private void updateSessionData(JSONObject json) {
        if (json == null) return;
        String pageUrl = json.optString("pageUrl", pendingPageUrl == null ? "" : pendingPageUrl);
        try {
            Uri uri = Uri.parse(pageUrl);
            if (!TextUtils.isEmpty(uri.getScheme()) && !TextUtils.isEmpty(uri.getHost()))
                latestOrigin = uri.getScheme() + "://" + uri.getHost();
        } catch (Throwable ignored) {}
        JSONArray cookies = json.optJSONArray("cookies");
        if (cookies == null) return;
        if (cookies.length() == 0) {
            latestCookieHeader = "";
            latestCookieFilePath = "";
            return;
        }
        StringBuilder header = new StringBuilder();
        StringBuilder netscape = new StringBuilder("# Netscape HTTP Cookie File\n");
        for (int i = 0; i < cookies.length(); i++) {
            JSONObject c = cookies.optJSONObject(i);
            if (c == null) continue;
            String name = c.optString("name", "");
            String value = c.optString("value", "");
            if (TextUtils.isEmpty(name)) continue;
            if (header.length() > 0) header.append("; ");
            header.append(name).append('=').append(value);
            String domain = c.optString("domain", "");
            String path = c.optString("path", "/");
            boolean secure = c.optBoolean("secure", false);
            long expiry = (long)c.optDouble("expirationDate", 0);
            netscape.append(cleanCookieField(domain)).append('\t').append(domain.startsWith(".") ? "TRUE" : "FALSE")
                    .append('\t').append(cleanCookieField(path)).append('\t').append(secure ? "TRUE" : "FALSE")
                    .append('\t').append(expiry).append('\t').append(cleanCookieField(name)).append('\t').append(cleanCookieField(value)).append('\n');
        }
        latestCookieHeader = header.toString();
        try {
            if (netscape.toString().equals(latestCookieContents)
                    && !TextUtils.isEmpty(latestCookieFilePath) && new File(latestCookieFilePath).isFile()) return;
            File f = File.createTempFile("satlink_cookies_", ".txt", getCacheDir());
            try (FileOutputStream out = new FileOutputStream(f, false)) {
                out.write(netscape.toString().getBytes(StandardCharsets.UTF_8));
            }
            latestCookieFilePath = f.getAbsolutePath();
            latestCookieContents = netscape.toString();
        } catch (Throwable error) { Log.w(TAG, "Cookie file creation failed", error); }
    }

    private void onCaptureResult(JSONObject json) {
        runOnUiThread(() -> {
            String incomingPage = normalizePageUrl(json.optString("pageUrl", ""));
            String activePage = normalizePageUrl(addressBar.getText().toString().trim());
            if (!TextUtils.isEmpty(activePage) && !activePage.equals(incomingPage)) return;
            pendingPageUrl = normalizePageUrl(json.optString("pageUrl", addressBar.getText().toString().trim()));
            pendingPageTitle = json.optString("title", "");
            String activePost = ActiveMediaPage.validTikTokPost(json.optString("activePostUrl", ""));
            String mediaKey = json.optString("activeMediaKey", "");
            if (!activePost.equals(latestActivePostUrl) || !mediaKey.equals(latestActiveMediaKey)) {
                latestActivePostUrl = activePost;
                latestActiveMediaKey = mediaKey;
                captureRequestSerial++;
                stopActiveAnalysis();
                detailedFormatsShown = false;
                lastQuickRender = 0;
            }
            lastCapturedItems = json.optJSONArray("items");
            lastPlayerFormats = json.optJSONArray("playerFormats");
            capturedDurationSeconds = numberToDouble(json.opt("duration"));
            if (capturedDurationSeconds <= 0 && lastCapturedItems != null) {
                for (int i = 0; i < lastCapturedItems.length(); i++) {
                    JSONObject item = lastCapturedItems.optJSONObject(i);
                    if (item != null) {
                        capturedDurationSeconds = Math.max(capturedDurationSeconds,
                                numberToDouble(item.opt("duration")));
                    }
                }
            }

            String pageUserAgent = json.optString("userAgent", "");
            if (!TextUtils.isEmpty(pageUserAgent)) latestUserAgent = pageUserAgent;
            updateSessionData(json);

            if (capturePanel.getVisibility() != View.VISIBLE) return;
            // On YouTube the page title can still be the previous video's; the analysis sets it.
            if (!TextUtils.isEmpty(pendingPageTitle) && !isYouTubeUrl(pendingPageUrl)) captureTitle.setText(pendingPageTitle);
            long now = android.os.SystemClock.elapsedRealtime();
            if (!detailedFormatsShown && now - lastQuickRender >= 500) {
                showCapturedFallback(lastCapturedItems);
                lastQuickRender = now;
            }

            if (engineReady.get()) {
                // YouTube must always be analyzed from the original watch page. Passing a
                // temporary videoplayback/manifest URL to yt-dlp returns only one direct stream
                // and loses resolution, FPS, codec, audio and size metadata.
                if (isYouTubeUrl(pendingPageUrl)) {
                    // Keep the proven YouTube path exactly as it was: always analyze the watch page.
                    analyzePage(pendingPageUrl);
                } else if (isPornhubUrl(pendingPageUrl)) {
                    // Pornhub signed streams must be extracted from the original watch page with
                    // the browser session. Never send a fragment such as index-v1-a1 to yt-dlp.
                    analyzePage(pendingPageUrl);
                } else if (isEgyBestUrl(pendingPageUrl)) {
                    // EgyBest hosts the real player inside a third-party frame. Analyze that server
                    // page first; if no player frame was found, use the strongest HLS/DASH request.
                    preferredPlayerPageUrl = strongestEgyBestServerPage(lastCapturedItems);
                    String manifestUrl = strongestManifestUrl(lastCapturedItems);
                    analyzePage(!TextUtils.isEmpty(preferredPlayerPageUrl)
                            ? preferredPlayerPageUrl
                            : (TextUtils.isEmpty(manifestUrl) ? pendingPageUrl : manifestUrl));
                } else {
                    // Site extractors need the original post, not a single CDN rendition.
                    analyzePage(pendingPageUrl);
                }
            } else if (engineFailed.get()) {
                analysisProgress.setVisibility(View.GONE);
                captureStatus.setText("تم الالتقاط، لكن محرك الجودات لم يتهيأ. عرض الروابط المباشرة المتاحة.");
                showCapturedFallback(lastCapturedItems);
            } else {
                captureStatus.setText("تم الالتقاط. محرك الجودات يتهيأ لأول تشغيل...");
            }
        });
    }

    private void onSessionUpdate(JSONObject json) {
        if (json == null) return;
        if (!normalizePageUrl(addressBar.getText().toString().trim())
                .equals(normalizePageUrl(json.optString("pageUrl", "")))) return;
        String pageUserAgent = json.optString("userAgent", "");
        if (!TextUtils.isEmpty(pageUserAgent)) latestUserAgent = pageUserAgent;
        updateSessionData(json);
    }

    private void analyzePage(String pageUrl) {
        final String normalizedPageUrl = normalizePageUrl(ActiveMediaPage.resolve(pageUrl, latestActivePostUrl));
        if (TextUtils.isEmpty(normalizedPageUrl)) {
            captureStatus.setText("جاري تحديد رابط المقطع الظاهر. افتح المقطع أو شارك رابطه مع التطبيق إذا لم يظهر.");
            analysisProgress.setVisibility(View.GONE);
            return;
        }
        if (isFinishing() || isDestroyed() || TextUtils.isEmpty(normalizedPageUrl) || !engineReady.get() || capturePanel.getVisibility() != View.VISIBLE
                || analyzedRequestSerial == captureRequestSerial || analyzing.getAndSet(true)) {
            return;
        }

        final int analysisSerial = captureRequestSerial;
        final int revisionAtStart = engineRevision;
        lastAnalysisError = "";
        analyzedRequestSerial = analysisSerial;
        analysisProgress.setVisibility(View.VISIBLE);
        captureStatus.setText("جاري قراءة الدقة والحجم والفريمات والصوتيات...");

        worker.execute(() -> {
            try {
                JSONObject root;
                if (isYouTubeUrl(normalizedPageUrl)) {
                    // YouTube-only path. Do not alter the proven extraction path for any other site.
                    root = executeYouTubeAnalysis(normalizedPageUrl);
                } else {
                    root = executeAnalysis(normalizedPageUrl);
                }

                FormatCollection collection = parseFormats(root);
                if (collection.videos.isEmpty() && collection.audios.isEmpty())
                    throw new IllegalStateException("المحرك لم يرجع صيغ فيديو أو صوت قابلة للتنزيل");
                runOnUiThread(() -> {
                    if (analysisSerial == captureRequestSerial && capturePanel.getVisibility() == View.VISIBLE) {
                        showAnalyzedFormats(collection);
                        detailedFormatsShown = !collection.videos.isEmpty() || !collection.audios.isEmpty();
                    }
                });
            } catch (Throwable error) {
                Log.e(TAG, "Format analysis failed", error);
                runOnUiThread(() -> {
                    if (analysisSerial != captureRequestSerial || capturePanel.getVisibility() != View.VISIBLE) return;
                    lastAnalysisError = friendlyAnalysisError(error) + "\n" + safeMessage(error);
                    if (looksLikeYouTubeThrottle(error)) {
                        captureStatus.setText("يوتيوب وضع حظراً مؤقتاً على الطلبات. عرض الروابط المباشرة الملتقطة من المشغّل.");
                    } else {
                        captureStatus.setText("تعذر تحليل الجودات من رابط الصفحة. عرض الالتقاط المباشر المتاح.");
                    }
                    showCapturedFallback(lastCapturedItems);
                    Toast.makeText(this, friendlyAnalysisError(error), Toast.LENGTH_LONG).show();
                });
            } finally {
                analyzing.set(false);
                runOnUiThread(() -> {
                    if (analysisSerial == captureRequestSerial) {
                        analysisProgress.setVisibility(View.GONE);
                        if (engineRevision != revisionAtStart && !hasStartedCaptureDownload()
                                && capturePanel.getVisibility() == View.VISIBLE) {
                            analyzedRequestSerial = -1;
                            analyzePage(pendingPageUrl);
                        }
                    }
                    else if (capturePanel.getVisibility() == View.VISIBLE && !TextUtils.isEmpty(pendingPageUrl))
                        analyzePage(pendingPageUrl);
                });
            }
        });
    }


    private FormatSelectionPolicy.Score extractionScore(JSONObject root) {
        List<FormatSelectionPolicy.Format> formats = new ArrayList<>();
        JSONArray raw = root.optJSONArray("formats");
        if (raw == null) { raw = new JSONArray(); raw.put(root); }
        for (int i=0; i<raw.length(); i++) {
            JSONObject format=raw.optJSONObject(i);
            if(format==null || format.optBoolean("has_drm",false)
                    || "mhtml".equals(format.optString("protocol")) || "images".equals(format.optString("protocol"))) continue;
            formats.add(new FormatSelectionPolicy.Format(format.optString("format_id"),format.optString("url"),format.optString("ext"),
                    format.optString("vcodec","none"),format.optString("acodec","none"),numberToInt(format.opt("height"))));
        }
        return FormatSelectionPolicy.score(formats);
    }

    private JSONObject executeYouTubeAnalysis(String pageUrl) throws Exception {
        JSONObject best=null;
        FormatSelectionPolicy.Score bestScore=new FormatSelectionPolicy.Score();
        Exception lastError=null;
        int serial=captureRequestSerial;
        // Match the direct URL path that SL.Tube uses first. Browser credentials are an
        // independent recovery attempt, never mixed into a successful anonymous response.
        for(boolean useSession:new boolean[]{false,true}) {
            if(serial!=captureRequestSerial || Thread.currentThread().isInterrupted())
                throw new InterruptedException("تم إلغاء تحليل الصفحة السابقة");
            try {
                YoutubeDLRequest request=SLTubeAnalysisRequest.create(getApplicationContext(),pageUrl);
                if(useSession) applySessionOptions(request,pageUrl);
                YoutubeDLResponse response=runAnalysisRequest(request,"satlink-sltube-analysis-"+java.util.UUID.randomUUID());
                if(response.getExitCode()!=0) throw new IllegalStateException("فشل طلب المحرك");
                JSONObject candidate=parseResponseJson(response.getOut());
                candidate.put("_satlink_browser_session",useSession);
                FormatSelectionPolicy.Score score=extractionScore(candidate);
                Log.i(TAG,"SL.Tube analysis profile="+(useSession?"browser-session":"direct-url")
                        +" videoFormats="+score.videoCount+" audioFormats="+score.audioCount+" maxHeight="+score.maxHeight);
                if(score.hasMedia() && (best==null || score.compareTo(bestScore)>0)) {best=candidate;bestScore=score;}
                if(!useSession && score.videoCount>0 && score.maxHeight>360) break;
            } catch(Exception error) {lastError=error;Log.w(TAG,"SL.Tube analysis profile failed",error);}
        }
        if(best!=null) return best;
        if(lastError!=null) throw lastError;
        throw new IllegalStateException("لم يرجع المحرك صيغ فيديو أو صوت قابلة للتنزيل");
    }

    private void stopActiveAnalysis() {
        final String process=activeAnalysisProcess;
        if (TextUtils.isEmpty(process) || analysisWatchdog.isShutdown()) return;
        analysisWatchdog.execute(() -> {
            try { YoutubeDL.getInstance().destroyProcessById(process); }
            catch(Throwable error) { Log.w(TAG,"Analysis cancellation failed",error); }
        });
    }

    private YoutubeDLResponse runAnalysisRequest(YoutubeDLRequest request, String processId) throws Exception {
        activeAnalysisProcess=processId;
        ScheduledFuture<?> timeout=analysisWatchdog.schedule(() -> {
            try { YoutubeDL.getInstance().destroyProcessById(processId); }
            catch(Throwable error) { Log.w(TAG,"Analysis timeout cancellation failed",error); }
        },45L,TimeUnit.SECONDS);
        try {
            return YoutubeDL.getInstance().execute(request,processId,
                    new Function3<Float,Long,String,Unit>() {
                        @Override public Unit invoke(Float progress,Long eta,String line) { return Unit.INSTANCE; }
                    });
        } finally {
            timeout.cancel(false);
            if(processId.equals(activeAnalysisProcess)) activeAnalysisProcess=null;
        }
    }

    private JSONObject executeAnalysis(String pageUrl) throws Exception {
        final int serial = captureRequestSerial;
        final JSONArray captured = lastCapturedItems;
        Exception lastError = null;
        // Start with the site's extractor and retain every format from its response.
        for (boolean useSession : new boolean[]{false, true}) {
            if (serial != captureRequestSerial || Thread.currentThread().isInterrupted())
                throw new InterruptedException("تم إلغاء تحليل الصفحة السابقة");
            try {
                return executeSiteRequest(pageUrl, useSession);
            } catch (Exception error) { lastError = error; }
        }
        // Embedded sites can provide a master playlist or a separate player page.
        // Do not replace all passively captured renditions with one direct CDN file.
        List<String> candidates = new ArrayList<>();
        String manifest = strongestManifestUrl(captured);
        if (!TextUtils.isEmpty(manifest) && !manifest.equals(pageUrl)) candidates.add(manifest);
        if (captured != null) {
            for (int i = captured.length()-1; i >= 0 && candidates.size() < 3; i--) {
                JSONObject item = captured.optJSONObject(i);
                if (item == null || !"page".equals(item.optString("type"))) continue;
                String url = item.optString("url");
                if (looksLikeAdUrl(url)) continue;
                if (url.startsWith("https://") && !url.equals(pageUrl) && !candidates.contains(url))
                    candidates.add(url);
            }
        }
        for (String candidate : candidates) {
            if (serial != captureRequestSerial || Thread.currentThread().isInterrupted())
                throw new InterruptedException("تم إلغاء تحليل الصفحة السابقة");
            try { return executeSiteRequest(candidate, true); }
            catch (Exception error) { lastError = error; }
        }
        if (lastError != null) throw lastError;
        throw new IllegalStateException("لم يرجع الموقع صيغ فيديو أو صوت");
    }

    private JSONObject executeSiteRequest(String url, boolean useSession) throws Exception {
        YoutubeDLRequest request = SLTubeAnalysisRequest.create(getApplicationContext(), url);
        if (useSession) applySessionOptions(request, url);
        YoutubeDLResponse response = runAnalysisRequest(request,
                "satlink-site-analysis-" + java.util.UUID.randomUUID());
        if (response.getExitCode() != 0) throw new IllegalStateException("فشل تحليل الموقع");
        JSONObject root = parseResponseJson(response.getOut());
        // Generic extractors may know the container but omit codec metadata.
        // Keep real media selectable without asserting that an MP4 has sound.
        JSONArray formats = root.optJSONArray("formats");
        if (formats == null) {
            formats = new JSONArray(); formats.put(root);
            if (TextUtils.isEmpty(root.optString("format_id"))
                    && root.optString("url").matches("https?://.*")) root.put("format_id", "best");
        }
        for (int i = 0; i < formats.length(); i++) {
            JSONObject format = formats.optJSONObject(i);
            if (format == null) continue;
            String ext = format.optString("ext").toLowerCase(Locale.US);
            if (TextUtils.isEmpty(format.optString("vcodec"))) {
                if (ext.matches("mp4|webm|mkv|mov|m4v|ts") || numberToInt(format.opt("height")) > 0)
                    format.put("vcodec", "unknown");
                else if (ext.matches("mp3|m4a|aac|ogg|opus|wav|flac")) {
                    format.put("vcodec", "none");
                    if (TextUtils.isEmpty(format.optString("acodec"))) format.put("acodec", "unknown");
                }
            }
        }
        if (!extractionScore(root).hasMedia()) throw new IllegalStateException("لا توجد صيغ قابلة للتنزيل");
        root.put("_satlink_browser_session", useSession);
        return root;
    }

    private static String compactEngineOutput(String output) {
        if (TextUtils.isEmpty(output)) return "تعذر جلب بيانات الفيديو";
        String text = output.replace('\r', ' ').replace('\n', ' ').trim();
        return text.length() > 420 ? text.substring(text.length() - 420) : text;
    }

    private void applySessionOptions(YoutubeDLRequest request, String pageUrl) {
        if (!TextUtils.isEmpty(latestUserAgent)) {
            request.addOption("--user-agent", latestUserAgent);
        }
        if (!TextUtils.isEmpty(pendingPageUrl)) {
            request.addOption("--referer", pendingPageUrl);
        } else if (!TextUtils.isEmpty(pageUrl)) {
            request.addOption("--referer", pageUrl);
        }
        if (!TextUtils.isEmpty(latestOrigin)) request.addOption("--add-header", "Origin:" + latestOrigin);
        if (!TextUtils.isEmpty(latestCookieFilePath)) request.addOption("--cookies", latestCookieFilePath);
        else if (!TextUtils.isEmpty(latestCookieHeader)) request.addOption("--add-header", "Cookie:" + latestCookieHeader);
    }

    private static boolean isPornhubUrl(String url) {
        if (TextUtils.isEmpty(url)) return false;
        String lower = url.toLowerCase(Locale.US);
        return lower.contains("pornhub.com/") || lower.contains("pornhubpremium.com/");
    }

    private static boolean isEgyBestUrl(String url) {
        if (TextUtils.isEmpty(url)) return false;
        String lower = url.toLowerCase(Locale.US);
        return lower.contains("egytbest.") || lower.contains("egybest.");
    }

    private static boolean looksLikeAdUrl(String url) {
        if (TextUtils.isEmpty(url)) return false;
        String lower = url.toLowerCase(Locale.US);
        return lower.contains("doubleclick") || lower.contains("googlesyndication")
                || lower.contains("googleadservices") || lower.contains("adservice")
                || lower.contains("popads") || lower.contains("popcash")
                || lower.contains("onclick") || lower.contains("adsterra")
                || lower.contains("exoclick") || lower.contains("trafficjunky")
                || lower.contains("banner") || lower.contains("preroll")
                || lower.contains("vast.") || lower.contains("/vast/")
                || lower.contains("adsystem") || lower.contains("adnxs");
    }

    private String strongestEgyBestServerPage(JSONArray items) {
        if (items == null) return "";
        String[] preferred = new String[]{
                "streamruby", "voe.", "mixdrop", "hgcloud", "vidmoly",
                "dood", "uqload", "streamtape", "filemoon", "vidhide"
        };
        for (String hostHint : preferred) {
            for (int i = items.length() - 1; i >= 0; i--) {
                JSONObject item = items.optJSONObject(i);
                if (item == null || !"page".equals(item.optString("type"))) continue;
                String url = item.optString("url", "");
                String lower = url.toLowerCase(Locale.US);
                if (lower.contains(hostHint) && !looksLikeAdUrl(lower)) return url;
            }
        }
        for (int i = items.length() - 1; i >= 0; i--) {
            JSONObject item = items.optJSONObject(i);
            if (item == null || !"page".equals(item.optString("type"))) continue;
            String url = item.optString("url", "");
            String lower = url.toLowerCase(Locale.US);
            if (!TextUtils.isEmpty(url) && !isEgyBestUrl(url) && !looksLikeAdUrl(lower)) return url;
        }
        return "";
    }

    private boolean rejectCapturedItem(JSONObject item) {
        if (item == null) return true;
        String type = item.optString("type", "");
        if ("page".equals(type) || "transport".equals(type)) return true;
        String url = item.optString("url", "");
        if (TextUtils.isEmpty(url)) return true;

        String mediaPath = Uri.parse(url).getPath();
        if (mediaPath != null && mediaPath.toLowerCase(Locale.US).endsWith(".m4s")) return true;
        if (mediaPath != null && mediaPath.toLowerCase(Locale.US).endsWith(".ts")
                && !TextUtils.isEmpty(strongestManifestUrl(lastCapturedItems))) return true;

        boolean strictSite = isPornhubUrl(pendingPageUrl) || isEgyBestUrl(pendingPageUrl);
        if (!strictSite) return false;

        String lower = url.toLowerCase(Locale.US);
        if (looksLikeAdUrl(lower)) return true;
        boolean playlist = lower.contains(".m3u8") || lower.contains(".mpd")
                || item.optString("mime", "").toLowerCase(Locale.US).contains("mpegurl")
                || item.optString("mime", "").toLowerCase(Locale.US).contains("dash+xml");
        if (playlist) return false;

        String clean = lower;
        int query = clean.indexOf('?');
        if (query >= 0) clean = clean.substring(0, query);
        int hash = clean.indexOf('#');
        if (hash >= 0) clean = clean.substring(0, hash);
        if (clean.endsWith(".m4s")) return true;
        if (lower.contains("index-v1-a1") || lower.contains("index-a1")
                || lower.contains("/segment") || lower.contains("segment-")
                || lower.contains("/chunk") || lower.contains("chunk-")
                || lower.contains("/fragment") || lower.contains("frag-")) return true;

        if (isEgyBestUrl(pendingPageUrl) && !TextUtils.isEmpty(preferredPlayerPageUrl)) {
            String referer = item.optString("referer", "");
            String frameUrl = item.optString("frameUrl", "");
            String playerHost = "";
            try { playerHost = Uri.parse(preferredPlayerPageUrl).getHost(); } catch (Throwable ignored) { }
            if (!TextUtils.isEmpty(playerHost)) {
                boolean fromPlayer = referer.contains(playerHost) || frameUrl.contains(playerHost)
                        || lower.contains(playerHost);
                if (!fromPlayer) return true;
            }
        }
        return false;
    }

    private static boolean isYouTubeUrl(String url) {
        if (TextUtils.isEmpty(url)) return false;
        String lower = url.toLowerCase(Locale.US);
        return lower.contains("youtube.com/") || lower.contains("youtu.be/");
    }

    private static boolean looksLikeYouTubeThrottle(Throwable error) {
        String message = error == null ? "" : String.valueOf(error.getMessage()).toLowerCase(Locale.US);
        return message.contains("429") || message.contains("too many requests")
                || message.contains("unable to fetch gvs") || message.contains("sign in to confirm")
                || message.contains("content isn't available");
    }

    private static String friendlyAnalysisError(Throwable error) {
        if (looksLikeYouTubeThrottle(error)) {
            return "يوتيوب حدّد الطلبات مؤقتاً. افتح الفيديو وشغّله ثواني ثم أعد الالتقاط، أو انتظر قليلاً.";
        }
        return "تعذر تحليل هذا الموقع حالياً. شغّل الفيديو ثم أعد الالتقاط.";
    }

    private JSONObject parseResponseJson(String output) throws Exception {
        if (output == null) {
            throw new IllegalStateException("لم يرجع محرك التحليل بيانات.");
        }

        String text = output.trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalStateException("لم يتم العثور على معلومات الجودات.");
        }

        JSONObject root = new JSONObject(text.substring(start, end + 1));
        if (root.optJSONArray("formats") == null) {
            JSONArray entries = root.optJSONArray("entries");
            if (entries != null && entries.length() > 0) {
                JSONObject first = entries.optJSONObject(0);
                if (first != null) {
                    root = first;
                }
            }
        }
        return root;
    }

    private FormatCollection parseFormats(JSONObject root) {
        FormatCollection result = new FormatCollection();
        if (root.optBoolean("has_drm", false)) throw new IllegalArgumentException("المحتوى محمي");
        result.title = root.optString("title", pendingPageTitle);
        result.durationSeconds = numberToDouble(root.opt("duration"));
        result.sourceUrl = root.optString("webpage_url", pendingPageUrl);
        if (TextUtils.isEmpty(result.sourceUrl)) {
            result.sourceUrl = pendingPageUrl;
        }

        JSONArray formats = root.optJSONArray("formats");
        if (formats == null) {
            if (TextUtils.isEmpty(root.optString("format_id", ""))) return result;
            formats = new JSONArray();
            formats.put(root);
        }

        Set<String> videoKeys = new HashSet<>();
        Set<String> audioKeys = new HashSet<>();
        List<MediaFormatItem> sourceAudios = new ArrayList<>();
        boolean tikTokSource = root.optString("extractor_key", "").startsWith("TikTok")
                || result.sourceUrl.matches("(?i)https?://([a-z0-9-]+\\.)*tiktok\\.com/.*");

        for (int i = 0; i < formats.length(); i++) {
            JSONObject format = formats.optJSONObject(i);
            if (format == null || format.optBoolean("has_drm", false)
                    || "mhtml".equals(format.optString("protocol"))
                    || "images".equals(format.optString("protocol"))) {
                continue;
            }

            String formatId = format.optString("format_id", "");
            if (!formatId.matches("[A-Za-z0-9_.:-]+")
                    || "images".equalsIgnoreCase(format.optString("vcodec"))) continue;
            String url = format.optString("url", "");
            String ext = format.optString("ext", "");
            String vcodec = format.optString("vcodec", "none");
            String acodec = format.optString("acodec", "none");
            int height = numberToInt(format.opt("height"));
            int width = numberToInt(format.opt("width"));
            double fps = numberToDouble(format.opt("fps"));
            double tbr = numberToDouble(format.opt("tbr"));
            double abr = numberToDouble(format.opt("abr"));
            long size = firstPositiveLong(format.opt("filesize"), format.opt("filesize_approx"));

            if (!TextUtils.isEmpty(vcodec) && !"none".equalsIgnoreCase(vcodec)) {
                MediaFormatItem item = new MediaFormatItem();
                item.type = MediaFormatItem.TYPE_VIDEO;
                item.sourceUrl = result.sourceUrl;
                item.useBrowserSession = root.optBoolean("_satlink_browser_session", true);
                item.directUrl = url;
                item.formatId = formatId;
                item.ext = ext;
                item.width = width;
                item.height = height;
                item.fps = fps;
                item.bitrate = tbr;
                item.sizeBytes = size > 0 ? size : estimateSize(result.durationSeconds, tbr);
                item.sizeEstimated = numberToLong(format.opt("filesize")) <= 0 && item.sizeBytes > 0;
                item.videoCodec = shortCodec(vcodec);
                item.audioCodec = shortCodec(acodec);
                item.hasAudio = !"none".equalsIgnoreCase(acodec) && !TextUtils.isEmpty(acodec);
                item.note = format.optString("format_note", "");
                item.dynamicRange = format.optString("dynamic_range", "");
                item.protocol = format.optString("protocol", "");

                String key = item.formatId;
                if (videoKeys.add(key)) {
                    result.videos.add(item);
                }
            }

            if ("none".equalsIgnoreCase(vcodec) && !"none".equalsIgnoreCase(acodec)) {
                MediaFormatItem item = new MediaFormatItem();
                item.type = MediaFormatItem.TYPE_AUDIO;
                item.sourceUrl = result.sourceUrl;
                item.useBrowserSession = root.optBoolean("_satlink_browser_session", true);
                item.directUrl = url;
                item.formatId = formatId;
                item.ext = ext;
                item.bitrate = abr > 0 ? abr : tbr;
                item.sizeBytes = size > 0 ? size : estimateSize(result.durationSeconds, abr > 0 ? abr : tbr);
                item.sizeEstimated = numberToLong(format.opt("filesize")) <= 0 && item.sizeBytes > 0;
                item.audioCodec = shortCodec(acodec);
                item.sampleRate = numberToInt(format.opt("asr"));
                item.channels = numberToInt(format.opt("audio_channels"));
                item.protocol = format.optString("protocol", "");
                item.note = format.optString("format_note", "");

                String key = item.formatId + "|" + Math.round(item.bitrate) + "|" + item.ext;
                if (audioKeys.add(key)) {
                    sourceAudios.add(item);
                }
            }
        }

        Collections.sort(result.videos, (left, right) -> {
            int heightCompare = Integer.compare(right.height, left.height);
            if (heightCompare != 0) return heightCompare;
            int fpsCompare = Double.compare(right.fps, left.fps);
            if (fpsCompare != 0) return fpsCompare;
            return Double.compare(right.bitrate, left.bitrate);
        });

        Collections.sort(sourceAudios, (left, right) ->
                Double.compare(right.bitrate, left.bitrate));

        // SL.Tube pairs an exact audio format and preserves container compatibility.
        for (MediaFormatItem video : result.videos) {
            if (video.hasAudio || sourceAudios.isEmpty() || tikTokSource) continue;
            MediaFormatItem audio = sourceAudios.get(0);
            for (MediaFormatItem candidate : sourceAudios) {
                if (("mp4".equals(video.ext) && "m4a".equals(candidate.ext))
                        || ("webm".equals(video.ext) && "webm".equals(candidate.ext))) {
                    audio = candidate; break;
                }
            }
            video.directAudioUrl = audio.directUrl;
            video.audioCodec = audio.audioCodec;
            if (video.formatId.matches("[A-Za-z0-9_.:-]+") && audio.formatId.matches("[A-Za-z0-9_.:-]+"))
                video.formatId += "+" + audio.formatId;
            boolean compatible = ("mp4".equals(video.ext) && "m4a".equals(audio.ext))
                    || ("webm".equals(video.ext) && "webm".equals(audio.ext)) || "mkv".equals(video.ext);
            if (!compatible) video.ext = "mkv";
            if (video.sizeBytes > 0 && audio.sizeBytes > 0) {
                video.sizeBytes += audio.sizeBytes;
                video.sizeEstimated = true; // Final muxed file may include container overhead.
            } else {video.sizeBytes = 0;video.sizeEstimated = false;}
        }

        if (sourceAudios.isEmpty() || tikTokSource) {
            for (MediaFormatItem video : result.videos) {
                if (video.hasAudio) {
                    if (tikTokSource) sourceAudios.clear();
                    sourceAudios.add(video); break;
                }
            }
        }

        // Display useful MP3 output choices instead of exposing only the low source bitrate
        // reported by some YouTube clients (for example 48 kbps). Every option downloads the
        // best available audio source and converts it to the selected MP3 bitrate.
        if (!sourceAudios.isEmpty()) {
            MediaFormatItem bestSource = sourceAudios.get(0);
            int[] mp3Qualities = new int[]{320, 256, 192, 128};
            for (int quality : mp3Qualities) {
                MediaFormatItem item = new MediaFormatItem();
                item.type = MediaFormatItem.TYPE_AUDIO;
                item.sourceUrl = result.sourceUrl;
                item.useBrowserSession = root.optBoolean("_satlink_browser_session", true);
                item.directUrl = bestSource.directUrl;
                item.useBrowserSession = bestSource.useBrowserSession;
                item.formatId = tikTokSource ? bestSource.formatId : "bestaudio/best";
                item.ext = "mp3";
                item.bitrate = quality;
                item.audioQualityKbps = quality;
                item.audioCodec = bestSource.audioCodec;
                item.sampleRate = bestSource.sampleRate;
                item.channels = bestSource.channels;
                item.note = "تحويل أفضل صوت متاح إلى MP3";
                if (result.durationSeconds > 0) {
                    item.sizeBytes = Math.round(result.durationSeconds * quality * 1000d / 8d);
                    item.sizeEstimated = true;
                }
                result.audios.add(item);
            }
        }

        return result;
    }

    private boolean hasStartedCaptureDownload() {
        for (DownloadUiBinding binding : downloadBindings.values()) {
            if (!TextUtils.isEmpty(binding.state)) return true;
        }
        return false;
    }

    private void showAnalyzedFormats(FormatCollection collection) {
        if (hasStartedCaptureDownload()) return;
        resetFormatLists();

        if (!TextUtils.isEmpty(collection.title)) {
            captureTitle.setText(collection.title);
        }

        for (MediaFormatItem item : collection.videos) {
            videoResults.addView(createFormatCard(item));
        }
        for (MediaFormatItem item : collection.audios) {
            audioResults.addView(createFormatCard(item));
        }
        updateCounts(collection.videos.size(), collection.audios.size());

        if (collection.videos.isEmpty() && collection.audios.isEmpty()) {
            captureStatus.setText("لم تظهر جودات مفصلة؛ عرض الروابط المباشرة التي التقطها المتصفح.");
            showCapturedFallback(lastCapturedItems);
            return;
        }

        captureStatus.setText("اختر الجودة المطلوبة. الحفظ داخل مجلد Downloads/SatLink.");
        if (!collection.videos.isEmpty()) {
            openVideoList();
        } else {
            openAudioList();
        }
    }

    /**
     * YouTube changes videos without reloading the page, so data read from the page can still
     * belong to the previous video. Show it only after the real analysis of the current video
     * failed, and then only items carrying the current video id.
     */
    private JSONArray currentVideoItems(JSONArray items) {
        if (items == null || !isYouTubeUrl(pendingPageUrl)) return items;
        if (TextUtils.isEmpty(lastAnalysisError) && !engineFailed.get()) return null;
        String id = Uri.parse(pendingPageUrl).getQueryParameter("v");
        if (TextUtils.isEmpty(id)) return new JSONArray();
        JSONArray kept = new JSONArray();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item != null && id.equals(item.optString("videoId"))) kept.put(item);
        }
        return kept;
    }

    private void showCapturedFallback(JSONArray items) {
        if (isYouTubeUrl(pendingPageUrl)) {
            items = currentVideoItems(items);
            if (items == null) return; // analysis of the current video is still running
        }
        if (hasStartedCaptureDownload()) return;
        resetFormatLists();
        if (items == null || items.length() == 0) {
            updateCounts(0, 0);
            captureStatus.setText(TextUtils.isEmpty(lastAnalysisError)
                    ? "جاري البحث عن بيانات الفيديو والصوت..." : lastAnalysisError);
            return;
        }

        String bestAudioUrl = "";
        boolean bestAudioCapturedDirect = false;
        String bestAudioExt = "";
        String bestAudioProtocol = "";
        String bestAudioCodec = "";
        double bestAudioBitrate = 0;
        long bestAudioSize = 0;
        String bestM4aUrl = "";
        double bestM4aBitrate = 0;
        long bestM4aSize = 0;
        String bestWebmAudioUrl = "";
        double bestWebmAudioBitrate = 0;
        long bestWebmAudioSize = 0;

        // Choose the strongest audio request first so adaptive YouTube video can be merged with it.
        for (int i = 0; i < items.length(); i++) {
            JSONObject json = items.optJSONObject(i);
            if (json == null || rejectCapturedItem(json)
                    || !isCapturedAudio(json)) continue;
            String url = json.optString("url", "");
            if (TextUtils.isEmpty(url)) continue;
            double bitrate = normalizedBitrateKbps(numberToDouble(json.opt("bitrate")));
            long size = numberToLong(json.opt("size"));
            double score = bitrate * 1_000_000d + Math.min(size, 999_999_999L);
            double currentScore = bestAudioBitrate * 1_000_000d + Math.min(bestAudioSize, 999_999_999L);
            String mime = json.optString("mime", "").toLowerCase(Locale.US);
            String detectedAudioExt = mime.contains("webm") || mime.contains("opus")
                    ? "webm" : (mime.contains("mp4") || mime.contains("m4a")
                    || mime.contains("mp4a") ? "m4a" : extensionFromUrl(url));
            String detectedAudioCodec = mime.contains("opus") ? "Opus"
                    : (mime.contains("mp4a") || mime.contains("aac") ? "AAC" : "");
            if (TextUtils.isEmpty(bestAudioUrl) || score > currentScore) {
                bestAudioUrl = url;
                bestAudioCapturedDirect = json.optBoolean("resolvedFromNetwork") || !isYouTubeUrl(pendingPageUrl);
                bestAudioBitrate = bitrate;
                bestAudioSize = size;
                bestAudioExt = detectedAudioExt;
                bestAudioProtocol = json.optString("protocol", "");
                bestAudioCodec = detectedAudioCodec;
            }
            if ("m4a".equals(detectedAudioExt)) {
                double m4aScore = bestM4aBitrate * 1_000_000d + Math.min(bestM4aSize, 999_999_999L);
                if (TextUtils.isEmpty(bestM4aUrl) || score > m4aScore) {
                    bestM4aUrl = url;
                    bestM4aBitrate = bitrate;
                    bestM4aSize = size;
                }
            } else if ("webm".equals(detectedAudioExt)) {
                double webmScore = bestWebmAudioBitrate * 1_000_000d + Math.min(bestWebmAudioSize, 999_999_999L);
                if (TextUtils.isEmpty(bestWebmAudioUrl) || score > webmScore) {
                    bestWebmAudioUrl = url;
                    bestWebmAudioBitrate = bitrate;
                    bestWebmAudioSize = size;
                }
            }
            capturedDurationSeconds = Math.max(capturedDurationSeconds,
                    numberToDouble(json.opt("duration")));
        }

        if (capturedDurationSeconds <= 0 && bestAudioSize > 0 && bestAudioBitrate > 0) {
            capturedDurationSeconds = bestAudioSize * 8d / (bestAudioBitrate * 1000d);
        }

        int videoTotal = 0;
        Set<String> seen = new HashSet<>();
        List<MediaFormatItem> capturedVideos = new ArrayList<>();

        for (int i = 0; i < items.length(); i++) {
            JSONObject json = items.optJSONObject(i);
            if (json == null || rejectCapturedItem(json)
                    || isCapturedAudio(json)) continue;

            String url = json.optString("url", "");
            if (TextUtils.isEmpty(url)) continue;

            String detectedExt = extensionFromUrl(url);
            String lowerUrl = url.toLowerCase(Locale.US);
            String mime = json.optString("mime", "").toLowerCase(Locale.US);
            boolean modernVideo = "mp4".equals(detectedExt) || "webm".equals(detectedExt)
                    || "mkv".equals(detectedExt) || "ts".equals(detectedExt)
                    || "mov".equals(detectedExt) || "m4v".equals(detectedExt)
                    || mime.startsWith("video/") || mime.contains("matroska")
                    || mime.contains("video/mp4") || mime.contains("video/webm")
                    || lowerUrl.contains("videoplayback") || lowerUrl.contains(".m3u8")
                    || lowerUrl.contains(".mpd") || lowerUrl.contains(".m4s") || lowerUrl.contains(".ts")
                    || mime.contains("mpegurl") || mime.contains("dash+xml") || mime.contains("mp2t");
            if (!modernVideo || (!"video".equals(json.optString("type")) && !mime.startsWith("video/")
                    && TextUtils.isEmpty(detectedExt) && TextUtils.isEmpty(json.optString("itag")))) continue;

            String itag = json.optString("itag", "");

            MediaFormatItem item = new MediaFormatItem();
            item.directUrl = TextUtils.isEmpty(json.optString("masterUrl")) ? url : json.optString("masterUrl");
            item.capturedDirect = json.optBoolean("resolvedFromNetwork") || !isYouTubeUrl(pendingPageUrl);
            String capturedReferer = json.optString("referer", "");
            if (TextUtils.isEmpty(capturedReferer)) capturedReferer = json.optString("frameUrl", "");
            if (TextUtils.isEmpty(capturedReferer)) capturedReferer = preferredPlayerPageUrl;
            item.sourceUrl = TextUtils.isEmpty(capturedReferer) ? pendingPageUrl : capturedReferer;
            item.formatId = TextUtils.isEmpty(json.optString("formatSelector"))
                    ? (TextUtils.isEmpty(itag) ? "" : itag) : json.optString("formatSelector");
            // YouTube itag lets yt-dlp reopen the original page and download through its managed
            // FFmpeg environment instead of executing temporary videoplayback links directly.
            item.ext = "mkv".equals(detectedExt) || mime.contains("matroska") ? "mkv"
                    : "ts".equals(detectedExt) || mime.contains("mp2t") ? "ts"
                    : "webm".equals(detectedExt) || mime.contains("webm") ? "webm" : "mp4";
            item.note = "رابط فيديو ملتقط من جلسة المتصفح";
            item.protocol = json.optString("protocol", Uri.parse(url).getScheme());
            item.type = MediaFormatItem.TYPE_VIDEO;
            item.width = numberToInt(json.opt("width"));
            item.height = numberToInt(json.opt("height"));
            item.fps = numberToDouble(json.opt("fps"));
            item.bitrate = normalizedBitrateKbps(numberToDouble(json.opt("bitrate")));
            item.sizeBytes = numberToLong(json.opt("size"));
            String qualityLabel = json.optString("qualityLabel", "");
            applyCapturedFormatMetadata(item, itag, mime, qualityLabel);
            if (!TextUtils.isEmpty(json.optString("videoCodec"))) item.videoCodec = shortCodec(json.optString("videoCodec"));
            if (!TextUtils.isEmpty(json.optString("audioCodec"))) item.audioCodec = shortCodec(json.optString("audioCodec"));
            if (json.has("hasAudio")) item.hasAudio = json.optBoolean("hasAudio");
            // YouTube transport endpoints without format facts are not selectable MP4 qualities.
            if (isYouTubeUrl(pendingPageUrl) && item.height <= 0 && item.width <= 0
                    && !json.optBoolean("resolvedFromNetwork") && TextUtils.isEmpty(itag)) continue;
            String formatKey = !TextUtils.isEmpty(itag) ? "itag:" + itag
                    : item.ext + "|" + item.videoCodec + "|" + item.height
                    + "|" + Math.round(item.fps) + "|" + Math.round(item.bitrate) + "|" + qualityLabel
                    + ((item.height > 0 || item.width > 0) ? "" : "|" + canonicalCaptureUrl(url));
            if (!seen.add(formatKey)) continue;

            double duration = numberToDouble(json.opt("duration"));
            if (duration <= 0) duration = capturedDurationSeconds;
            capturedDurationSeconds = Math.max(capturedDurationSeconds, duration);
            if (item.sizeBytes <= 0) {
                item.sizeBytes = estimateSize(duration, item.bitrate);
                item.sizeEstimated = item.sizeBytes > 0;
            }

            if (!item.hasAudio && !TextUtils.isEmpty(bestAudioUrl) && TextUtils.isEmpty(json.optString("masterUrl"))) {
                String pairedAudioUrl = bestAudioUrl;
                String pairedAudioCodec = bestAudioCodec;
                double pairedAudioBitrate = bestAudioBitrate;
                long pairedAudioSize = bestAudioSize;
                if ("mp4".equalsIgnoreCase(item.ext) && !TextUtils.isEmpty(bestM4aUrl)) {
                    pairedAudioUrl = bestM4aUrl;
                    pairedAudioCodec = "AAC";
                    pairedAudioBitrate = bestM4aBitrate;
                    pairedAudioSize = bestM4aSize;
                } else if ("webm".equalsIgnoreCase(item.ext) && !TextUtils.isEmpty(bestWebmAudioUrl)) {
                    pairedAudioUrl = bestWebmAudioUrl;
                    pairedAudioCodec = "Opus";
                    pairedAudioBitrate = bestWebmAudioBitrate;
                    pairedAudioSize = bestWebmAudioSize;
                }
                item.directAudioUrl = pairedAudioUrl;
                item.audioCodec = pairedAudioCodec;
                if (pairedAudioSize > 0 && item.sizeBytes > 0) item.sizeBytes += pairedAudioSize;
                else if (item.sizeBytes > 0 && duration > 0 && pairedAudioBitrate > 0) {
                    item.sizeBytes += estimateSize(duration, pairedAudioBitrate);
                }
                item.note = "سيتم دمج أفضل صوت متوافق تلقائياً";
            }

            capturedVideos.add(item);
        }

        Collections.sort(capturedVideos, (left, right) -> {
            int heightCompare = Integer.compare(right.height, left.height);
            if (heightCompare != 0) return heightCompare;
            int fpsCompare = Double.compare(right.fps, left.fps);
            if (fpsCompare != 0) return fpsCompare;
            return Double.compare(right.bitrate, left.bitrate);
        });
        for (MediaFormatItem item : capturedVideos) {
            videoResults.addView(createFormatCard(item));
            videoTotal++;
        }

        int audioTotal = 0;
        MediaFormatItem muxedAudioSource = null;
        // A progressive file carries audio too; it does not require a separate audio network request.
        if (TextUtils.isEmpty(bestAudioUrl)) {
            for (MediaFormatItem video : capturedVideos) {
                if (!video.hasAudio) continue;
                bestAudioUrl = video.directUrl;
                bestAudioCapturedDirect = video.capturedDirect;
                muxedAudioSource = video;
                bestAudioCodec = video.audioCodec;
                break;
            }
        }
        if (!TextUtils.isEmpty(bestAudioUrl)) {
            int[] mp3Qualities = new int[]{320, 256, 192, 128};
            for (int quality : mp3Qualities) {
                MediaFormatItem item = new MediaFormatItem();
                item.type = MediaFormatItem.TYPE_AUDIO;
                item.sourceUrl = TextUtils.isEmpty(preferredPlayerPageUrl)
                        ? pendingPageUrl : preferredPlayerPageUrl;
                item.directUrl = bestAudioUrl;
                item.capturedDirect = bestAudioCapturedDirect;
                item.hasAudio = true;
                item.protocol = muxedAudioSource == null ? bestAudioProtocol : muxedAudioSource.protocol;
                if (muxedAudioSource != null) item.videoCodec = TextUtils.isEmpty(muxedAudioSource.videoCodec)
                        ? "unknown" : muxedAudioSource.videoCodec;
                item.formatId = "";
                item.ext = "mp3";
                item.bitrate = quality;
                item.audioQualityKbps = quality;
                item.audioCodec = bestAudioCodec;
                item.note = "تحويل أفضل صوت ملتقط إلى MP3";
                if (capturedDurationSeconds > 0) {
                    item.sizeBytes = estimateSize(capturedDurationSeconds, quality);
                    item.sizeEstimated = true;
                }
                audioResults.addView(createFormatCard(item));
                audioTotal++;
            }
        }

        updateCounts(videoTotal, audioTotal);
        if (videoTotal > 0) {
            captureStatus.setText("تم التقاط الفيديو من جلسة المتصفح. اختر الجودة المطلوبة.");
            openVideoList();
        } else if (audioTotal > 0) {
            captureStatus.setText("ظهر الصوت فقط. شغّل الفيديو عدة ثوانٍ ثم أعد الالتقاط لإظهار الفيديو.");
            openAudioList();
        } else {
            captureStatus.setText(TextUtils.isEmpty(lastAnalysisError)
                    ? "جاري استخراج الجودات؛ لم يصل رابط فيديو أو صوت بعد." : lastAnalysisError);
        }
    }

    private static boolean isCapturedAudio(JSONObject item) {
        String mime = item.optString("mime", "").toLowerCase(Locale.US);
        if ("audio".equals(item.optString("type")) || mime.startsWith("audio/")) return true;
        return item.optString("itag", "").matches("139|140|141|249|250|251|256|258|599|600");
    }

    private static String canonicalCaptureUrl(String url) {
        return url.replaceAll("([?&])(range|rn|rbuf)=[^&#]*", "$1")
                .replace("?&", "?").replace("&&", "&");
    }

    private static double normalizedBitrateKbps(double value) {
        return value > 10_000d ? value / 1000d : value;
    }

    private static void applyCapturedFormatMetadata(MediaFormatItem item, String itag,
                                                    String mime, String qualityLabel) {
        if (item == null) return;
        String lowerMime = mime == null ? "" : mime.toLowerCase(Locale.US);
        if (lowerMime.contains("av01")) item.videoCodec = "AV1";
        else if (lowerMime.contains("vp9") || lowerMime.contains("vp09")) item.videoCodec = "VP9";
        else if (lowerMime.contains("hvc1") || lowerMime.contains("hev1") || lowerMime.contains("hevc")) item.videoCodec = "H.265";
        else if (lowerMime.contains("avc1") || lowerMime.contains("h264")) item.videoCodec = "H.264";
        else item.videoCodec = "";

        if (item.height <= 0 && !TextUtils.isEmpty(qualityLabel) && qualityLabel.matches(".*\\d+p.*")) {
            String digits = qualityLabel.replaceFirst(".*?(\\d+)p.*", "$1");
            try { item.height = Integer.parseInt(digits); } catch (Exception ignored) { }
        }

        int value;
        try { value = Integer.parseInt(itag); } catch (Exception ignored) { value = 0; }
        switch (value) {
            case 18: item.height = positiveOr(item.height, 360); item.videoCodec = "H.264"; item.hasAudio = true; break;
            case 22: item.height = positiveOr(item.height, 720); item.videoCodec = "H.264"; item.hasAudio = true; break;
            case 160: item.height = positiveOr(item.height, 144); item.videoCodec = "H.264"; break;
            case 133: item.height = positiveOr(item.height, 240); item.videoCodec = "H.264"; break;
            case 134: item.height = positiveOr(item.height, 360); item.videoCodec = "H.264"; break;
            case 135: item.height = positiveOr(item.height, 480); item.videoCodec = "H.264"; break;
            case 136: item.height = positiveOr(item.height, 720); item.videoCodec = "H.264"; break;
            case 137: item.height = positiveOr(item.height, 1080); item.videoCodec = "H.264"; break;
            case 264: item.height = positiveOr(item.height, 1440); item.videoCodec = "H.264"; break;
            case 266: item.height = positiveOr(item.height, 2160); item.videoCodec = "H.264"; break;
            case 298: item.height = positiveOr(item.height, 720); item.fps = item.fps > 0 ? item.fps : 60; item.videoCodec = "H.264"; break;
            case 299: item.height = positiveOr(item.height, 1080); item.fps = item.fps > 0 ? item.fps : 60; item.videoCodec = "H.264"; break;
            case 247: item.height = positiveOr(item.height, 720); item.videoCodec = "VP9"; item.ext = "webm"; break;
            case 248: item.height = positiveOr(item.height, 1080); item.videoCodec = "VP9"; item.ext = "webm"; break;
            case 271: item.height = positiveOr(item.height, 1440); item.videoCodec = "VP9"; item.ext = "webm"; break;
            case 313: item.height = positiveOr(item.height, 2160); item.videoCodec = "VP9"; item.ext = "webm"; break;
            case 302: item.height = positiveOr(item.height, 720); item.fps = item.fps > 0 ? item.fps : 60; item.videoCodec = "VP9"; item.ext = "webm"; break;
            case 303: item.height = positiveOr(item.height, 1080); item.fps = item.fps > 0 ? item.fps : 60; item.videoCodec = "VP9"; item.ext = "webm"; break;
            case 308: item.height = positiveOr(item.height, 1440); item.fps = item.fps > 0 ? item.fps : 60; item.videoCodec = "VP9"; item.ext = "webm"; break;
            case 315: item.height = positiveOr(item.height, 2160); item.fps = item.fps > 0 ? item.fps : 60; item.videoCodec = "VP9"; item.ext = "webm"; break;
            case 398: item.height = positiveOr(item.height, 720); item.videoCodec = "AV1"; item.ext = "mp4"; break;
            case 399: item.height = positiveOr(item.height, 1080); item.videoCodec = "AV1"; item.ext = "mp4"; break;
            case 400: item.height = positiveOr(item.height, 1440); item.videoCodec = "AV1"; item.ext = "mp4"; break;
            case 401: item.height = positiveOr(item.height, 2160); item.videoCodec = "AV1"; item.ext = "mp4"; break;
            default: break;
        }
    }

    private static int positiveOr(int value, int fallback) {
        return value > 0 ? value : fallback;
    }

    private View createFormatCard(MediaFormatItem item) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(13), dp(11), dp(13), dp(11));
        card.setBackground(roundedBackground(
                item.type == MediaFormatItem.TYPE_AUDIO ? 0xFF17121B : 0xFF151219,
                item.type == MediaFormatItem.TYPE_AUDIO ? 0xFF7A3E8E : 0xFF87345F,
                15
        ));

        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        cardParams.setMargins(0, 0, 0, dp(9));
        card.setLayoutParams(cardParams);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout textColumn = new LinearLayout(this);
        textColumn.setOrientation(LinearLayout.VERTICAL);
        textColumn.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView primary = new TextView(this);
        primary.setText(item.type == MediaFormatItem.TYPE_VIDEO
                ? videoPrimaryText(item) : audioPrimaryText(item));
        primary.setTextColor(0xFFFFFFFF);
        primary.setTextSize(15);
        primary.setTypeface(Typeface.DEFAULT_BOLD);

        TextView details = new TextView(this);
        details.setText(item.type == MediaFormatItem.TYPE_VIDEO
                ? videoDetailsText(item) : audioDetailsText(item));
        details.setTextColor(0xFFAAA3B2);
        details.setTextSize(12);
        details.setPadding(0, dp(3), 0, 0);

        textColumn.addView(primary);
        textColumn.addView(details);

        Button downloadButton = new Button(this);
        downloadButton.setText("تحميل");
        downloadButton.setTextColor(Color.WHITE);
        downloadButton.setTextSize(12);
        downloadButton.setTypeface(Typeface.DEFAULT_BOLD);
        downloadButton.setAllCaps(false);
        downloadButton.setBackgroundResource(R.drawable.download_button_background);
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(dp(72), dp(38));
        buttonParams.setMarginStart(dp(10));
        downloadButton.setLayoutParams(buttonParams);

        top.addView(textColumn);
        top.addView(downloadButton);

        ProgressBar itemProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        itemProgress.setMax(100);
        itemProgress.setProgress(0);
        itemProgress.setProgressDrawable(getDrawable(R.drawable.progress_horizontal_neon));
        itemProgress.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(5));
        progressParams.setMargins(0, dp(9), 0, 0);
        itemProgress.setLayoutParams(progressParams);

        TextView itemStatus = new TextView(this);
        itemStatus.setTextColor(0xFFC7BECB);
        itemStatus.setTextSize(11);
        itemStatus.setPadding(0, dp(5), 0, 0);
        itemStatus.setVisibility(View.GONE);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        controls.setPadding(0, dp(7), 0, 0);
        controls.setVisibility(View.GONE);

        Button pauseResumeButton = smallControlButton("إيقاف مؤقت", 0xFF3C2143, 0xFFB755C8);
        Button cancelButton = smallControlButton("إلغاء", 0xFF3B1726, 0xFFD94A79);
        LinearLayout.LayoutParams pauseParams = new LinearLayout.LayoutParams(dp(105), dp(34));
        pauseParams.setMarginEnd(dp(7));
        pauseResumeButton.setLayoutParams(pauseParams);
        cancelButton.setLayoutParams(new LinearLayout.LayoutParams(dp(68), dp(34)));
        controls.addView(pauseResumeButton);
        controls.addView(cancelButton);

        card.addView(top);
        card.addView(itemProgress);
        card.addView(itemStatus);
        card.addView(controls);

        String taskId = taskIdFor(item);
        DownloadUiBinding binding = new DownloadUiBinding(
                taskId, item, downloadButton, itemProgress, itemStatus,
                controls, pauseResumeButton, cancelButton);
        downloadBindings.put(taskId, binding);

        downloadButton.setOnClickListener(v -> startDownload(binding));
        pauseResumeButton.setOnClickListener(v -> {
            if (MediaDownloadService.STATE_PAUSED.equals(binding.state)) {
                sendTaskAction(binding, MediaDownloadService.ACTION_RESUME);
            } else if (MediaDownloadService.STATE_FAILED.equals(binding.state)) {
                sendTaskAction(binding, MediaDownloadService.ACTION_RETRY);
            } else {
                sendTaskAction(binding, MediaDownloadService.ACTION_PAUSE);
            }
        });
        cancelButton.setOnClickListener(v -> sendTaskAction(binding, MediaDownloadService.ACTION_CANCEL));
        return card;
    }

    private Button smallControlButton(String text, int fill, int stroke) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(11);
        button.setAllCaps(false);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setPadding(dp(6), 0, dp(6), 0);
        button.setBackground(roundedBackground(fill, stroke, 12));
        return button;
    }

    private String videoPrimaryText(MediaFormatItem item) {
        String quality;
        if (item.height > 0) {
            quality = item.height + "p";
        } else if (item.width > 0) {
            quality = item.width + "px";
        } else {
            quality = "فيديو";
        }

        List<String> parts = new ArrayList<>();
        parts.add(quality);
        if (item.fps > 0) parts.add(formatNumber(item.fps) + "fps");
        if (!TextUtils.isEmpty(item.ext)) parts.add(item.ext.toUpperCase(Locale.US));
        if (item.sizeBytes > 0) parts.add((item.sizeEstimated ? "≈ " : "") + formatBytes(item.sizeBytes));
        else parts.add("الحجم لم يرسله المصدر");
        return TextUtils.join("  •  ", parts);
    }

    private String videoDetailsText(MediaFormatItem item) {
        List<String> parts = new ArrayList<>();
        if (!TextUtils.isEmpty(item.videoCodec)) parts.add(item.videoCodec);
        if (!TextUtils.isEmpty(item.audioCodec) && item.hasAudio) parts.add(item.audioCodec);
        if (item.bitrate > 0) parts.add(formatNumber(item.bitrate / 1000d) + " Mbps");
        if (!TextUtils.isEmpty(item.dynamicRange) && !"SDR".equalsIgnoreCase(item.dynamicRange)) {
            parts.add(item.dynamicRange);
        }
        if (!item.hasAudio && !TextUtils.isEmpty(item.directAudioUrl)) parts.add("دمج الصوت الملتقط تلقائياً");
        else if (!item.hasAudio) parts.add("فيديو بدون مسار صوت مؤكد");
        if (parts.isEmpty()) parts.add("جودة فيديو ملتقطة");
        return TextUtils.join("  •  ", parts);
    }

    private String audioPrimaryText(MediaFormatItem item) {
        List<String> parts = new ArrayList<>();
        if (TextUtils.isEmpty(item.formatId)) {
            parts.add(TextUtils.isEmpty(item.ext) ? "صوت مباشر" : item.ext.toUpperCase(Locale.US));
        } else {
            parts.add("MP3");
        }
        if (item.bitrate > 0) parts.add(formatNumber(item.bitrate) + " kbps");
        if (item.sizeBytes > 0) parts.add((item.sizeEstimated ? "≈ " : "") + formatBytes(item.sizeBytes));
        else parts.add("الحجم لم يرسله المصدر");
        return TextUtils.join("  •  ", parts);
    }

    private String audioDetailsText(MediaFormatItem item) {
        List<String> parts = new ArrayList<>();
        if (item.audioQualityKbps > 0) parts.add("من أفضل مصدر صوت متاح");
        else if (!TextUtils.isEmpty(item.ext)) parts.add("المصدر " + item.ext.toUpperCase(Locale.US));
        if (!TextUtils.isEmpty(item.audioCodec)) parts.add(item.audioCodec);
        if (item.sampleRate > 0) parts.add(formatNumber(item.sampleRate / 1000d) + " kHz");
        if (item.channels > 0) parts.add(item.channels == 1 ? "Mono" : item.channels == 2 ? "Stereo" : item.channels + " قنوات");
        parts.add(TextUtils.isEmpty(item.formatId) ? "تحميل مباشر" : "تحويل إلى MP3");
        return TextUtils.join("  •  ", parts);
    }

    private void startDownload(DownloadUiBinding binding) {
        MediaFormatItem item = binding.item;
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, STORAGE_REQUEST);
            Toast.makeText(this, "اسمح بحفظ الملفات ثم اضغط تحميل مرة ثانية.", Toast.LENGTH_LONG).show();
            return;
        }

        if (!engineReady.get()) {
            Toast.makeText(this, "محرك التحميل لم يجهز بعد.", Toast.LENGTH_SHORT).show();
            return;
        }

        binding.state = MediaDownloadService.STATE_QUEUED;
        binding.downloadButton.setEnabled(false);
        binding.progress.setVisibility(View.VISIBLE);
        binding.progress.setIndeterminate(true);
        binding.status.setVisibility(View.VISIBLE);
        binding.status.setText("جاري إرسال المهمة إلى مدير التنزيل...");
        binding.controls.setVisibility(View.VISIBLE);
        binding.pauseResumeButton.setText("إيقاف مؤقت");

        Intent intent = buildTaskIntent(binding, MediaDownloadService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent);
        else startService(intent);
    }

    private void sendTaskAction(DownloadUiBinding binding, String action) {
        Intent intent = buildTaskIntent(binding, action);
        if ((MediaDownloadService.ACTION_RESUME.equals(action)
                || MediaDownloadService.ACTION_RETRY.equals(action))
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private Intent buildTaskIntent(DownloadUiBinding binding, String action) {
        MediaFormatItem item = binding.item;
        Intent intent = new Intent(this, MediaDownloadService.class);
        intent.setAction(action);
        intent.putExtra(MediaDownloadService.EXTRA_TASK_ID, binding.taskId);
        intent.putExtra(MediaDownloadService.EXTRA_SOURCE_URL, item.sourceUrl);
        intent.putExtra(MediaDownloadService.EXTRA_DIRECT_URL, item.directUrl);
        intent.putExtra(MediaDownloadService.EXTRA_DIRECT_AUDIO_URL, item.directAudioUrl);
        intent.putExtra(MediaDownloadService.EXTRA_FORMAT_ID, item.formatId);
        intent.putExtra(MediaDownloadService.EXTRA_USE_BROWSER_SESSION, item.useBrowserSession);
        intent.putExtra(MediaDownloadService.EXTRA_MANIFEST, item.protocol != null && (item.protocol.contains("m3u8") || item.protocol.contains("dash")));
        intent.putExtra(MediaDownloadService.EXTRA_MEDIA_TYPE,
                item.type == MediaFormatItem.TYPE_AUDIO
                        ? MediaDownloadService.TYPE_AUDIO : MediaDownloadService.TYPE_VIDEO);
        intent.putExtra(MediaDownloadService.EXTRA_AUDIO_QUALITY, item.audioQualityKbps);
        intent.putExtra(MediaDownloadService.EXTRA_HAS_AUDIO, item.hasAudio);
        intent.putExtra(MediaDownloadService.EXTRA_CAPTURED_DIRECT, item.capturedDirect);
        intent.putExtra(MediaDownloadService.EXTRA_VIDEO_CODEC, item.videoCodec);
        intent.putExtra(MediaDownloadService.EXTRA_AUDIO_CODEC, item.audioCodec);
        intent.putExtra(MediaDownloadService.EXTRA_OUTPUT_EXT,
                item.type == MediaFormatItem.TYPE_AUDIO ? "mp3" :
                        (TextUtils.isEmpty(item.ext) ? "mp4" : item.ext));
        intent.putExtra(MediaDownloadService.EXTRA_TITLE, captureTitle.getText().toString());
        intent.putExtra(MediaDownloadService.EXTRA_ESTIMATED_SIZE, item.sizeBytes);
        intent.putExtra(MediaDownloadService.EXTRA_COOKIE_FILE, item.useBrowserSession ? latestCookieFilePath : "");
        intent.putExtra(MediaDownloadService.EXTRA_COOKIE_HEADER, item.useBrowserSession ? latestCookieHeader : "");
        intent.putExtra(MediaDownloadService.EXTRA_USER_AGENT, item.useBrowserSession ? latestUserAgent : "");
        return intent;
    }

    private void registerDownloadReceiver() {
        downloadReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null) return;
                String taskId = intent.getStringExtra(MediaDownloadService.EXTRA_TASK_ID);
                DownloadUiBinding binding = downloadBindings.get(taskId);
                if (binding == null) return;

                binding.state = intent.getStringExtra(MediaDownloadService.EXTRA_STATE);
                int progress = intent.getIntExtra(MediaDownloadService.EXTRA_PROGRESS, 0);
                long downloaded = intent.getLongExtra(MediaDownloadService.EXTRA_DOWNLOADED, 0L);
                long total = intent.getLongExtra(MediaDownloadService.EXTRA_TOTAL, 0L);
                long eta = intent.getLongExtra(MediaDownloadService.EXTRA_ETA, 0L);
                String speed = intent.getStringExtra(MediaDownloadService.EXTRA_SPEED);
                String message = intent.getStringExtra(MediaDownloadService.EXTRA_MESSAGE);

                updateDownloadUi(binding, progress, downloaded, total, eta, speed, message);
            }
        };
        IntentFilter filter = new IntentFilter(MediaDownloadService.ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(downloadReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(downloadReceiver, filter);
        }
    }

    private void updateDownloadUi(DownloadUiBinding binding, int progress,
                                  long downloaded, long total, long eta,
                                  String speed, String message) {
        String state = binding.state;
        binding.status.setVisibility(View.VISIBLE);
        binding.status.setText(buildUiProgressText(progress, downloaded, total, eta, speed, message));

        if (MediaDownloadService.STATE_RUNNING.equals(state)
                || MediaDownloadService.STATE_QUEUED.equals(state)) {
            binding.downloadButton.setEnabled(false);
            binding.progress.setVisibility(View.VISIBLE);
            binding.progress.setIndeterminate(progress <= 0);
            if (progress > 0) binding.progress.setProgress(progress);
            binding.controls.setVisibility(View.VISIBLE);
            binding.pauseResumeButton.setText("إيقاف مؤقت");
            binding.pauseResumeButton.setEnabled(true);
            binding.cancelButton.setText("إلغاء");
        } else if (MediaDownloadService.STATE_PAUSED.equals(state)) {
            binding.downloadButton.setEnabled(false);
            binding.progress.setVisibility(View.VISIBLE);
            binding.progress.setIndeterminate(false);
            binding.progress.setProgress(progress);
            binding.controls.setVisibility(View.VISIBLE);
            binding.pauseResumeButton.setText("استئناف");
            binding.cancelButton.setText("إلغاء");
        } else if (MediaDownloadService.STATE_FAILED.equals(state)) {
            binding.downloadButton.setEnabled(false);
            binding.progress.setVisibility(View.VISIBLE);
            binding.progress.setIndeterminate(false);
            binding.controls.setVisibility(View.VISIBLE);
            binding.pauseResumeButton.setText("إعادة المحاولة");
            binding.cancelButton.setText("مسح");
        } else if (MediaDownloadService.STATE_COMPLETED.equals(state)) {
            binding.progress.setVisibility(View.VISIBLE);
            binding.progress.setIndeterminate(false);
            binding.progress.setProgress(100);
            binding.controls.setVisibility(View.GONE);
            binding.downloadButton.setEnabled(true);
            binding.downloadButton.setText("تحميل مرة أخرى");
            Toast.makeText(this, "اكتمل التحميل داخل Downloads/SatLink", Toast.LENGTH_LONG).show();
        } else if (MediaDownloadService.STATE_CANCELLED.equals(state)) {
            binding.progress.setVisibility(View.GONE);
            binding.controls.setVisibility(View.GONE);
            binding.downloadButton.setEnabled(true);
            binding.downloadButton.setText("تحميل");
        }
    }

    private String buildUiProgressText(int progress, long downloaded, long total,
                                       long eta, String speed, String fallback) {
        List<String> parts = new ArrayList<>();
        if (progress > 0) parts.add(progress + "%");
        if (downloaded > 0 && total > 0) parts.add(formatBytes(downloaded) + " / " + formatBytes(total));
        if (!TextUtils.isEmpty(speed)) parts.add(speed);
        if (eta > 0) parts.add("متبقي " + formatEta(eta));
        if (parts.isEmpty() && !TextUtils.isEmpty(fallback)) parts.add(fallback);
        return TextUtils.join("  •  ", parts);
    }

    private String taskIdFor(MediaFormatItem item) {
        String raw = item.type + "|" + item.sourceUrl + "|" + item.directUrl + "|"
                + item.formatId + "|" + item.audioQualityKbps + "|" + item.ext;
        return (item.type == MediaFormatItem.TYPE_AUDIO ? "a_" : "v_")
                + Integer.toHexString(raw.hashCode());
    }

    private static String formatEta(long seconds) {
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        if (hours > 0) return String.format(Locale.US, "%d:%02d:%02d", hours, minutes, secs);
        return String.format(Locale.US, "%02d:%02d", minutes, secs);
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_REQUEST);
        }
    }

    private void resetFormatLists() {
        downloadBindings.clear();
        videoResults.removeAllViews();
        audioResults.removeAllViews();
        videoScroll.setVisibility(View.GONE);
        audioScroll.setVisibility(View.GONE);
        videoArrow.setText("⌄");
        audioArrow.setText("⌄");
        updateCounts(0, 0);
    }

    private void updateCounts(int videos, int audios) {
        videoCount.setText(videos + " جودة");
        audioCount.setText(audios + " جودة");
    }

    private void toggleVideoList() {
        if (videoScroll.getVisibility() == View.VISIBLE) {
            videoScroll.setVisibility(View.GONE);
            videoArrow.setText("⌄");
        } else {
            openVideoList();
        }
    }

    private void toggleAudioList() {
        if (audioScroll.getVisibility() == View.VISIBLE) {
            audioScroll.setVisibility(View.GONE);
            audioArrow.setText("⌄");
        } else {
            openAudioList();
        }
    }

    private void openVideoList() {
        audioScroll.setVisibility(View.GONE);
        audioArrow.setText("⌄");
        videoScroll.setVisibility(View.VISIBLE);
        videoArrow.setText("⌃");
    }

    private void openAudioList() {
        videoScroll.setVisibility(View.GONE);
        videoArrow.setText("⌄");
        audioScroll.setVisibility(View.VISIBLE);
        audioArrow.setText("⌃");
    }

    private static boolean isSupportedModernVideo(MediaFormatItem item) {
        if (item == null || TextUtils.isEmpty(item.ext) || TextUtils.isEmpty(item.videoCodec)) return false;
        String ext = item.ext.toLowerCase(Locale.US);
        String codec = item.videoCodec.toUpperCase(Locale.US);
        if ("mkv".equals(ext) || "ts".equals(ext) || "mpegts".equals(ext)
                || "mov".equals(ext) || "m4v".equals(ext)) return true;
        if ("mp4".equals(ext)) {
            return "H.264".equalsIgnoreCase(codec) || "H.265".equalsIgnoreCase(codec)
                    || "AV1".equalsIgnoreCase(codec) || codec.contains("VP9");
        }
        if ("webm".equals(ext)) {
            return "VP9".equalsIgnoreCase(codec) || "AV1".equalsIgnoreCase(codec);
        }
        return false;
    }

    private GradientDrawable roundedBackground(int fillColor, int strokeColor, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fillColor);
        drawable.setCornerRadius(dp(radiusDp));
        drawable.setStroke(dp(1), strokeColor);
        return drawable;
    }

    private void closeCapturePanel() {
        capturePanel.setVisibility(View.GONE);
        sharedNavigationPending = false;
        captureRequestSerial++;
        stopActiveAnalysis();
    }

    private String normalizePageUrl(String url) {
        if (TextUtils.isEmpty(url)) return url;
        String normalized = url.trim();
        normalized = normalized.replace("https://m.youtube.com/", "https://www.youtube.com/")
                .replace("http://m.youtube.com/", "https://www.youtube.com/")
                .replace("https://music.youtube.com/", "https://www.youtube.com/")
                .replace("http://music.youtube.com/", "https://www.youtube.com/");
        return normalized;
    }

    private void configureExtensionPrompts() {
        runtime.getWebExtensionController().setPromptDelegate(new WebExtensionController.PromptDelegate() {
            @Override
            public GeckoResult<WebExtension.PermissionPromptResponse> onInstallPromptRequest(
                    WebExtension extension, String[] permissions, String[] origins,
                    String[] dataCollectionPermissions) {
                return GeckoResult.fromValue(new WebExtension.PermissionPromptResponse(true, false, false));
            }

            @Override
            public GeckoResult<AllowOrDeny> onUpdatePrompt(WebExtension extension,
                    String[] newPermissions, String[] newOrigins, String[] newDataCollectionPermissions) {
                return GeckoResult.fromValue(AllowOrDeny.ALLOW);
            }

            @Override
            public GeckoResult<AllowOrDeny> onOptionalPrompt(WebExtension extension,
                    String[] permissions, String[] origins, String[] dataCollectionPermissions) {
                return GeckoResult.fromValue(AllowOrDeny.ALLOW);
            }
        });
    }

    private void hideAllOverlays() {
        if (browserToolbar != null) browserToolbar.setVisibility(View.VISIBLE);
        homeOverlay.setVisibility(View.GONE);
        downloadsOverlay.setVisibility(View.GONE);
        extensionsOverlay.setVisibility(View.GONE);
        tabsOverlay.setVisibility(View.GONE);
        favoritesOverlay.setVisibility(View.GONE);
    }

    private void sendPlaybackMessage(String action, boolean allowed) {
        JSONObject message = new JSONObject();
        try {message.put("action", action);message.put("allowed", allowed);} catch (Exception ignored) { }
        for (WebExtension.Port port : new ArrayList<>(capturePorts)) {
            try {
                JSONObject scoped = new JSONObject();
                scoped.put("action", action);
                BrowserTab owner = findTab(port.sender.session);
                scoped.put("tabKey", owner == null ? "" : owner.key);
                scoped.put("allowed", allowed && port.sender.session == session);
                port.postMessage(scoped);
            } catch (Throwable error) {capturePorts.remove(port);}
        }
    }

    private void pauseBrowserMedia() {
        if (browserMediaControl != null) browserMediaControl.pause();
        sendPlaybackMessage("pauseMedia", false);
    }

    private void updateBrowserPlayback() {
        boolean allowed = activityForeground && "browser".equals(activeScreen);
        if (!allowed) pauseBrowserMedia();
        if (browserMediaControl != null) browserMediaControl.muteAudio(!allowed);
        sendPlaybackMessage("setPlaybackAllowed", allowed);
        if (session != null) session.setActive(allowed);
    }

    /** Sends a user playback command to the frames of the visible tab only. */
    private void sendPlayerControl(String command, double value) {
        for (WebExtension.Port port : new ArrayList<>(capturePorts)) {
            if (port.sender.session != session) continue;
            try {
                JSONObject scoped = new JSONObject();
                scoped.put("action", "playerControl");
                BrowserTab owner = findTab(port.sender.session);
                scoped.put("tabKey", owner == null ? "" : owner.key);
                scoped.put("allowed", activityForeground && "browser".equals(activeScreen));
                scoped.put("command", command);
                scoped.put("value", value);
                port.postMessage(scoped);
            } catch (Throwable error) {capturePorts.remove(port);}
        }
    }

    private BrowserTab findTabByKey(String key) {
        if (TextUtils.isEmpty(key)) return null;
        for (BrowserTab tab : browserTabs) if (tab.key.equals(key)) return tab;
        return null;
    }

    /** One step back: page history first, then the page that opened this tab, then home. */
    private void goBackInBrowser() {
        pauseBrowserMedia();
        BrowserTab visible = findTab(session);
        boolean historyBehind = visible != null && visible.historyIndex > 0;
        if (browserCanGoBack || historyBehind) {
            backNavigationPending = true;
            session.goBack();
            return;
        }
        BrowserTab current = findTab(session);
        BrowserTab opener = current == null ? null : findTabByKey(current.openerKey);
        if (opener != null && opener != current && opener.session.isOpen()) {
            selectTab(opener);
            closeTab(current);
            return;
        }
        showHomeScreen();
    }

    private void openInNewTab(String url, boolean rememberOpener) {
        if (TextUtils.isEmpty(url)) return;
        BrowserTab opener = findTab(session);
        GeckoSession created = createTabSession();
        BrowserTab tab = new BrowserTab(created);
        if (rememberOpener && opener != null) tab.openerKey = opener.key;
        browserTabs.add(tab);
        created.open(runtime);
        selectTab(tab);
        showBrowserScreen();
        created.loadUri(url);
    }

    @Override
    protected void onPause() {
        activityForeground = false;
        persistBrowserWorkspace();
        downloadsList.removeCallbacks(generalDownloadsRefresh);
        updateBrowserPlayback();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        activityForeground = true;
        updateBrowserPlayback();
        if ("downloads".equals(activeScreen)) refreshDownloads();
    }

    private void showHomeScreen() {
        returnToBrowserFromHome = false;
        closeCapturePanel();
        hideAllOverlays();
        if (browserToolbar != null) browserToolbar.setVisibility(View.GONE);
        homeOverlay.setVisibility(View.VISIBLE);
        captureButton.setVisibility(View.GONE);
        activeScreen = "home";
        updateBrowserPlayback();
        setSelectedNav(navHome);
        renderShortcuts();
    }

    private void showBrowserScreen() {
        returnToBrowserFromHome = false;
        hideAllOverlays();
        captureButton.setVisibility(View.GONE);
        activeScreen = "browser";
        updateBrowserPlayback();
        clearSelectedNav();
    }

    private void showDownloadsScreen() {
        closeCapturePanel();
        hideAllOverlays();
        downloadsOverlay.setVisibility(View.VISIBLE);
        captureButton.setVisibility(View.GONE);
        activeScreen = "downloads";
        updateBrowserPlayback();
        setSelectedNav(navDownloads);
        refreshDownloads();
    }

    private void showExtensionsScreen() {
        closeCapturePanel();
        hideAllOverlays();
        extensionsOverlay.setVisibility(View.VISIBLE);
        captureButton.setVisibility(View.GONE);
        activeScreen = "extensions";
        updateBrowserPlayback();
        setSelectedNav(navExtensions);
        refreshExtensions();
    }

    private void showTabsScreen() {
        closeCapturePanel();
        hideAllOverlays();
        tabsOverlay.setVisibility(View.VISIBLE);
        captureButton.setVisibility(View.GONE);
        activeScreen = "tabs";
        updateBrowserPlayback();
        setSelectedNav(navTabs);
        renderTabs();
    }

    private void showFavoritesScreen() {
        closeCapturePanel();
        hideAllOverlays();
        favoritesOverlay.setVisibility(View.VISIBLE);
        captureButton.setVisibility(View.GONE);
        activeScreen = "favorites";
        updateBrowserPlayback();
        clearSelectedNav();
        favoritesSearch.setText("");
        renderFavorites("");
    }

    private void toggleCurrentFavorite() {
        String url = normalizePageUrl(addressBar.getText().toString().trim());
        if (TextUtils.isEmpty(url) || "about:blank".equals(url)) {
            Toast.makeText(this, "افتح صفحة أولاً حتى تضيفها للمفضلات", Toast.LENGTH_SHORT).show();
            return;
        }
        int existing = findFavoriteIndex(url);
        if (existing >= 0) {
            favorites.remove(existing);
            saveFavorites();
            updateFavoriteButtonState();
            Toast.makeText(this, "تم حذف الصفحة من المفضلات", Toast.LENGTH_SHORT).show();
            if (favoritesOverlay.getVisibility() == View.VISIBLE) renderFavorites(favoritesSearch.getText().toString());
            return;
        }
        String title = TextUtils.isEmpty(pendingPageTitle) ? hostName(url) : pendingPageTitle.trim();
        favorites.add(0, new FavoriteItem(title, url, System.currentTimeMillis()));
        saveFavorites();
        updateFavoriteButtonState();
        Toast.makeText(this, "تمت إضافة الصفحة إلى المفضلات", Toast.LENGTH_SHORT).show();
    }

    private int findFavoriteIndex(String url) {
        String normalized = normalizeFavoriteUrl(url);
        for (int i = 0; i < favorites.size(); i++) {
            if (normalizeFavoriteUrl(favorites.get(i).url).equals(normalized)) return i;
        }
        return -1;
    }

    private String normalizeFavoriteUrl(String value) {
        if (value == null) return "";
        String result = value.trim();
        while (result.endsWith("/") && result.length() > 8) result = result.substring(0, result.length() - 1);
        return result;
    }

    private String hostName(String url) {
        try {
            String host = Uri.parse(url).getHost();
            if (!TextUtils.isEmpty(host)) return host.replaceFirst("^www\\.", "");
        } catch (Throwable ignored) {}
        return "صفحة محفوظة";
    }

    private void updateFavoriteButtonState() {
        String url = normalizePageUrl(addressBar == null ? "" : addressBar.getText().toString().trim());
        boolean saved = !TextUtils.isEmpty(url) && findFavoriteIndex(url) >= 0;
        if (addFavoriteButton != null) {
            addFavoriteButton.setText(saved ? "♥" : "♡+");
            addFavoriteButton.setTextColor(saved ? 0xFFFF4B9F : 0xFFFF7AC0);
            addFavoriteButton.setContentDescription(saved ? "حذف من المفضلات" : "إضافة إلى المفضلات");
        }
    }

    private void loadFavorites() {
        favorites.clear();
        String raw = getSharedPreferences("satlink_favorites", MODE_PRIVATE).getString("items", "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                String title = item.optString("title", "صفحة محفوظة");
                String url = item.optString("url", "");
                long createdAt = item.optLong("createdAt", 0L);
                if (!TextUtils.isEmpty(url) && findFavoriteIndex(url) < 0) favorites.add(new FavoriteItem(title, url, createdAt));
            }
        } catch (Throwable error) {
            Log.w(TAG, "Could not read favorites", error);
        }
    }

    private void saveFavorites() {
        JSONArray array = new JSONArray();
        for (FavoriteItem item : favorites) {
            JSONObject object = new JSONObject();
            try {
                object.put("title", item.title);
                object.put("url", item.url);
                object.put("createdAt", item.createdAt);
                array.put(object);
            } catch (Throwable ignored) {}
        }
        getSharedPreferences("satlink_favorites", MODE_PRIVATE).edit().putString("items", array.toString()).apply();
    }

    private void renderFavorites(String query) {
        if (favoritesList == null) return;
        favoritesList.removeAllViews();
        String filter = query == null ? "" : query.trim().toLowerCase(Locale.US);
        int shown = 0;
        for (FavoriteItem item : favorites) {
            if (!filter.isEmpty() && !item.title.toLowerCase(Locale.US).contains(filter)
                    && !item.url.toLowerCase(Locale.US).contains(filter)) continue;
            shown++;
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackgroundResource(R.drawable.favorite_item_background);
            card.setPadding(dp(14), dp(12), dp(14), dp(12));
            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2);
            cardParams.bottomMargin = dp(10);
            card.setLayoutParams(cardParams);

            TextView title = new TextView(this);
            title.setText("♥  " + item.title);
            title.setTextColor(Color.WHITE);
            title.setTextSize(16);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setGravity(Gravity.END);
            card.addView(title, new LinearLayout.LayoutParams(-1, -2));

            TextView url = new TextView(this);
            url.setText(item.url);
            url.setTextColor(0xFFB7AFC0);
            url.setTextSize(11);
            url.setMaxLines(1);
            url.setEllipsize(TextUtils.TruncateAt.END);
            url.setGravity(Gravity.END);
            LinearLayout.LayoutParams urlParams = new LinearLayout.LayoutParams(-1, -2);
            urlParams.topMargin = dp(4);
            card.addView(url, urlParams);

            LinearLayout actions = new LinearLayout(this);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            actions.setGravity(Gravity.END);
            actions.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
            LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(-1, -2);
            actionsParams.topMargin = dp(10);

            Button open = smallActionButton("فتح");
            open.setOnClickListener(v -> { openUrl(item.url); showBrowserScreen(); });
            actions.addView(open);

            Button delete = smallActionButton("حذف");
            delete.setTextColor(0xFFFF6B9D);
            delete.setOnClickListener(v -> {
                int index = findFavoriteIndex(item.url);
                if (index >= 0) favorites.remove(index);
                saveFavorites();
                updateFavoriteButtonState();
                renderFavorites(favoritesSearch.getText().toString());
            });
            LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(dp(72), dp(38));
            deleteParams.setMarginStart(dp(8));
            actions.addView(delete, deleteParams);
            card.addView(actions, actionsParams);
            card.setOnClickListener(v -> { openUrl(item.url); showBrowserScreen(); });
            favoritesList.addView(card);
        }
        favoritesEmpty.setVisibility(shown == 0 ? View.VISIBLE : View.GONE);
    }

    private Button smallActionButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(12);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinHeight(0);
        button.setPadding(0, 0, 0, 0);
        button.setBackgroundResource(R.drawable.toolbar_icon_background);
        button.setStateListAnimator(null);
        button.setLayoutParams(new LinearLayout.LayoutParams(dp(72), dp(38)));
        return button;
    }

    private static final class FavoriteItem {
        final String title;
        final String url;
        final long createdAt;
        FavoriteItem(String title, String url, long createdAt) {
            this.title = TextUtils.isEmpty(title) ? "صفحة محفوظة" : title;
            this.url = url;
            this.createdAt = createdAt;
        }
    }

    private void clearSelectedNav() {
        TextView[] items = new TextView[]{navHome, navDownloads, navExtensions, navTabs};
        for (TextView item : items) {
            item.setTextColor(0xFFAEA6B4);
            item.setTypeface(Typeface.DEFAULT);
        }
    }

    private void setSelectedNav(TextView selected) {
        clearSelectedNav();
        selected.setTextColor(0xFFFF4B9F);
        selected.setTypeface(Typeface.DEFAULT_BOLD);
    }

    private void loadFromHomeSearch() {
        String value = homeSearch.getText().toString().trim();
        if (value.isEmpty()) return;
        addressBar.setText(value);
        loadFromAddressBar();
        homeSearch.setText("");
    }

    private void openUrl(String url) {
        addressBar.setText(url);
            updateFavoriteButtonState();
        loadFromAddressBar();
    }

    private JSONArray loadShortcuts() {
        String saved = getSharedPreferences("satlink_home", MODE_PRIVATE)
                .getString("shortcuts", "");
        if (!TextUtils.isEmpty(saved)) {
            try { return new JSONArray(saved); } catch (Exception ignored) { }
        }
        JSONArray defaults = new JSONArray();
        addShortcutObject(defaults, "YouTube", "https://www.youtube.com/");
        addShortcutObject(defaults, "Facebook", "https://www.facebook.com/");
        addShortcutObject(defaults, "TikTok", "https://www.tiktok.com/");
        addShortcutObject(defaults, "Instagram", "https://www.instagram.com/");
        addShortcutObject(defaults, "X", "https://x.com/");
        saveShortcuts(defaults);
        return defaults;
    }

    private static void addShortcutObject(JSONArray array, String name, String url) {
        try {
            JSONObject item = new JSONObject();
            item.put("name", name);
            item.put("url", url);
            array.put(item);
        } catch (Exception ignored) { }
    }

    private void saveShortcuts(JSONArray array) {
        getSharedPreferences("satlink_home", MODE_PRIVATE).edit()
                .putString("shortcuts", array.toString()).apply();
    }

    private void renderShortcuts() {
        if (shortcutGrid == null) return;
        shortcutGrid.removeAllViews();
        JSONArray shortcuts = loadShortcuts();
        List<View> tiles = new ArrayList<>();
        for (int i = 0; i < shortcuts.length(); i++) {
            JSONObject item = shortcuts.optJSONObject(i);
            if (item == null) continue;
            final int index = i;
            String name = item.optString("name", "موقع");
            String url = item.optString("url", "");
            TextView tile = shortcutTile(siteSymbol(name), name);
            tile.setOnClickListener(v -> openUrl(url));
            tile.setOnLongClickListener(v -> {
                confirmRemoveShortcut(index, name);
                return true;
            });
            tiles.add(tile);
        }
        TextView add = shortcutTile("＋", "إضافة موقع");
        add.setTextColor(0xFFFF62B1);
        add.setOnClickListener(v -> showAddShortcutDialog());
        tiles.add(add);

        for (int i = 0; i < tiles.size(); i += 3) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rowParams.setMargins(0, 0, 0, dp(10));
            row.setLayoutParams(rowParams);
            for (int c = 0; c < 3; c++) {
                if (i + c < tiles.size()) row.addView(tiles.get(i + c));
                else {
                    Space space = new Space(this);
                    space.setLayoutParams(new LinearLayout.LayoutParams(0, dp(96), 1f));
                    row.addView(space);
                }
            }
            shortcutGrid.addView(row);
        }
    }

    private TextView shortcutTile(String symbol, String name) {
        TextView tile = new TextView(this);
        tile.setGravity(Gravity.CENTER);
        tile.setText(symbol + "\n" + name);
        tile.setTextColor(Color.WHITE);
        tile.setTextSize(13);
        tile.setTypeface(Typeface.DEFAULT_BOLD);
        tile.setBackground(roundedBackground(0xFF151219, 0xFF3E263B, 17));
        tile.setPadding(dp(5), dp(12), dp(5), dp(12));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(96), 1f);
        params.setMargins(dp(5), 0, dp(5), 0);
        tile.setLayoutParams(params);
        return tile;
    }

    private static String siteSymbol(String name) {
        String lower = name.toLowerCase(Locale.US);
        if (lower.contains("youtube")) return "▶";
        if (lower.contains("facebook")) return "f";
        if (lower.contains("tiktok")) return "♪";
        if (lower.contains("instagram")) return "◎";
        if (lower.equals("x") || lower.contains("twitter")) return "𝕏";
        return "◆";
    }

    private void showAddShortcutDialog() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(6), dp(20), 0);
        EditText nameInput = new EditText(this);
        nameInput.setHint("اسم الموقع");
        EditText urlInput = new EditText(this);
        urlInput.setHint("https://example.com");
        urlInput.setInputType(android.text.InputType.TYPE_TEXT_VARIATION_URI);
        form.addView(nameInput);
        form.addView(urlInput);
        new AlertDialog.Builder(this)
                .setTitle("إضافة موقع سريع")
                .setView(form)
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("إضافة", (dialog, which) -> {
                    String name = nameInput.getText().toString().trim();
                    String url = urlInput.getText().toString().trim();
                    if (TextUtils.isEmpty(name) || TextUtils.isEmpty(url)) {
                        Toast.makeText(this, "اكتب الاسم والرابط", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://" + url;
                    JSONArray array = loadShortcuts();
                    addShortcutObject(array, name, url);
                    saveShortcuts(array);
                    renderShortcuts();
                }).show();
    }

    private void confirmRemoveShortcut(int index, String name) {
        new AlertDialog.Builder(this)
                .setTitle("حذف الاختصار")
                .setMessage("حذف " + name + " من الواجهة الرئيسية؟")
                .setNegativeButton("لا", null)
                .setPositiveButton("حذف", (dialog, which) -> {
                    JSONArray old = loadShortcuts();
                    JSONArray next = new JSONArray();
                    for (int i = 0; i < old.length(); i++) if (i != index) next.put(old.opt(i));
                    saveShortcuts(next);
                    renderShortcuts();
                }).show();
    }

    private void refreshDownloads() {
        downloadsList.removeAllViews();
        int found = 0;
        downloadsList.removeCallbacks(generalDownloadsRefresh);
        List<GeneralDownloadCatalog.Entry> nativeFiles = new ArrayList<>();
        try {
            nativeFiles = GeneralDownloadCatalog.read((DownloadManager)getSystemService(DOWNLOAD_SERVICE));
        } catch (Throwable error) {
            addMessageCard(downloadsList, "تعذر قراءة مدير التنزيل: " + safeMessage(error));
        }
        Set<String> nativeNames = GeneralDownloadCatalog.representedNames(nativeFiles);
        for (MediaDownloadService.Snapshot media : MediaDownloadService.snapshot()) {
            downloadsList.addView(mediaDownloadCard(media));
            found++;
        }
        for (FileDownloadService.Task task : FileDownloadService.snapshot(this)) {
            if (FileDownloadService.DONE.equals(task.state)) continue;
            downloadsList.addView(engineDownloadCard(task));
            nativeNames.add(task.name);
            found++;
        }
        for (GeneralDownloadCatalog.Entry item : nativeFiles) {
            downloadsList.addView(generalDownloadCard(item));
            found++;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Uri collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
            String[] projection = new String[]{MediaStore.Downloads._ID,
                    MediaStore.Downloads.DISPLAY_NAME, MediaStore.Downloads.SIZE,
                    MediaStore.Downloads.MIME_TYPE, MediaStore.Downloads.DATE_ADDED,
                    MediaStore.Downloads.RELATIVE_PATH};
            String selection = MediaStore.Downloads.RELATIVE_PATH + " LIKE ?";
            String[] args = new String[]{"%SatLink%"};
            try (Cursor cursor = getContentResolver().query(collection, projection, selection, args,
                    MediaStore.Downloads.DATE_ADDED + " DESC")) {
                if (cursor != null) {
                    int idIndex = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID);
                    int nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME);
                    int sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.Downloads.SIZE);
                    int mimeIndex = cursor.getColumnIndexOrThrow(MediaStore.Downloads.MIME_TYPE);
                    while (cursor.moveToNext()) {
                        long id = cursor.getLong(idIndex);
                        String name = cursor.getString(nameIndex);
                        if (nativeNames.contains(name)) continue;
                        long size = cursor.getLong(sizeIndex);
                        String mime = cursor.getString(mimeIndex);
                        Uri uri = ContentUris.withAppendedId(collection, id);
                        downloadsList.addView(downloadFileCard(name, size, mime, uri, null));
                        found++;
                    }
                }
            } catch (Throwable error) {
                addMessageCard(downloadsList, "تعذر قراءة التنزيلات: " + safeMessage(error));
            }
        } else {
            File folder = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "SatLink");
            File[] files = folder.listFiles();
            if (files != null) {
                java.util.Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
                for (File file : files) {
                    if (!file.isFile() || nativeNames.contains(file.getName())) continue;
                    downloadsList.addView(downloadFileCard(file.getName(), file.length(), mimeForName(file.getName()), null, file));
                    found++;
                }
            }
        }
        if (found == 0) addMessageCard(downloadsList, "لا توجد ملفات داخل Downloads/SatLink حالياً");
        if (activityForeground && "downloads".equals(activeScreen))
            downloadsList.postDelayed(generalDownloadsRefresh, 1000L);
    }

    private View generalDownloadCard(GeneralDownloadCatalog.Entry item) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setBackground(roundedBackground(0xFF151219, 0xFF67314F, 15));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, 0, 0, dp(9));card.setLayoutParams(params);
        TextView title = new TextView(this);
        title.setText(item.name);title.setTextColor(Color.WHITE);title.setTextSize(14);
        title.setTypeface(Typeface.DEFAULT_BOLD);card.addView(title);
        TextView status = new TextView(this);
        String bytes = formatBytes(Math.max(0, item.downloaded));
        if (item.total > 0) bytes += " / " + formatBytes(item.total);
        int percent = item.percent();
        status.setText(item.state() + (percent < 0 ? "" : "  •  " + percent + "%")
                + "\n" + (TextUtils.isEmpty(item.mime) ? "ملف" : item.mime) + "  •  " + bytes);
        status.setTextColor(0xFFAAA3B2);status.setTextSize(11);
        status.setPadding(0, dp(5), 0, dp(10));card.addView(status);
        if (item.active()) {
            ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            progress.setIndeterminate(percent < 0);progress.setMax(100);
            if (percent >= 0) progress.setProgress(percent);
            card.addView(progress, new LinearLayout.LayoutParams(-1, dp(8)));
        }
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);actions.setGravity(Gravity.END);
        DownloadManager manager = (DownloadManager)getSystemService(DOWNLOAD_SERVICE);
        if (item.complete()) {
            Button open = smallDownloadActionButton("فتح");actions.addView(open);
            open.setOnClickListener(v -> {
                try {
                    Uri uri = GeneralDownloadCatalog.openUri(manager, item);
                    if (uri == null) throw new IllegalStateException("الملف غير متاح");
                    openDownloadedUri(uri, item.mime);
                } catch (Throwable error) {
                    Toast.makeText(this, "تعذر فتح الملف: " + safeMessage(error), Toast.LENGTH_SHORT).show();
                }
            });
        }
        Button folder = smallDownloadActionButton("فتح المجلد");actions.addView(folder);
        folder.setOnClickListener(v -> openDownloadsFolder());
        boolean cancel = item.active();
        Button remove = smallDownloadActionButton(cancel ? "إلغاء" : "حذف");actions.addView(remove);
        remove.setTextColor(0xFFFF7DAA);
        remove.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle(cancel ? "إلغاء التنزيل" : "حذف الملف")
                .setMessage((cancel ? "إلغاء التنزيل وحذف الجزء المحمّل؟\n" : "حذف الملف نهائياً؟\n") + item.name)
                .setNegativeButton("رجوع", null)
                .setPositiveButton(cancel ? "إلغاء التنزيل" : "حذف", (dialog, which) -> {
                    try {
                        boolean removed = GeneralDownloadCatalog.remove(manager, item);
                        Toast.makeText(this, removed ? "تمت الإزالة" : "تعذر إزالة التنزيل", Toast.LENGTH_SHORT).show();
                    } catch (Throwable error) {
                        Toast.makeText(this, "تعذر إزالة التنزيل: " + safeMessage(error), Toast.LENGTH_SHORT).show();
                    }
                    refreshDownloads();
                }).show());
        card.addView(actions);
        return card;
    }

    /** A running video/audio download (yt-dlp service). */
    private View mediaDownloadCard(MediaDownloadService.Snapshot media) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setBackground(roundedBackground(0xFF151219, 0xFFFF4B9F, 15));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, 0, 0, dp(9));card.setLayoutParams(params);
        TextView title = new TextView(this);
        title.setText("🎬 " + media.title);
        title.setTextColor(Color.WHITE);title.setTextSize(14);title.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(title);
        boolean active = MediaDownloadService.STATE_RUNNING.equals(media.state) || MediaDownloadService.STATE_QUEUED.equals(media.state);
        String state = MediaDownloadService.STATE_PAUSED.equals(media.state) ? "متوقف مؤقتاً"
                : MediaDownloadService.STATE_FAILED.equals(media.state) ? "فشل"
                : MediaDownloadService.STATE_QUEUED.equals(media.state) ? "بالانتظار" : "جاري التنزيل";
        StringBuilder line = new StringBuilder(state).append("  •  ").append(media.progress).append('%');
        if (active && !TextUtils.isEmpty(media.speed)) line.append("  •  ").append(media.speed);
        if (active && media.eta > 0) line.append("  •  ").append(formatEta(media.eta));
        if (media.total > 0) line.append('\n').append(FileDownloadService.Formats.bytes(media.downloaded))
                .append(" / ").append(FileDownloadService.Formats.bytes(media.total));
        if (MediaDownloadService.STATE_FAILED.equals(media.state) && !TextUtils.isEmpty(media.message)) line.append('\n').append(media.message);
        TextView status = new TextView(this);
        status.setText(line.toString());
        status.setTextColor(0xFFAAA3B2);status.setTextSize(11);status.setPadding(0, dp(5), 0, dp(10));
        card.addView(status);
        ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);progress.setProgress(media.progress);
        card.addView(progress, new LinearLayout.LayoutParams(-1, dp(8)));
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);actions.setGravity(Gravity.END);
        actions.setPadding(0, dp(8), 0, 0);
        if (active) {
            Button pause = smallDownloadActionButton("إيقاف مؤقت");actions.addView(pause);
            pause.setOnClickListener(v -> {mediaTaskAction(media.id, MediaDownloadService.ACTION_PAUSE);refreshDownloads();});
        } else if (MediaDownloadService.STATE_PAUSED.equals(media.state) || MediaDownloadService.STATE_FAILED.equals(media.state)) {
            boolean failed = MediaDownloadService.STATE_FAILED.equals(media.state);
            Button resume = smallDownloadActionButton(failed ? "إعادة المحاولة" : "استكمال");actions.addView(resume);
            resume.setOnClickListener(v -> {mediaTaskAction(media.id, failed ? MediaDownloadService.ACTION_RETRY : MediaDownloadService.ACTION_RESUME);refreshDownloads();});
        }
        Button cancel = smallDownloadActionButton("إلغاء");actions.addView(cancel);
        cancel.setTextColor(0xFFFF7DAA);
        cancel.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("إلغاء التنزيل")
                .setMessage("إلغاء التنزيل وحذف الجزء المحمّل؟\n" + media.title)
                .setNegativeButton("رجوع", null)
                .setPositiveButton("إلغاء التنزيل", (dialog, which) -> {
                    mediaTaskAction(media.id, MediaDownloadService.ACTION_CANCEL);
                    refreshDownloads();
                }).show());
        card.addView(actions);
        return card;
    }

    private void mediaTaskAction(String taskId, String action) {
        Intent intent = new Intent(this, MediaDownloadService.class).setAction(action)
                .putExtra(MediaDownloadService.EXTRA_TASK_ID, taskId);
        try {
            if ((MediaDownloadService.ACTION_RESUME.equals(action) || MediaDownloadService.ACTION_RETRY.equals(action))
                    && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent);
            else startService(intent);
        } catch (Throwable error) {
            Toast.makeText(this, "تعذر تنفيذ الأمر: " + safeMessage(error), Toast.LENGTH_SHORT).show();
        }
    }

    /** A file or torrent download from the multi-connection engine. */
    private View engineDownloadCard(FileDownloadService.Task task) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setBackground(roundedBackground(0xFF151219, 0xFF8B3FD9, 15));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, 0, 0, dp(9));card.setLayoutParams(params);
        boolean torrent = FileDownloadService.KIND_TORRENT.equals(task.kind);
        TextView title = new TextView(this);
        title.setText((torrent ? "🧲 " : "") + task.name);
        title.setTextColor(Color.WHITE);title.setTextSize(14);title.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(title);
        String state;
        switch (task.state) {
            case FileDownloadService.QUEUED: state = "بالانتظار"; break;
            case FileDownloadService.RUNNING: state = torrent && task.total <= 0 ? "جاري جلب معلومات التورنت" : "جاري التنزيل"; break;
            case FileDownloadService.PAUSED: state = "متوقف مؤقتاً"; break;
            case FileDownloadService.FAILED: state = "فشل"; break;
            default: state = task.state;
        }
        int percent = task.percent();
        StringBuilder line = new StringBuilder(state);
        if (percent >= 0) line.append("  •  ").append(percent).append('%');
        line.append('\n').append(FileDownloadService.Formats.bytes(task.downloaded));
        if (task.total > 0) line.append(" / ").append(FileDownloadService.Formats.bytes(task.total));
        if (FileDownloadService.RUNNING.equals(task.state)) {
            line.append("  •  ").append(FileDownloadService.Formats.bytes(task.speed)).append("/ث");
            if (torrent) line.append("  •  ").append(task.connections).append(" نظير، ").append(task.peers).append(" مصدر");
            else line.append("  •  ").append(task.connections).append(" اتصال");
            if (task.speed > 0 && task.total > task.downloaded)
                line.append("  •  ").append(formatEta((task.total - task.downloaded) / task.speed));
        }
        if (FileDownloadService.FAILED.equals(task.state) && !TextUtils.isEmpty(task.error)) line.append('\n').append(task.error);
        TextView status = new TextView(this);
        status.setText(line.toString());
        status.setTextColor(0xFFAAA3B2);status.setTextSize(11);status.setPadding(0, dp(5), 0, dp(10));
        card.addView(status);
        ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setIndeterminate(percent < 0 && task.active());
        if (percent >= 0) progress.setProgress(percent);
        card.addView(progress, new LinearLayout.LayoutParams(-1, dp(8)));
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);actions.setGravity(Gravity.END);
        actions.setPadding(0, dp(8), 0, 0);
        if (task.active()) {
            Button pause = smallDownloadActionButton("إيقاف مؤقت");actions.addView(pause);
            pause.setOnClickListener(v -> {FileDownloadService.command(this, FileDownloadService.ACTION_PAUSE, task.id);refreshDownloads();});
        } else {
            Button resume = smallDownloadActionButton(FileDownloadService.FAILED.equals(task.state) ? "إعادة المحاولة" : "استكمال");
            actions.addView(resume);
            resume.setOnClickListener(v -> {FileDownloadService.command(this, FileDownloadService.ACTION_RESUME, task.id);refreshDownloads();});
        }
        Button cancel = smallDownloadActionButton("إلغاء");actions.addView(cancel);
        cancel.setTextColor(0xFFFF7DAA);
        cancel.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("إلغاء التنزيل")
                .setMessage("إلغاء التنزيل وحذف الجزء المحمّل؟\n" + task.name)
                .setNegativeButton("رجوع", null)
                .setPositiveButton("إلغاء التنزيل", (dialog, which) -> {
                    FileDownloadService.command(this, FileDownloadService.ACTION_CANCEL, task.id);
                    refreshDownloads();
                }).show());
        card.addView(actions);
        return card;
    }

    private void showConnectionSetting() {
        final int[] choices = {1, 4, 8, 16, 32};
        String[] labels = new String[choices.length];
        int current = FileDownloadService.connections(this), selected = choices.length - 1;
        for (int i = 0; i < choices.length; i++) {
            labels[i] = choices[i] + (choices[i] == 1 ? " اتصال" : " اتصالاً");
            if (choices[i] == current) selected = i;
        }
        new AlertDialog.Builder(this).setTitle("عدد الاتصالات لكل ملف")
                .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                    FileDownloadService.setConnections(this, choices[which]);
                    dialog.dismiss();
                    Toast.makeText(this, "يطبق على التنزيلات الجديدة والمستكملة. إذا رفض الموقع، قلّل العدد.", Toast.LENGTH_LONG).show();
                }).setNegativeButton("رجوع", null).show();
    }

    private View downloadFileCard(String name, long size, String mime, Uri uri, File legacyFile) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setBackground(roundedBackground(0xFF151219, 0xFF67314F, 15));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, dp(9));
        card.setLayoutParams(params);

        TextView title = new TextView(this);
        title.setText(name);
        title.setTextColor(Color.WHITE);
        title.setTextSize(14);
        title.setTypeface(Typeface.DEFAULT_BOLD);

        TextView details = new TextView(this);
        details.setText((TextUtils.isEmpty(mime) ? "ملف" : mime) + "  •  " + formatBytes(size));
        details.setTextColor(0xFFAAA3B2);
        details.setTextSize(11);
        details.setPadding(0, dp(4), 0, dp(10));
        card.addView(title);
        card.addView(details);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);

        Button openButton = smallDownloadActionButton("فتح");
        Button folderButton = smallDownloadActionButton("فتح المجلد");
        Button deleteButton = smallDownloadActionButton("حذف");
        deleteButton.setTextColor(0xFFFF7DAA);

        actions.addView(openButton);
        actions.addView(folderButton);
        actions.addView(deleteButton);
        card.addView(actions);

        openButton.setOnClickListener(v -> {
            if (uri != null) {
                openDownloadedUri(uri, mime);
            } else if (legacyFile != null && legacyFile.exists()) {
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    intent.setDataAndType(Uri.fromFile(legacyFile), TextUtils.isEmpty(mime) ? "*/*" : mime);
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(intent);
                } catch (Throwable error) {
                    Toast.makeText(this, "تعذر فتح الملف على هذا الجهاز", Toast.LENGTH_SHORT).show();
                }
            }
        });

        folderButton.setOnClickListener(v -> openDownloadsFolder());

        deleteButton.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("حذف الملف")
                .setMessage("هل تريد حذف " + name + " نهائياً؟")
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("حذف", (dialog, which) -> {
                    boolean deleted = false;
                    try {
                        if (uri != null) deleted = getContentResolver().delete(uri, null, null) > 0;
                        else if (legacyFile != null) deleted = legacyFile.delete();
                    } catch (Throwable ignored) { }
                    Toast.makeText(this, deleted ? "تم حذف الملف" : "تعذر حذف الملف",
                            Toast.LENGTH_SHORT).show();
                    refreshDownloads();
                }).show());
        return card;
    }

    private Button smallDownloadActionButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(11);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinHeight(0);
        button.setPadding(dp(13), 0, dp(13), 0);
        button.setBackground(roundedBackground(0xFF241A2B, 0xFF8B3B70, 12));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(38));
        params.setMargins(dp(6), 0, 0, 0);
        button.setLayoutParams(params);
        return button;
    }

    private void openDownloadsFolder() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            startActivity(intent);
            Toast.makeText(this, "افتح مجلد Downloads ثم SatLink", Toast.LENGTH_SHORT).show();
        } catch (Throwable error) {
            Toast.makeText(this, "تعذر فتح مدير الملفات", Toast.LENGTH_SHORT).show();
        }
    }

    private void openDownloadedUri(Uri uri, String mime) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, TextUtils.isEmpty(mime) ? "*/*" : mime);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
        } catch (Throwable error) {
            Toast.makeText(this, "لا يوجد تطبيق مناسب لفتح الملف", Toast.LENGTH_SHORT).show();
        }
    }

    private static String mimeForName(String name) {
        String ext = "";
        int dot = name == null ? -1 : name.lastIndexOf('.');
        if (dot >= 0) ext = name.substring(dot + 1).toLowerCase(Locale.US);
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return TextUtils.isEmpty(mime) ? "application/octet-stream" : mime;
    }

    private void addMessageCard(LinearLayout parent, String message) {
        TextView text = new TextView(this);
        text.setText(message);
        text.setTextColor(0xFFC7BECB);
        text.setTextSize(13);
        text.setGravity(Gravity.CENTER);
        text.setPadding(dp(12), dp(24), dp(12), dp(24));
        text.setBackground(roundedBackground(0xFF151219, 0xFF38283B, 15));
        parent.addView(text);
    }

    private void refreshExtensions() {
        extensionsList.removeAllViews();
        addMessageCard(extensionsList, "جاري قراءة الإضافات المثبتة...");
        runtime.getWebExtensionController().list().accept(list -> runOnUiThread(() -> {
            extensionsList.removeAllViews();
            int shown = 0;
            if (list != null) {
                for (WebExtension extension : list) {
                    if (extension == null || EXTENSION_ID.equals(extension.id)) continue;
                    extensionsList.addView(extensionCard(extension));
                    shown++;
                }
            }
            if (shown == 0) addMessageCard(extensionsList, "لا توجد إضافات خارجية مثبتة حالياً");
            updateExtensionToolbar(list);
        }), error -> runOnUiThread(() -> {
            extensionsList.removeAllViews();
            addMessageCard(extensionsList, "تعذر قراءة الإضافات: " + safeMessage(error));
            headerExtensionsButton.setText("🧩 !");
        }));
    }

    private void refreshExtensionToolbar() {
        if (runtime == null || headerExtensionsButton == null) return;
        runtime.getWebExtensionController().list().accept(list -> runOnUiThread(() ->
                updateExtensionToolbar(list)), error -> runOnUiThread(() ->
                headerExtensionsButton.setText("🧩 !")));
    }

    private void updateExtensionToolbar(List<WebExtension> list) {
        if (headerExtensionsButton == null) return;
        int enabledCount = 0;
        WebExtension blocker = null;
        if (list != null) {
            for (WebExtension extension : list) {
                if (extension == null || EXTENSION_ID.equals(extension.id)) continue;
                boolean enabled = extension.metaData == null || extension.metaData.disabledFlags == 0;
                if (!enabled) continue;
                enabledCount++;
                String name = extensionDisplayName(extension).toLowerCase(Locale.US);
                if (blocker == null && (name.contains("ublock") || name.contains("adblock")
                        || name.contains("ad blocker") || name.contains("مانع"))) {
                    blocker = extension;
                }
            }
        }
        if (blocker != null) {
            String label = extensionDisplayName(blocker);
            if (label.length() > 10) label = label.substring(0, 10);
            headerExtensionsButton.setText("🛡 " + label);
        } else {
            headerExtensionsButton.setText("🧩 " + enabledCount);
        }
    }

    private void showExtensionQuickMenu() {
        runtime.getWebExtensionController().list().accept(list -> runOnUiThread(() -> {
            List<WebExtension> visible = new ArrayList<>();
            if (list != null) {
                for (WebExtension extension : list) {
                    if (extension == null || EXTENSION_ID.equals(extension.id)) continue;
                    visible.add(extension);
                }
            }
            if (visible.isEmpty()) {
                new AlertDialog.Builder(this)
                        .setTitle("الإضافات")
                        .setMessage("لا توجد إضافات مثبتة. افتح صفحة الإضافات لتثبيت ملف XPI من مدير الملفات.")
                        .setNegativeButton("إلغاء", null)
                        .setPositiveButton("فتح الإضافات", (d, w) -> showExtensionsScreen())
                        .show();
                return;
            }
            if (visible.size() == 1) {
                showExtensionActionsDialog(visible.get(0));
                return;
            }
            String[] names = new String[visible.size() + 1];
            for (int i = 0; i < visible.size(); i++) {
                WebExtension extension = visible.get(i);
                boolean enabled = extension.metaData == null || extension.metaData.disabledFlags == 0;
                names[i] = (enabled ? "● " : "○ ") + extensionDisplayName(extension);
            }
            names[visible.size()] = "إدارة الإضافات";
            new AlertDialog.Builder(this)
                    .setTitle("الإضافات المثبتة")
                    .setItems(names, (dialog, which) -> {
                        if (which == visible.size()) showExtensionsScreen();
                        else showExtensionActionsDialog(visible.get(which));
                    })
                    .show();
        }), error -> runOnUiThread(() -> Toast.makeText(this,
                "تعذر قراءة الإضافات: " + safeMessage(error), Toast.LENGTH_LONG).show()));
    }

    private void showExtensionActionsDialog(WebExtension extension) {
        boolean enabled = extension.metaData == null || extension.metaData.disabledFlags == 0;
        List<String> actions = new ArrayList<>();
        boolean hasSettings = extension.metaData != null
                && !TextUtils.isEmpty(extension.metaData.optionsPageUrl);
        if (hasSettings) actions.add("فتح إعدادات الإضافة");
        actions.add(enabled ? "تعطيل الإضافة" : "تفعيل الإضافة");
        actions.add("إدارة جميع الإضافات");
        new AlertDialog.Builder(this)
                .setTitle(extensionDisplayName(extension))
                .setItems(actions.toArray(new String[0]), (dialog, which) -> {
                    int index = 0;
                    if (hasSettings) {
                        if (which == index) {
                            openExtensionSettings(extension);
                            return;
                        }
                        index++;
                    }
                    if (which == index) {
                        setExtensionEnabled(extension, !enabled);
                    } else {
                        showExtensionsScreen();
                    }
                })
                .setNegativeButton("إغلاق", null)
                .show();
    }

    private void setExtensionEnabled(WebExtension extension, boolean enable) {
        GeckoResult<WebExtension> result = enable
                ? runtime.getWebExtensionController().enable(
                        extension, WebExtensionController.EnableSource.USER)
                : runtime.getWebExtensionController().disable(
                        extension, WebExtensionController.EnableSource.USER);
        result.accept(updated -> runOnUiThread(() -> {
            refreshExtensions();
            refreshExtensionToolbar();
        }), error -> runOnUiThread(() -> Toast.makeText(this,
                "تعذر تغيير حالة الإضافة: " + safeMessage(error), Toast.LENGTH_LONG).show()));
    }

    private void openExtensionSettings(WebExtension extension) {
        String optionsUrl = extension != null && extension.metaData != null
                ? extension.metaData.optionsPageUrl : null;
        if (TextUtils.isEmpty(optionsUrl)) {
            Toast.makeText(this, "هذه الإضافة لا توفر صفحة إعدادات", Toast.LENGTH_LONG).show();
            return;
        }
        closeCapturePanel();
        showBrowserScreen();
        addressBar.setText(optionsUrl);
        session.loadUri(optionsUrl);
    }

    private View extensionCard(WebExtension extension) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(12), dp(11), dp(12), dp(11));
        card.setBackground(roundedBackground(0xFF17121B, 0xFF6F3B83, 15));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, dp(9));
        card.setLayoutParams(params);
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView name = new TextView(this);
        name.setText(extensionDisplayName(extension));
        name.setTextColor(Color.WHITE);
        name.setTextSize(15);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        TextView id = new TextView(this);
        id.setText(extension.id + (extension.isBuiltIn ? "  •  مدمجة" : "  •  مثبتة"));
        id.setTextColor(0xFFAAA3B2);
        id.setTextSize(10);
        id.setPadding(0, dp(3), 0, 0);
        text.addView(name);
        text.addView(id);
        card.addView(text);
        if (!extension.isBuiltIn) {
            boolean enabled = extension.metaData == null || extension.metaData.disabledFlags == 0;
            if (extension.metaData != null && !TextUtils.isEmpty(extension.metaData.optionsPageUrl)) {
                Button settings = smallControlButton("إعدادات", 0xFF28203A, 0xFFB755C8);
                LinearLayout.LayoutParams settingsParams = new LinearLayout.LayoutParams(dp(70), dp(36));
                settingsParams.setMarginEnd(dp(6));
                settings.setLayoutParams(settingsParams);
                settings.setOnClickListener(v -> openExtensionSettings(extension));
                card.addView(settings);
            }

            Button toggle = smallControlButton(enabled ? "تعطيل" : "تفعيل",
                    enabled ? 0xFF33203B : 0xFF173A2E,
                    enabled ? 0xFFB755C8 : 0xFF42C58A);
            LinearLayout.LayoutParams toggleParams = new LinearLayout.LayoutParams(dp(62), dp(36));
            toggleParams.setMarginEnd(dp(6));
            toggle.setLayoutParams(toggleParams);
            toggle.setOnClickListener(v -> setExtensionEnabled(extension, !enabled));
            card.addView(toggle);

            Button remove = smallControlButton("حذف", 0xFF3B1726, 0xFFD94A79);
            remove.setLayoutParams(new LinearLayout.LayoutParams(dp(58), dp(36)));
            remove.setOnClickListener(v -> confirmUninstallExtension(extension));
            card.addView(remove);
        }
        return card;
    }

    private String extensionDisplayName(WebExtension extension) {
        if (extension != null && extension.metaData != null
                && !TextUtils.isEmpty(extension.metaData.name)) {
            return extension.metaData.name;
        }
        return extension == null ? "إضافة" : extension.id;
    }

    private void installExtensionFromInput() {
        String url = extensionUrlInput.getText().toString().trim();
        if (TextUtils.isEmpty(url)) {
            Toast.makeText(this, "الصق رابط XPI موقّع من Firefox", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!url.startsWith("https://") && !url.startsWith("file:") && !url.startsWith("resource:")) {
            Toast.makeText(this, "استخدم رابط HTTPS مباشر لملف XPI", Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("تثبيت الإضافة")
                .setMessage("تثبيت الإضافة من هذا الرابط؟\n\n" + url)
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("تثبيت", (dialog, which) -> {
                    Toast.makeText(this, "جاري تثبيت الإضافة...", Toast.LENGTH_SHORT).show();
                    runtime.getWebExtensionController().install(url).accept(extension -> runOnUiThread(() -> {
                        extensionUrlInput.setText("");
                        Toast.makeText(this, "تم تثبيت " + extensionDisplayName(extension), Toast.LENGTH_LONG).show();
                        refreshExtensions();
                        refreshExtensionToolbar();
                    }), error -> runOnUiThread(() -> Toast.makeText(this,
                            "فشل التثبيت: " + safeMessage(error), Toast.LENGTH_LONG).show()));
                }).show();
    }

    private void pickExtensionFromDevice() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        intent.setType("application/x-xpinstall");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/x-xpinstall", "application/zip", "application/octet-stream"
        });
        startActivityForResult(intent, XPI_PICK_REQUEST);
    }

    private void installExtensionFromLocalUri(Uri uri) {
        if (uri == null) return;
        worker.execute(() -> {
            try {
                String displayName = queryDisplayName(uri);
                if (TextUtils.isEmpty(displayName)) displayName = "extension.xpi";
                if (!displayName.toLowerCase(Locale.US).endsWith(".xpi")) {
                    throw new IllegalArgumentException("اختر ملف إضافة بصيغة XPI");
                }

                File addonDir = new File(getFilesDir(), "imported_addons");
                if (!addonDir.exists() && !addonDir.mkdirs()) {
                    throw new IOException("تعذر إنشاء مجلد الإضافات المحلي");
                }
                File local = new File(addonDir,
                        System.currentTimeMillis() + "_" + sanitizeLocalFilename(displayName));
                try (InputStream input = getContentResolver().openInputStream(uri);
                     OutputStream output = new FileOutputStream(local)) {
                    if (input == null) throw new IOException("تعذر قراءة ملف الإضافة");
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
                }

                String fileUri = local.toURI().toString();
                runOnUiThread(() -> {
                    Toast.makeText(this, "جاري فحص وتثبيت الإضافة...", Toast.LENGTH_SHORT).show();
                    runtime.getWebExtensionController().install(fileUri).accept(extension -> runOnUiThread(() -> {
                        Toast.makeText(this, "تم تثبيت " + extensionDisplayName(extension), Toast.LENGTH_LONG).show();
                        refreshExtensions();
                        refreshExtensionToolbar();
                    }), error -> runOnUiThread(() -> Toast.makeText(this,
                            "فشل التثبيت: تأكد أن ملف XPI موقّع ومتوافق. " + safeMessage(error),
                            Toast.LENGTH_LONG).show()));
                });
            } catch (Throwable error) {
                runOnUiThread(() -> Toast.makeText(this,
                        "تعذر استيراد الإضافة: " + safeMessage(error), Toast.LENGTH_LONG).show());
            }
        });
    }

    private String queryDisplayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{MediaStore.MediaColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME);
                if (index >= 0) return cursor.getString(index);
            }
        } catch (Throwable ignored) { }
        return uri == null ? "" : uri.getLastPathSegment();
    }

    private static String sanitizeLocalFilename(String name) {
        String clean = TextUtils.isEmpty(name) ? "extension.xpi" : name;
        clean = clean.replaceAll("[\\\\/:*?\"<>|\\r\\n]", "_").trim();
        return clean.isEmpty() ? "extension.xpi" : clean;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == XPI_PICK_REQUEST && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                try {
                    getContentResolver().takePersistableUriPermission(uri,
                            data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION));
                } catch (Throwable ignored) { }
                installExtensionFromLocalUri(uri);
            }
        }
    }

    private void confirmUninstallExtension(WebExtension extension) {
        new AlertDialog.Builder(this)
                .setTitle("حذف الإضافة")
                .setMessage("حذف " + extensionDisplayName(extension) + "؟")
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("حذف", (dialog, which) -> runtime.getWebExtensionController()
                        .uninstall(extension).accept(result -> runOnUiThread(() -> {
                                    refreshExtensions();
                                    refreshExtensionToolbar();
                                }), error -> runOnUiThread(() -> Toast.makeText(this,
                                        "تعذر حذف الإضافة: " + safeMessage(error), Toast.LENGTH_LONG).show())))
                .show();
    }

    private void goForwardInBrowser() {
        BrowserTab tab = findTab(session);
        if (tab != null && tab.canGoForward) {
            showBrowserScreen();session.goForward();
        }
    }

    private void scheduleBrowserWorkspaceSave() {
        if (destroyingTabs || restoringWorkspace || addressBar == null) return;
        addressBar.removeCallbacks(browserWorkspaceSave);
        addressBar.postDelayed(browserWorkspaceSave, 1200L);
    }

    private void persistBrowserWorkspace() {
        if (destroyingTabs || restoringWorkspace || browserTabs.isEmpty()) return;
        browserWorkspace.tabs.clear();browserWorkspace.selected = 0;
        for (BrowserTab tab : browserTabs) {
            if (!BrowserWorkspace.restorable(tab.url)) continue;
            if (tab.session == session) browserWorkspace.selected = browserWorkspace.tabs.size();
            browserWorkspace.tabs.add(new BrowserWorkspace.Page(tab.url,tab.title,tab.savedState,0));
        }
        getSharedPreferences("satlink_browser",MODE_PRIVATE).edit()
                .putString("workspace",browserWorkspace.encode()).apply();
    }

    private void loadSavedBrowserPage(BrowserTab tab, BrowserWorkspace.Page page) {
        tab.url = page.url;tab.title = page.title;tab.savedState = page.state;
        tab.session.setActive(false);
        GeckoSession.SessionState state = GeckoSession.SessionState.fromString(page.state);
        if (state != null) tab.session.restoreState(state);
        else tab.session.loadUri(page.url);
    }

    private boolean restoreBrowserWorkspace() {
        if (!browserWorkspace.restoreTabs || browserWorkspace.tabs.isEmpty()) return false;
        restoringWorkspace = true;
        List<BrowserWorkspace.Page> pages = new ArrayList<>(browserWorkspace.tabs);
        int selected = browserWorkspace.selected;
        try {
            for (int i=0;i<pages.size();i++) {
                BrowserTab tab;
                if (i==0) tab = browserTabs.get(0);
                else {
                    GeckoSession created = createTabSession();
                    tab = new BrowserTab(created);browserTabs.add(tab);created.open(runtime);
                }
                loadSavedBrowserPage(tab,pages.get(i));
            }
            selectTab(browserTabs.get(Math.min(selected,browserTabs.size()-1)));
            return true;
        } finally {restoringWorkspace = false;updateTabCount();}
    }

    private void reopenClosedBrowserTab() {
        BrowserWorkspace.Page page = browserWorkspace.reopen();
        if (page == null) {Toast.makeText(this,"لا يوجد تبويب مغلق لاستعادته",Toast.LENGTH_SHORT).show();return;}
        GeckoSession created = createTabSession();
        BrowserTab tab = new BrowserTab(created);browserTabs.add(tab);created.open(runtime);
        loadSavedBrowserPage(tab,page);selectTab(tab);scheduleBrowserWorkspaceSave();
    }

    private void rememberBrowserVisit(BrowserTab tab) {
        if (!restoringWorkspace) browserWorkspace.visit(tab.url,tab.title,System.currentTimeMillis());
    }

    private void showBrowserMenu() {
        String[] items = {"تبويب جديد","إغلاق التبويب الحالي","إعادة فتح آخر تبويب مغلق",
                "سجل التصفح","إعدادات المتصفح","التبويبات","المفضلة","التنزيلات","الإضافات"};
        new AlertDialog.Builder(this).setTitle("المتصفح").setItems(items,(dialog,which)->{
            switch(which) {
                case 0:startNewTab();break;
                case 1:BrowserTab tab=findTab(session);if(tab!=null)closeTab(tab);break;
                case 2:reopenClosedBrowserTab();break;
                case 3:showBrowserHistory();break;
                case 4:showBrowserSettings();break;
                case 5:showTabsScreen();break;
                case 6:showFavoritesScreen();break;
                case 7:showDownloadsScreen();break;
                case 8:showExtensionsScreen();break;
            }
        }).show();
    }

    private void showBrowserSettings() {
        String[] items = {"محرك البحث: " + browserWorkspace.engine,
                "استعادة التبويبات عند التشغيل: " + (browserWorkspace.restoreTabs ? "مفعّلة" : "متوقفة"),
                "عدد اتصالات تنزيل الملفات: " + FileDownloadService.connections(this)};
        new AlertDialog.Builder(this).setTitle("إعدادات المتصفح").setItems(items,(d,which)->{
            if (which==0) {
                String[] engines={"Google","Bing","DuckDuckGo"};
                int selected=java.util.Arrays.asList(engines).indexOf(browserWorkspace.engine);
                new AlertDialog.Builder(this).setTitle("محرك البحث").setSingleChoiceItems(engines,selected,(choice,index)->{
                    browserWorkspace.engine=engines[index];persistBrowserWorkspace();choice.dismiss();
                }).setNegativeButton("رجوع",null).show();
            } else if (which==1) {
                browserWorkspace.restoreTabs=!browserWorkspace.restoreTabs;persistBrowserWorkspace();showBrowserSettings();
            } else {
                showConnectionSetting();
            }
        }).setNegativeButton("إغلاق",null).show();
    }

    private void showBrowserHistory() {
        LinearLayout container=new LinearLayout(this);container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(14),dp(8),dp(14),dp(8));
        EditText search=new EditText(this);search.setHint("ابحث بالعنوان أو الرابط");search.setSingleLine(true);container.addView(search);
        ScrollView scroll=new ScrollView(this);LinearLayout rows=new LinearLayout(this);rows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(rows);container.addView(scroll,new LinearLayout.LayoutParams(-1,dp(350)));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("سجل التصفح").setView(container)
                .setNegativeButton("إغلاق",null).setNeutralButton("مسح السجل",(d,w)->new AlertDialog.Builder(this)
                .setTitle("مسح سجل التصفح؟").setMessage("سيُحذف سجل الصفحات فقط.")
                .setNegativeButton("رجوع",null).setPositiveButton("مسح",(confirm,index)->{
                    browserWorkspace.history.clear();persistBrowserWorkspace();
                }).show()).create();
        search.addTextChangedListener(new TextWatcher(){
            @Override public void beforeTextChanged(CharSequence t,int start,int count,int after){}
            @Override public void onTextChanged(CharSequence t,int start,int before,int count){renderBrowserHistory(rows,t.toString(),dialog);}
            @Override public void afterTextChanged(Editable e){}
        });
        renderBrowserHistory(rows,"",dialog);dialog.show();
    }

    private void renderBrowserHistory(LinearLayout rows,String query,AlertDialog dialog) {
        rows.removeAllViews();List<BrowserWorkspace.Page> matches=browserWorkspace.matching(query);
        if(matches.isEmpty()){TextView empty=new TextView(this);empty.setText("لا توجد نتائج");rows.addView(empty);return;}
        int count=0;
        for(BrowserWorkspace.Page page:matches){
            if(count++>=100)break;
            LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);
            TextView link=new TextView(this);link.setText((page.title.isEmpty()?page.url:page.title)+"\n"+page.url+"\n"+
                    new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.getDefault()).format(new java.util.Date(page.time)));
            link.setTextSize(13);link.setPadding(0,dp(8),dp(6),dp(8));row.addView(link,new LinearLayout.LayoutParams(0,-2,1));
            link.setOnClickListener(v->{dialog.dismiss();openUrl(page.url);});
            Button remove=new Button(this);remove.setText("×");remove.setContentDescription("حذف الصفحة من السجل");
            row.addView(remove,new LinearLayout.LayoutParams(dp(44),dp(44)));
            remove.setOnClickListener(v->{browserWorkspace.history.remove(page);persistBrowserWorkspace();renderBrowserHistory(rows,query,dialog);});
            rows.addView(row);
        }
        if(matches.size()>100){TextView more=new TextView(this);more.setText("تُعرض أحدث 100 نتيجة؛ استخدم البحث للوصول للبقية");rows.addView(more);}
    }

    private static final class BrowserTab {
        final GeckoSession session;
        final String key = java.util.UUID.randomUUID().toString();
        String url = "about:blank", title = "", cookies = "", cookiePath = "", userAgent = "";
        boolean canGoBack, canGoForward, loading, awaitingSelection;
        String savedState = "";
        String openerKey = "";
        int historyIndex = -1;
        MediaSession media;
        BrowserTab(GeckoSession session) {this.session = session;}
    }

    private BrowserTab findTab(GeckoSession target) {
        for (BrowserTab tab : browserTabs) if (tab.session == target) return tab;
        return null;
    }

    private GeckoSession createTabSession() {
        GeckoSession created = new GeckoSession();
        created.getSettings().setSuspendMediaWhenInactive(true);
        created.setMediaSessionDelegate(new MediaSession.Delegate() {
            @Override public void onActivated(GeckoSession tab, MediaSession media) {
                BrowserTab owner = findTab(tab);
                if (owner == null) {media.pause();media.muteAudio(true);return;}
                owner.media = media;
                if (tab != MainActivity.this.session) {media.pause();media.muteAudio(true);return;}
                browserMediaControl = media;
                boolean allowed = activityForeground && "browser".equals(activeScreen);
                media.muteAudio(!allowed);
                if (!allowed) media.pause();
            }
            @Override public void onDeactivated(GeckoSession tab, MediaSession media) {
                BrowserTab owner = findTab(tab);
                if (owner != null && owner.media == media) owner.media = null;
                if (browserMediaControl == media) browserMediaControl = null;
            }
            @Override public void onPlay(GeckoSession tab, MediaSession media) {
                BrowserTab owner = findTab(tab);
                if (owner == null) {media.pause();media.muteAudio(true);return;}
                owner.media = media;
                if (tab != MainActivity.this.session) {media.pause();media.muteAudio(true);return;}
                browserMediaControl = media;
                if (!activityForeground || !"browser".equals(activeScreen)) {media.pause();media.muteAudio(true);}
            }
        });
        created.setContentDelegate(new BrowserContentDelegate());
        // The history list is a second, independent record of whether the tab can go back.
        created.setHistoryDelegate(new GeckoSession.HistoryDelegate() {
            @Override public void onHistoryStateChange(GeckoSession tab, GeckoSession.HistoryDelegate.HistoryList history) {
                BrowserTab owner = findTab(tab);
                if (owner == null || history == null) return;
                owner.historyIndex = history.getCurrentIndex();
                if (owner.historyIndex > 0) owner.canGoBack = true;
                if (tab == MainActivity.this.session && owner.historyIndex > 0) browserCanGoBack = true;
            }
        });
        created.setNavigationDelegate(new BrowserNavigationDelegate());
        created.setProgressDelegate(new BrowserProgressDelegate());
        if (captureExtension != null) created.getWebExtensionController().setMessageDelegate(
                captureExtension, new CaptureMessageDelegate(), NATIVE_APP);
        return created;
    }

    private void updateTabCount() {
        if (tabsButton != null) tabsButton.setText("▣ " + browserTabs.size());
    }

    private void saveTabSession() {
        BrowserTab tab = findTab(session);
        if (tab == null) return;
        tab.cookies = latestCookieContents;
        tab.cookiePath = latestCookieFilePath == null ? "" : latestCookieFilePath;
        tab.userAgent = latestUserAgent == null ? "" : latestUserAgent;
        tab.canGoBack = browserCanGoBack;
    }

    private void selectPopupIfReady(BrowserTab tab) {
        if (tab.awaitingSelection && tab.session.isOpen()) {
            tab.awaitingSelection = false;
            selectTab(tab);
        }
    }

    private void selectTab(BrowserTab tab) {
        if (destroyingTabs || !browserTabs.contains(tab) || !tab.session.isOpen()) return;
        if (session != tab.session) {
            pauseBrowserMedia();
            saveTabSession();
            session.setActive(false);
            closeCapturePanel();
            geckoView.releaseSession();
            session = tab.session;
            geckoView.setSession(session);
            browserMediaControl = tab.media;
            // Analysis results and page metadata belong only to the visible tab.
            pendingPageUrl = normalizePageUrl(tab.url);
            pendingPageTitle = tab.title;
            lastCapturedItems = null;
            lastPlayerFormats = null;
            latestActivePostUrl = "";
            latestActiveMediaKey = "";
            capturedDurationSeconds = 0;
            preferredPlayerPageUrl = "";
            latestCookieHeader = "";
            latestOrigin = "";
            latestCookieContents = tab.cookies;
            latestCookieFilePath = tab.cookiePath;
            if (!tab.userAgent.isEmpty()) latestUserAgent = tab.userAgent;
            browserCanGoBack = tab.canGoBack;
            progressBar.setVisibility(View.GONE);
        }
        forwardPageButton.setEnabled(tab.canGoForward);
        forwardPageButton.setAlpha(tab.canGoForward ? 1f : 0.4f);
        refreshPageButton.setText(tab.loading ? "×" : "↻");
        addressBar.setText("about:blank".equals(tab.url) ? "" : tab.url);
        updateFavoriteButtonState();
        if ("about:blank".equals(tab.url)) showHomeScreen();
        else showBrowserScreen();
        updateTabCount();
        scheduleBrowserWorkspaceSave();
    }

    private void closeTab(BrowserTab tab) {
        if (!browserTabs.contains(tab)) return;
        browserWorkspace.rememberClosed(tab.url, tab.title, tab.savedState);
        boolean current = session == tab.session;
        boolean listing = "tabs".equals(activeScreen);
        int previousCount = browserTabs.size();
        int index = browserTabs.indexOf(tab);
        if (current) {
            if (browserTabs.size() == 1) {
                GeckoSession replacement = createTabSession();
                replacement.open(runtime);
                browserTabs.add(new BrowserTab(replacement));
            }
            BrowserTab next = browserTabs.get(index == browserTabs.size()-1 ? index-1 : index+1);
            selectTab(next);
        }
        for (WebExtension.Port port : new ArrayList<>(capturePorts)) {
            if (port.sender.session == tab.session) {
                capturePorts.remove(port);
                if (capturePort == port) capturePort = null;
                try {port.disconnect();} catch (Throwable ignored) { }
            }
        }
        browserTabs.remove(tab);
        tab.session.setActive(false);
        tab.session.close();
        tab.media = null;
        updateTabCount();
        if (listing && previousCount > 1) showTabsScreen();
        else if ("tabs".equals(activeScreen)) renderTabs();
        scheduleBrowserWorkspaceSave();
    }

    private void renderTabs() {
        tabsList.removeAllViews();
        for (BrowserTab tab : new ArrayList<>(browserTabs)) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setPadding(dp(14), dp(10), dp(10), dp(10));
            card.setBackground(roundedBackground(0xFF151219,
                    tab.session == session ? 0xFFFF4B9F : 0xFF703456, 15));
            LinearLayout.LayoutParams margin = new LinearLayout.LayoutParams(-1, -2);
            margin.bottomMargin = dp(8);card.setLayoutParams(margin);
            TextView label = new TextView(this);
            String name = TextUtils.isEmpty(tab.title) ? tab.url : tab.title;
            if ("about:blank".equals(tab.url)) name = "تبويب جديد";
            label.setText((tab.session == session ? "● " : "") + name +
                    ("about:blank".equals(tab.url) ? "" : "\n" + tab.url));
            label.setTextColor(Color.WHITE);label.setTextSize(14);label.setMaxLines(3);
            card.addView(label, new LinearLayout.LayoutParams(0, -2, 1f));
            label.setOnClickListener(v -> selectTab(tab));
            Button close = new Button(this);close.setText("×");
            close.setContentDescription("إغلاق التبويب");close.setTextColor(Color.WHITE);
            close.setBackground(roundedBackground(0xFF301525, 0xFF703456, 12));
            card.addView(close, new LinearLayout.LayoutParams(dp(48), dp(48)));
            close.setOnClickListener(v -> closeTab(tab));
            card.setOnClickListener(v -> selectTab(tab));
            tabsList.addView(card);
        }
    }

    private void startNewTab() {
        GeckoSession created = createTabSession();
        BrowserTab tab = new BrowserTab(created);
        browserTabs.add(tab);
        created.open(runtime);
        selectTab(tab);
        created.loadUri("about:blank");
    }

    private void loadFromAddressBar() {
        String input = addressBar.getText().toString().trim();
        if (input.isEmpty()) return;

        String target;
        if (input.startsWith("http://") || input.startsWith("https://") ||
                input.startsWith("about:")) {
            target = input;
        } else if (input.contains(".") && !input.contains(" ")) {
            target = "https://" + input;
        } else {
            target = browserWorkspace.search(input);
        }

        closeCapturePanel();
        pendingPageUrl = null;
        pendingPageTitle = null;
        lastCapturedItems = null;
        showBrowserScreen();
        session.loadUri(target);
    }

    @Override
    public void onBackPressed() {
        handleBackPress();
    }

    /** One step back for the back gesture, the back key and the app's own back button. */
    private void handleBackPress() {
        if (inPageFullscreen && session != null) {
            // Back leaves the fullscreen video first; it must not leave the page.
            session.exitFullScreen();
        } else if (capturePanel.getVisibility() == View.VISIBLE) {
            closeCapturePanel();
        } else if ("home".equals(activeScreen)) {
            if (returnToBrowserFromHome && currentTabHasPage()) {
                showBrowserScreen();
                return;
            }
            long now = System.currentTimeMillis();
            if (now - lastHomeBackPress < 2000L) {
                pauseBrowserMedia();
                moveTaskToBack(true);
            } else {
                lastHomeBackPress = now;
                Toast.makeText(this, "اضغط رجوع مرة ثانية للخروج", Toast.LENGTH_SHORT).show();
            }
        } else if (!"browser".equals(activeScreen)) {
            String url = addressBar.getText().toString().trim();
            if (!TextUtils.isEmpty(url) && !"about:blank".equals(url)) showBrowserScreen();
            else showHomeScreen();
        } else {
            goBackInBrowser();
        }
    }

    @Override
    protected void onDestroy() {
        if (Build.VERSION.SDK_INT >= 33 && systemBackCallback != null) {
            try { SystemBack.unregister(this, systemBackCallback); } catch (Throwable ignored) { }
            systemBackCallback = null;
        }
        if (downloadReceiver != null) {
            try { unregisterReceiver(downloadReceiver); } catch (Exception ignored) { }
        }
        persistBrowserWorkspace();
        addressBar.removeCallbacks(browserWorkspaceSave);
        downloadsList.removeCallbacks(generalDownloadsRefresh);
        destroyingTabs = true;
        geckoView.releaseSession();
        for (BrowserTab tab : new ArrayList<>(browserTabs)) tab.session.close();
        browserTabs.clear();
        capturePorts.clear();
        capturePort = null;
        captureRequestSerial++;
        worker.shutdownNow();
        engineUpdater.shutdownNow();
        fileSaver.shutdown();
        stopActiveAnalysis();
        analysisWatchdog.shutdown();
        super.onDestroy();
    }
    /**
     * Android 16 no longer calls onBackPressed() for apps targeting API 36; the back gesture
     * and back button arrive only through OnBackInvokedCallback. Without it the system sends
     * the app to the phone's home screen. Older Android versions keep using onBackPressed().
     */
    private void registerSystemBack() {
        if (Build.VERSION.SDK_INT < 33) return;
        systemBackCallback = SystemBack.register(this, this::handleBackPress);
    }

    private static final class SystemBack {
        static Object register(Activity activity, Runnable handler) {
            android.window.OnBackInvokedCallback callback = handler::run;
            activity.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback);
            return callback;
        }

        static void unregister(Activity activity, Object callback) {
            if (callback instanceof android.window.OnBackInvokedCallback)
                activity.getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(
                        (android.window.OnBackInvokedCallback) callback);
        }
    }

    private static String cleanCookieField(String value) {
        return value == null ? "" : value.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ');
    }

    private final class BrowserContentDelegate implements GeckoSession.ContentDelegate {
        @Override public void onTitleChange(GeckoSession session, String title) {
            BrowserTab tab = findTab(session);
            if (tab == null) return;
            tab.title = title == null ? "" : title;
            if (session == MainActivity.this.session) pendingPageTitle = tab.title;
            rememberBrowserVisit(tab);scheduleBrowserWorkspaceSave();
            if ("tabs".equals(activeScreen)) renderTabs();
        }

        @Override public void onContextMenu(GeckoSession session, int screenX, int screenY,
                                            GeckoSession.ContentDelegate.ContextElement element) {
            if (element == null || session != MainActivity.this.session) return;
            final String link = element.linkUri;
            final String source = element.srcUri;
            final int type = element.type;
            runOnUiThread(() -> showPageContextMenu(link, source, type));
        }

        @Override public void onCloseRequest(GeckoSession session) {
            BrowserTab tab = findTab(session);
            if (tab != null) closeTab(tab);
        }
        @Override
        public void onFullScreen(GeckoSession session, boolean fullScreen) {
            if (session != MainActivity.this.session) return;
            runOnUiThread(() -> applyPageFullscreen(fullScreen));
        }

        @Override
        public void onExternalResponse(GeckoSession session, WebResponse response) {
            if (response == null || TextUtils.isEmpty(response.uri)) return;
            BrowserTab sourceTab = findTab(session);
            if (sourceTab == null) return;
            if (!GeneralFilePolicy.http(response.uri)) {
                // blob:/data: downloads exist only inside the page; a second network request cannot fetch them.
                saveResponseBody(response);
                return;
            }
            if (!GeneralFilePolicy.media(response.uri,
                    responseHeader(response.headers, "content-type"),
                    responseHeader(response.headers, "content-disposition"))
                    && GeneralFilePolicy.http(response.uri)) {
                String disposition = responseHeader(response.headers, "content-disposition");
                String type = responseHeader(response.headers, "content-type");
                long bytes = parseContentLength(responseHeader(response.headers, "content-length"));
                try {if (response.body != null) response.body.close();} catch (Throwable ignored) { }
                runOnUiThread(() -> generalFiles().offer(response.uri, disposition, type, bytes,
                        sourceTab.url, session == MainActivity.this.session ? latestUserAgent : sourceTab.userAgent,
                        session == MainActivity.this.session
                                ? (TextUtils.isEmpty(latestCookieFilePath) ? "" : latestCookieContents)
                                : (sourceTab.cookiePath.isEmpty() ? "" : sourceTab.cookies)));
                return;
            }
            final String url = response.uri;
            final String contentDisposition = responseHeader(response.headers, "content-disposition");
            String detectedMime = responseHeader(response.headers, "content-type");
            if (!TextUtils.isEmpty(detectedMime) && detectedMime.contains(";")) {
                detectedMime = detectedMime.substring(0, detectedMime.indexOf(';')).trim();
            }
            final String mime = TextUtils.isEmpty(detectedMime)
                    ? "application/octet-stream" : detectedMime;
            final long size = parseContentLength(responseHeader(response.headers, "content-length"));
            final String fileName = URLUtil.guessFileName(url, contentDisposition, mime);

            try {
                if (response.body != null) response.body.close();
            } catch (Throwable ignored) { }

            runOnUiThread(() -> showGeneralDownloadDialog(url, fileName, mime, size));
        }
    }

    private void saveResponseBody(WebResponse response) {
        final java.io.InputStream body = response.body;
        if (body == null) {
            runOnUiThread(() -> Toast.makeText(this, "الموقع لم يرسل محتوى الملف", Toast.LENGTH_LONG).show());
            return;
        }
        final String type = responseHeader(response.headers, "content-type");
        final String name = GeneralFilePolicy.fileName(response.uri,
                responseHeader(response.headers, "content-disposition"), type);
        final String mime = GeneralFilePolicy.mime(name, type);
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            try { body.close(); } catch (Throwable ignored) { }
            runOnUiThread(() -> {
                requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, STORAGE_REQUEST);
                Toast.makeText(this, "اسمح بحفظ الملفات ثم اضغط رابط التنزيل مرة ثانية.", Toast.LENGTH_LONG).show();
            });
            return;
        }
        runOnUiThread(() -> Toast.makeText(this, "جاري حفظ " + name, Toast.LENGTH_SHORT).show());
        final Context app = getApplicationContext();
        fileSaver.execute(() -> {
            String saved = null;
            String failure = "";
            try { saved = StreamFileSaver.save(app, body, name, mime); }
            catch (Throwable error) { failure = safeMessage(error); Log.w(TAG, "Stream download failed", error); }
            final String savedName = saved;
            final String message = failure;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                Toast.makeText(this, savedName != null ? "تم حفظ " + savedName + " في Downloads/SatLink"
                        : "تعذر حفظ الملف: " + message, Toast.LENGTH_LONG).show();
                if ("downloads".equals(activeScreen)) refreshDownloads();
            });
        });
    }

    private String currentCookieSnapshot() {
        return TextUtils.isEmpty(latestCookieFilePath) ? "" : latestCookieContents;
    }

    private void showPageContextMenu(String link, String source, int type) {
        if (isFinishing() || isDestroyed() || !"browser".equals(activeScreen)) return;
        final List<String> labels = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();
        final String referer = addressBar.getText().toString().trim();
        if (!TextUtils.isEmpty(link)) {
            boolean web = GeneralFilePolicy.http(link);
            boolean magnet = link.toLowerCase(Locale.ROOT).startsWith("magnet:?");
            if (web) {
                labels.add("فتح في تبويب جديد");
                actions.add(() -> openInNewTab(link, true));
            }
            if (web || magnet) {
                labels.add(magnet ? "فتح رابط التورنت" : "تنزيل الرابط كملف");
                actions.add(() -> {
                    if (magnet) generalFiles().navigation(link, referer, latestUserAgent, "");
                    else generalFiles().offer(link, "", "", 0L, referer, latestUserAgent, currentCookieSnapshot());
                });
            }
            labels.add("نسخ الرابط");
            actions.add(() -> copyText(link));
            labels.add("مشاركة الرابط");
            actions.add(() -> shareText(link));
        }
        if (!TextUtils.isEmpty(source) && GeneralFilePolicy.http(source)) {
            boolean image = type == GeckoSession.ContentDelegate.ContextElement.TYPE_IMAGE;
            labels.add(image ? "تنزيل الصورة" : "تنزيل الملف");
            actions.add(() -> generalFiles().offer(source, "", "", 0L, referer, latestUserAgent, currentCookieSnapshot()));
            if (image) {
                labels.add("فتح الصورة في تبويب جديد");
                actions.add(() -> openInNewTab(source, true));
            }
            labels.add(image ? "نسخ رابط الصورة" : "نسخ رابط الملف");
            actions.add(() -> copyText(source));
        }
        if (labels.isEmpty()) return;
        new AlertDialog.Builder(this)
                .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                    if (which >= 0 && which < actions.size()) actions.get(which).run();
                })
                .show();
    }

    private void copyText(String text) {
        try {
            android.content.ClipboardManager clipboard =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (clipboard == null) throw new IllegalStateException("clipboard");
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("SatLink", text));
            Toast.makeText(this, "تم النسخ", Toast.LENGTH_SHORT).show();
        } catch (Throwable error) {
            Toast.makeText(this, "تعذر النسخ", Toast.LENGTH_SHORT).show();
        }
    }

    private void shareText(String text) {
        try {
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_TEXT, text);
            startActivity(Intent.createChooser(send, "مشاركة الرابط"));
        } catch (Throwable error) {
            Toast.makeText(this, "لا يوجد تطبيق للمشاركة", Toast.LENGTH_SHORT).show();
        }
    }

    private GeneralFileDownloads generalFiles() {
        if (generalFileDownloads == null) generalFileDownloads = new GeneralFileDownloads(this);
        return generalFileDownloads;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (generalFileDownloads != null) generalFileDownloads.permissionResult(requestCode, grantResults);
    }

    private void showGeneralDownloadDialog(String url, String requestedName, String mime, long size) {
        String safeName = sanitizeGeneralFilename(requestedName);
        String sizeText = size > 0 ? formatBytes(size) : "غير معروف";
        new AlertDialog.Builder(this)
                .setTitle("تنزيل ملف")
                .setMessage("الاسم: " + safeName + "\nالنوع: " + mime
                        + "\nالحجم: " + sizeText + "\nالحفظ: Downloads/SatLink")
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("تنزيل", (dialog, which) -> enqueueGeneralDownload(
                        url, safeName, mime, size))
                .show();
    }

    private void enqueueGeneralDownload(String url, String requestedName, String mime, long size) {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, STORAGE_REQUEST);
            Toast.makeText(this, "اسمح بحفظ الملفات ثم اضغط رابط التنزيل مرة ثانية.", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            Uri uri = Uri.parse(url);
            String scheme = uri.getScheme();
            if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
                throw new IllegalArgumentException("هذا النوع من الروابط غير قابل للتنزيل المباشر");
            }
            String fileName = uniqueGeneralDownloadName(requestedName);
            DownloadManager.Request request = new DownloadManager.Request(uri);
            request.setTitle(fileName);
            request.setDescription("SatLink Browser PRO");
            request.setMimeType(mime);
            request.setAllowedOverMetered(true);
            request.setAllowedOverRoaming(true);
            request.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,
                    "SatLink/" + fileName);
            if (!TextUtils.isEmpty(latestUserAgent)) {
                request.addRequestHeader("User-Agent", latestUserAgent);
            }
            String referer = addressBar.getText().toString().trim();
            if (!TextUtils.isEmpty(referer)) request.addRequestHeader("Referer", referer);
            DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            if (manager == null) throw new IllegalStateException("مدير تنزيل أندرويد غير متاح");
            manager.enqueue(request);
            Toast.makeText(this, "بدأ تنزيل " + fileName, Toast.LENGTH_LONG).show();
        } catch (Throwable error) {
            Toast.makeText(this, "تعذر بدء تنزيل الملف: " + safeMessage(error), Toast.LENGTH_LONG).show();
        }
    }

    private static String responseHeader(Map<String, String> headers, String requested) {
        if (headers == null || TextUtils.isEmpty(requested)) return "";
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() != null && requested.equalsIgnoreCase(entry.getKey())) {
                return entry.getValue();
            }
        }
        return "";
    }

    private static long parseContentLength(String value) {
        if (TextUtils.isEmpty(value)) return 0L;
        try { return Long.parseLong(value.trim()); } catch (Exception ignored) { return 0L; }
    }

    private static String sanitizeGeneralFilename(String name) {
        String clean = TextUtils.isEmpty(name) ? "download.bin" : name;
        clean = clean.replaceAll("[\\\\/:*?\"<>|\\r\\n]", "_").trim();
        if (clean.isEmpty()) clean = "download.bin";
        if (clean.length() > 180) clean = clean.substring(0, 180);
        return clean;
    }

    private String uniqueGeneralDownloadName(String requested) {
        String clean = sanitizeGeneralFilename(requested);
        String ext = "";
        String base = clean;
        int dot = clean.lastIndexOf('.');
        if (dot > 0 && dot < clean.length() - 1) {
            base = clean.substring(0, dot);
            ext = clean.substring(dot);
        }
        String candidate = clean;
        int index = 1;
        while (generalDownloadExists(candidate) && index < 1000) {
            candidate = base + " (" + index++ + ")" + ext;
        }
        return candidate;
    }

    private boolean generalDownloadExists(String name) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            String selection = MediaStore.Downloads.DISPLAY_NAME + "=? AND "
                    + MediaStore.Downloads.RELATIVE_PATH + "=?";
            try (Cursor cursor = getContentResolver().query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    new String[]{MediaStore.Downloads._ID},
                    selection,
                    new String[]{name, Environment.DIRECTORY_DOWNLOADS + "/SatLink/"},
                    null)) {
                return cursor != null && cursor.moveToFirst();
            } catch (Throwable ignored) { return false; }
        }
        File file = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), "SatLink/" + name);
        return file.exists();
    }

    private final class CaptureMessageDelegate implements WebExtension.MessageDelegate {
        @Override
        public void onConnect(WebExtension.Port port) {
            if (port.sender.session == session) capturePort = port;
            capturePorts.add(port);
            updateBrowserPlayback();
        }

        @Override
        public GeckoResult<Object> onMessage(String nativeApp, Object message,
                                             WebExtension.MessageSender sender) {
            if (sender.session != null && sender.session != session) return null;
            if (message instanceof JSONObject) {
                JSONObject json = (JSONObject) message;
                String action = json.optString("action");
                BrowserTab active = findTab(session);
                if (sender.session == null && (active == null
                        || !active.key.equals(json.optString("tabKey", "")))) return null;
                if ("captureResult".equals(action)) {
                    onCaptureResult(json);
                } else if ("sessionUpdate".equals(action)) {
                    onSessionUpdate(json);
                }
            }
            return null;
        }
    }

    private final class BrowserNavigationDelegate implements GeckoSession.NavigationDelegate {
        @Override public void onCanGoForward(GeckoSession session, boolean canGoForward) {
            BrowserTab tab = findTab(session);
            if (tab == null) return;
            tab.canGoForward = canGoForward;
            if (session != MainActivity.this.session) return;
            forwardPageButton.setEnabled(canGoForward);
            forwardPageButton.setAlpha(canGoForward ? 1f : 0.4f);
        }
        @Override public GeckoResult<GeckoSession> onNewSession(GeckoSession opener, String uri) {
            if (destroyingTabs || findTab(opener) == null) return null;
            // Gecko requires an unopened session and loads the requested URI itself.
            GeckoSession created = createTabSession();
            BrowserTab tab = new BrowserTab(created);
            tab.url = TextUtils.isEmpty(uri) ? "about:blank" : uri;
            BrowserTab openerTab = findTab(opener);
            if (openerTab != null) tab.openerKey = openerTab.key;
            tab.awaitingSelection = true;
            browserTabs.add(tab);
            updateTabCount();
            geckoView.post(() -> selectPopupIfReady(tab));
            geckoView.postDelayed(() -> selectPopupIfReady(tab), 100L);
            return GeckoResult.fromValue(created);
        }

        @Override
        public GeckoResult<AllowOrDeny> onLoadRequest(GeckoSession session,
                                                     GeckoSession.NavigationDelegate.LoadRequest request) {
            BrowserTab sourceTab = findTab(session);
            if (sourceTab == null) return GeckoResult.fromValue(AllowOrDeny.DENY);
            if (generalFiles().navigation(request.uri, TextUtils.isEmpty(request.triggerUri)
                    ? sourceTab.url : request.triggerUri,
                    session == MainActivity.this.session ? latestUserAgent : sourceTab.userAgent,
                    session == MainActivity.this.session
                            ? (TextUtils.isEmpty(latestCookieFilePath) ? "" : latestCookieContents)
                            : (sourceTab.cookiePath.isEmpty() ? "" : sourceTab.cookies))) return GeckoResult.fromValue(AllowOrDeny.DENY);
            return null;
        }

        @Override
        public void onLocationChange(GeckoSession session, String url,
                                     List<GeckoSession.PermissionDelegate.ContentPermission> perms,
                                     Boolean hasUserGesture) {
            BrowserTab tab = findTab(session);
            if (tab == null) return;
            if (url != null) {
                if (!url.equals(tab.url)) tab.title = "";
                tab.url = url;rememberBrowserVisit(tab);scheduleBrowserWorkspaceSave();
            }
            selectPopupIfReady(tab);
            if (session != MainActivity.this.session) return;
            boolean steppedBack = backNavigationPending;
            backNavigationPending = false;
            if (steppedBack && "about:blank".equals(url) && "browser".equals(activeScreen)) {
                addressBar.setText("");
                pendingPageUrl = null;
                showHomeScreen();
                return;
            }
            if (url != null) {
                if (!normalizePageUrl(url).equals(pendingPageUrl)) {
                    if (!sharedNavigationPending) closeCapturePanel();
                    else { captureRequestSerial++; stopActiveAnalysis(); detailedFormatsShown = false; }
                    lastCapturedItems = null;
                    lastPlayerFormats = null;
                    latestActivePostUrl = "";
                    latestActiveMediaKey = "";
                    capturedDurationSeconds = 0;
                    preferredPlayerPageUrl = "";
                    latestCookieHeader = "";
                    latestCookieFilePath = "";
                }
                addressBar.setText(url);
            updateFavoriteButtonState();
                pendingPageUrl = normalizePageUrl(url);
            }
        }

        @Override
        public void onCanGoBack(GeckoSession session, boolean canGoBack) {
            BrowserTab tab = findTab(session);
            if (tab == null) return;
            tab.canGoBack = canGoBack;
            if (session != MainActivity.this.session) return;
            browserCanGoBack = canGoBack;
            backButton.setEnabled(true);
            backButton.setAlpha(1f);
        }
    }

    private final class BrowserProgressDelegate implements GeckoSession.ProgressDelegate {
        @Override public void onSessionStateChange(GeckoSession session, GeckoSession.SessionState state) {
            BrowserTab tab = findTab(session);
            if (tab == null || state == null) return;
            String encoded = state.toString();
            tab.savedState = encoded != null && encoded.length() <= 32768 ? encoded : "";
            scheduleBrowserWorkspaceSave();
        }
        @Override
        public void onPageStart(GeckoSession session, String url) {
            BrowserTab tab = findTab(session);
            if (tab != null) {tab.loading = true;selectPopupIfReady(tab);}
            if (session != MainActivity.this.session) return;
            refreshPageButton.setText("×");
            progressBar.setProgress(10);
            progressBar.setVisibility(View.VISIBLE);
        }

        @Override
        public void onProgressChange(GeckoSession session, int progress) {
            if (session != MainActivity.this.session) return;
            progressBar.setProgress(progress);
        }

        @Override
        public void onPageStop(GeckoSession session, boolean success) {
            BrowserTab tab = findTab(session);
            if (tab != null) tab.loading = false;
            if (session != MainActivity.this.session) return;
            refreshPageButton.setText("↻");
            progressBar.setProgress(100);
            progressBar.setVisibility(View.GONE);
            if (sharedNavigationPending) {
                sharedNavigationPending = false;
                if (capturePanel.getVisibility() == View.VISIBLE && !analyzing.get() && !detailedFormatsShown)
                    requestMediaScan();
            }
        }
    }

    private static final class DownloadUiBinding {
        final String taskId;
        final MediaFormatItem item;
        final Button downloadButton;
        final ProgressBar progress;
        final TextView status;
        final LinearLayout controls;
        final Button pauseResumeButton;
        final Button cancelButton;
        String state = "";

        DownloadUiBinding(String taskId, MediaFormatItem item, Button downloadButton,
                          ProgressBar progress, TextView status, LinearLayout controls,
                          Button pauseResumeButton, Button cancelButton) {
            this.taskId = taskId;
            this.item = item;
            this.downloadButton = downloadButton;
            this.progress = progress;
            this.status = status;
            this.controls = controls;
            this.pauseResumeButton = pauseResumeButton;
            this.cancelButton = cancelButton;
        }
    }

    private static final class FormatCollection {
        String title;
        String sourceUrl;
        double durationSeconds;
        final List<MediaFormatItem> videos = new ArrayList<>();
        final List<MediaFormatItem> audios = new ArrayList<>();
    }

    private static final class MediaFormatItem {
        static final int TYPE_VIDEO = 1;
        static final int TYPE_AUDIO = 2;

        int type;
        String sourceUrl;
        String directUrl;
        String directAudioUrl;
        String formatId;
        String ext;
        String videoCodec;
        String audioCodec;
        String note;
        String dynamicRange;
        String protocol;
        int width;
        int height;
        int sampleRate;
        int channels;
        double fps;
        double bitrate;
        int audioQualityKbps;
        long sizeBytes;
        boolean sizeEstimated;
        boolean capturedDirect;
        boolean useBrowserSession = true;
        boolean hasAudio;
    }

    private static long estimateSize(double durationSeconds, double bitrateKbps) {
        if (durationSeconds <= 0 || bitrateKbps <= 0) return 0L;
        return Math.max(1L, Math.round(durationSeconds * bitrateKbps * 1000d / 8d));
    }

    private static int numberToInt(Object value) {
        if (value instanceof Number) return ((Number) value).intValue();
        if (value == null || JSONObject.NULL.equals(value)) return 0;
        try {
            return (int) Math.round(Double.parseDouble(String.valueOf(value)));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static double numberToDouble(Object value) {
        if (value instanceof Number) return ((Number) value).doubleValue();
        if (value == null || JSONObject.NULL.equals(value)) return 0;
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static long firstPositiveLong(Object first, Object second) {
        long firstValue = numberToLong(first);
        return firstValue > 0 ? firstValue : numberToLong(second);
    }

    private static long numberToLong(Object value) {
        if (value instanceof Number) return ((Number) value).longValue();
        if (value == null || JSONObject.NULL.equals(value)) return 0;
        try {
            return Math.round(Double.parseDouble(String.valueOf(value)));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static String shortCodec(String codec) {
        if (codec == null || "none".equalsIgnoreCase(codec)) return "";
        String value = codec.trim();
        int dot = value.indexOf('.');
        if (dot > 0) value = value.substring(0, dot);
        if ("avc1".equalsIgnoreCase(value) || "h264".equalsIgnoreCase(value)) return "H.264";
        if ("hev1".equalsIgnoreCase(value) || "hvc1".equalsIgnoreCase(value)
                || "hevc".equalsIgnoreCase(value) || "h265".equalsIgnoreCase(value)) return "H.265";
        if ("vp09".equalsIgnoreCase(value) || "vp9".equalsIgnoreCase(value)) return "VP9";
        if ("av01".equalsIgnoreCase(value) || "av1".equalsIgnoreCase(value)) return "AV1";
        if ("mp4a".equalsIgnoreCase(value)) return "AAC";
        return value.toUpperCase(Locale.US);
    }

    private static String formatBytes(long bytes) {
        if (bytes <= 0) return "";
        final String[] units = {"B", "KB", "MB", "GB", "TB"};
        double value = bytes;
        int unit = 0;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024d;
            unit++;
        }
        return String.format(Locale.US, value >= 100 ? "%.0f %s" : value >= 10 ? "%.1f %s" : "%.2f %s", value, units[unit]);
    }

    private static String formatNumber(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.01) {
            return String.valueOf((long) Math.rint(value));
        }
        return String.format(Locale.US, "%.1f", value);
    }

    private static String extensionFromUrl(String url) {
        try {
            String path = Uri.parse(url).getPath();
            if (path == null) return "";
            int dot = path.lastIndexOf('.');
            if (dot >= 0 && dot < path.length() - 1) {
                String ext = path.substring(dot + 1).toLowerCase(Locale.US);
                if (ext.length() <= 5) return ext;
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private static String safeMessage(Throwable error) {
        if (error == null) return "خطأ غير معروف";
        String message = error.getLocalizedMessage();
        if (TextUtils.isEmpty(message)) message = error.getMessage();
        if (TextUtils.isEmpty(message)) message = error.getClass().getSimpleName();
        if (message.length() > 220) message = message.substring(0, 220);
        return message;
    }

    private static String shortProgressLine(String line) {
        if (TextUtils.isEmpty(line)) return "";
        String clean = line.replace('\n', ' ').replace('\r', ' ').trim();
        if (clean.length() > 80) clean = clean.substring(0, 80);
        return clean.isEmpty() ? "" : "\n" + clean;
    }
}
