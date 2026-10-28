package io.github.xfzz7.dualpane;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;

import java.io.File;
import java.util.List;
import java.util.Locale;

@UnstableApi
public class MainActivity extends Activity {
    private static final String TAG = "DualPane";
    private static final int STORAGE_REQUEST = 21;
    private static final String HTTP_AGENT =
            "Mozilla/5.0 (Linux; Android 9; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";
    private static final long SEEK_STEP_MS = 10_000;
    private static final long OVERLAY_MS = 1_600;
    private static final float SPLIT_MIN = 0.25f;
    private static final float SPLIT_MAX = 0.75f;
    private static final float SPLIT_STEP = 0.012f;
    private static final int FOCUS_LEFT = 0;
    private static final int FOCUS_HANDLE = 1;
    private static final int FOCUS_RIGHT = 2;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Pane leftPane;
    private Pane rightPane;
    private Pane focused;
    private View handle;
    private View menu;
    private TextView menuTitle;
    private LinearLayout menuList;
    private ScrollView menuScroll;
    private float leftShare = 2f / 3f;
    private FrameLayout root;
    private LinearLayout panes;
    private boolean pipMode;
    private int pipSize = 1;
    private int pipCorner;
    private static final float[] PIP_SIZE_FRACTION = {0.26f, 0.34f, 0.44f};
    private static final float PIP_MAX_HEIGHT_FRACTION = 0.60f;
    private static final int PIP_MARGIN_DP = 24;
    private static final float PIP_DEFAULT_ASPECT = 16f / 9f;
    private static final String[] PIP_SIZE_NAMES = {"小", "中", "大"};
    private static final String[] PIP_CORNER_NAMES = {"右下", "右上", "左下", "左上"};
    private int focusSlot = FOCUS_LEFT;
    private boolean barToRight = true;
    private long strokeHideAt;
    private DlnaRenderers dlna;
    private int progressTicks;
    private boolean menuOpen;
    private boolean centerLong;
    private int menuLevel;
    /** True between onStart and onStop; DLNA commands arriving in background only prepare. */
    private boolean started;
    private boolean destroyed;
    private View guide;
    private GuideMask guideMask;
    private LinearLayout guideCard;
    private TextView guideStep;
    private TextView guideTitle;
    private TextView guideBody;
    private TextView guideHint;
    private TextView exitHint;
    private boolean exitArmed;
    private int guidePage;
    private boolean guideOpen;
    private boolean guideSavedPip;
    private boolean helpMode;

    private enum GuideSpot {
        PANES, LEFT, HANDLE, RIGHT, PIP
    }

    private static final int GUIDE_REV = 2;
    private static final String[] GUIDE_TITLE = {
            "左右两路",
            "当前这一边",
            "中间的白条",
            "确认键",
            "画中画"
    };
    private static final String[] GUIDE_BODY = {
            "左边是「双屏左」，右边是「双屏右」。\n投到哪一边，片子就出现在哪一边。",
            "焦点在左边。左右键快进、快退。\n上下键只改这一边的音量。",
            "按菜单键会停在这条白条上。\n左右键拉宽窄，再按一次到另一边。",
            "现在焦点在右边。短按确认，播放或暂停。\n长按确认打开菜单：本机视频、解码、画中画。",
            "打开之后就是现在这样：左边铺满，右边是小窗。\n菜单键切换焦点，返回先回到大屏。"
    };
    private static final GuideSpot[] GUIDE_SPOT = {
            GuideSpot.PANES,
            GuideSpot.LEFT,
            GuideSpot.HANDLE,
            GuideSpot.RIGHT,
            GuideSpot.PIP
    };

    private final Runnable progress = new Runnable() {
        @Override
        public void run() {
            if (leftPane == null || leftPane.player == null) {
                return;
            }
            refreshTransport(leftPane);
            refreshTransport(rightPane);
            progressTicks++;
            if (progressTicks % 4 == 0) {
                Log.i(TAG, "pos L=" + leftPane.player.getCurrentPosition()
                        + " R=" + rightPane.player.getCurrentPosition()
                        + " vol L=" + leftPane.volume
                        + " R=" + rightPane.volume
                        + " play L=" + leftPane.player.getPlayWhenReady()
                        + " R=" + rightPane.player.getPlayWhenReady());
            }
            handler.postDelayed(this, 500);
        }
    };

    private final Runnable hideOverlay = new Runnable() {
        @Override
        public void run() {
            long now = SystemClock.uptimeMillis();
            hideIfDue(leftPane, now);
            hideIfDue(rightPane, now);
            scheduleHide(now);
        }
    };

    private final Runnable hideStroke = new Runnable() {
        @Override
        public void run() {
            if (guideOpen || SystemClock.uptimeMillis() >= strokeHideAt) {
                hideStrokes();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        root = findViewById(R.id.root);
        panes = findViewById(R.id.panes);
        handle = findViewById(R.id.handle);
        menu = findViewById(R.id.menu);
        menuTitle = findViewById(R.id.menu_title);
        menuList = findViewById(R.id.menu_list);
        menuScroll = findViewById(R.id.menu_scroll);
        guide = findViewById(R.id.guide);
        guideMask = findViewById(R.id.guide_mask);
        guideCard = findViewById(R.id.guide_card);
        guideStep = findViewById(R.id.guide_step);
        guideTitle = findViewById(R.id.guide_title);
        guideBody = findViewById(R.id.guide_body);
        guideHint = findViewById(R.id.guide_hint);
        exitHint = findViewById(R.id.exit_hint);

        SharedPreferences prefs = getSharedPreferences("dual", MODE_PRIVATE);
        leftShare = prefs.getFloat("left_share", 2f / 3f);
        pipSize = clampIndex(prefs.getInt("pip_size", 1), 2);
        pipCorner = clampIndex(prefs.getInt("pip_corner", 0), 3);
        pipMode = prefs.getBoolean("pip_mode", false);
        leftPane = bind(true, prefs.getFloat("left_vol", 1f), prefs.getBoolean("left_hw", true));
        rightPane = bind(false, prefs.getFloat("right_vol", 0.35f), prefs.getBoolean("right_hw", true));
        boolean restoring = savedInstanceState != null;
        if (restoring) {
            leftShare = savedInstanceState.getFloat("split", leftShare);
        }
        applySplit();
        View.OnLayoutChangeListener place = (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> placeHandle();
        leftPane.root.addOnLayoutChangeListener(place);
        rightPane.root.addOnLayoutChangeListener(place);
        handle.addOnLayoutChangeListener(place);
        focusPane(leftPane);

        dlna = new DlnaRenderers(this, new DlnaRenderers.Sink() {
            @Override
            public void cast(boolean left, String uri) {
                runOnUiThread(() -> {
                    if (destroyed) {
                        return;
                    }
                    Pane pane = left ? leftPane : rightPane;
                    play(pane, uri);
                    focusPane(pane);
                    bringForward();
                });
            }

            @Override
            public void pause(boolean left) {
                runOnUiThread(() -> pausePane(left ? leftPane : rightPane));
            }

            @Override
            public void resume(boolean left) {
                runOnUiThread(() -> resumePane(left ? leftPane : rightPane));
            }

            @Override
            public void stop(boolean left) {
                runOnUiThread(() -> stopPane(left ? leftPane : rightPane));
            }

            @Override
            public void seekTo(boolean left, long positionMs) {
                runOnUiThread(() -> {
                    Pane pane = left ? leftPane : rightPane;
                    if (pane.player != null) {
                        pane.player.seekTo(positionMs);
                        refreshTransport(pane);
                    }
                });
            }

            @Override
            public void setVolume(boolean left, float volume) {
                runOnUiThread(() -> setAbsoluteVolume(left ? leftPane : rightPane, volume));
            }

            @Override
            public DlnaRenderers.Status status(boolean left) {
                Pane pane = left ? leftPane : rightPane;
                return new DlnaRenderers.Status(
                        pane.transport, pane.positionMs, pane.durationMs, pane.volume, pane.uri);
            }
        });
        dlna.start(prefs);

        forgetDemo();
        if (restoring) {
            restorePlayback(savedInstanceState);
        } else {
            Intent intent = getIntent();
            if (intent.hasExtra("left") || intent.hasExtra("right")) {
                applyIntent(intent);
            } else {
                clearLastCast();
            }
            int seen = prefs.getInt("guide_rev", prefs.getBoolean("guide_done", false) ? 1 : 0);
            if (seen < GUIDE_REV) {
                showGuide();
            }
        }
        Log.i(TAG, "start");
        boolean debugGuide = debugGuidePage(getIntent()) >= 0;
        if (!applyDebugLayout(getIntent()) && !guideOpen && !debugGuide) {
            root.post(() -> {
                if (destroyed || guideOpen) {
                    return;
                }
                if (pipMode) {
                    applyPipLayout();
                }
            });
        }
        applyDebugGuide(getIntent());
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putFloat("split", leftShare);
        outState.putBoolean("pip_mode", guideOpen ? guideSavedPip : pipMode);
        outState.putInt("pip_size", pipSize);
        outState.putInt("pip_corner", pipCorner);
        outState.putBoolean("bar_right", barToRight);
        outState.putInt("focus_slot", focusSlot);
        outState.putBoolean("guide_open", guideOpen);
        outState.putInt("guide_page", guidePage);
        outState.putBoolean("help_mode", helpMode);
        savePane(outState, "left", leftPane);
        savePane(outState, "right", rightPane);
    }

    private void savePane(Bundle outState, String key, Pane pane) {
        if (pane == null || pane.player == null) {
            return;
        }
        outState.putString(key + "_uri", pane.uri == null ? "" : pane.uri);
        outState.putLong(key + "_pos", Math.max(0, pane.player.getCurrentPosition()));
        outState.putBoolean(key + "_resume", pane.player.getPlayWhenReady());
        outState.putFloat(key + "_vol", pane.volume);
    }

    /** Rebuilds both panes after a configuration change or process death. */
    private void restorePlayback(Bundle state) {
        restorePane(state, "left", leftPane);
        restorePane(state, "right", rightPane);
        pipSize = clampIndex(state.getInt("pip_size", pipSize), 2);
        pipCorner = clampIndex(state.getInt("pip_corner", pipCorner), 3);
        pipMode = state.getBoolean("pip_mode", pipMode);
        barToRight = state.getBoolean("bar_right", barToRight);
        int slot = state.getInt("focus_slot", FOCUS_LEFT);
        if (slot == FOCUS_HANDLE && !pipMode) {
            focusBar();
        } else {
            focusPane(slot == FOCUS_RIGHT ? rightPane : leftPane);
        }
        helpMode = state.getBoolean("help_mode", false);
        int page = state.getInt("guide_page", 0);
        if (state.getBoolean("guide_open", false) && page >= 0 && page < GUIDE_TITLE.length) {
            guideSavedPip = pipMode;
            guideOpen = true;
            guidePage = page;
            guide.setVisibility(View.VISIBLE);
            bindGuide();
        }
        Log.i(TAG, "restore");
    }

    private void restorePane(Bundle state, String key, Pane pane) {
        if (pane == null || pane.player == null) {
            return;
        }
        pane.volume = clamp(state.getFloat(key + "_vol", pane.volume));
        pane.player.setVolume(pane.volume);
        String uri = state.getString(key + "_uri", "");
        if (uri == null || uri.isEmpty()) {
            showPlaceholder(pane);
            return;
        }
        play(pane, uri);
        long position = Math.max(0, state.getLong(key + "_pos", 0L));
        if (position > 0) {
            pane.player.seekTo(position);
        }
        boolean resume = state.getBoolean(key + "_resume", true);
        pane.resume = resume;
        // onCreate runs before onStart: defer actual playback until onStart.
        pane.player.setPlayWhenReady(resume && started);
    }

    private Pane bind(boolean left, float volume, boolean hardware) {
        Pane pane = new Pane();
        pane.left = left;
        pane.name = left ? "左" : "右";
        pane.volume = clamp(volume);
        pane.hardware = hardware;
        int root = left ? R.id.left_root : R.id.right_root;
        pane.root = findViewById(root);
        pane.playerView = findViewById(left ? R.id.left_player : R.id.right_player);
        pane.empty = findViewById(left ? R.id.left_empty : R.id.right_empty);
        pane.emptyTitle = findViewById(left ? R.id.left_empty_title : R.id.right_empty_title);
        pane.emptyHint = findViewById(left ? R.id.left_empty_hint : R.id.right_empty_hint);
        pane.loading = findViewById(left ? R.id.left_loading : R.id.right_loading);
        pane.loadingLabel = findViewById(left ? R.id.left_loading_label : R.id.right_loading_label);
        pane.volumeText = findViewById(left ? R.id.left_volume : R.id.right_volume);
        pane.stroke = findViewById(left ? R.id.left_stroke : R.id.right_stroke);
        showPlaceholder(pane);
        attachPlayer(pane);
        return pane;
    }

    private void attachPlayer(Pane pane) {
        if (pane.player != null) {
            pane.playerView.setPlayer(null);
            pane.player.release();
            pane.player = null;
        }
        pane.player = createPlayer(pane);
        pane.playerView.setPlayer(pane.player);
        pane.player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (playbackState == Player.STATE_READY && pane.hasFrame) {
                    pane.empty.setVisibility(View.GONE);
                }
                syncLoading(pane);
                refreshTransport(pane);
            }

            @Override
            public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
                syncLoading(pane);
                refreshTransport(pane);
            }

            @Override
            public void onRenderedFirstFrame() {
                pane.hasFrame = true;
                pane.empty.setVisibility(View.GONE);
                syncLoading(pane);
            }

            @Override
            public void onVideoSizeChanged(VideoSize videoSize) {
                int w = videoSize.width;
                int h = videoSize.height;
                if (videoSize.unappliedRotationDegrees == 90 || videoSize.unappliedRotationDegrees == 270) {
                    int t = w;
                    w = h;
                    h = t;
                }
                if (w > 0 && h > 0) {
                    float aspect = (w * videoSize.pixelWidthHeightRatio) / (float) h;
                    if (!(aspect > 0.1f) || Float.isNaN(aspect)) {
                        return;
                    }
                    float previous = pane.videoAspect;
                    pane.videoAspect = aspect;
                    if (pipMode && pane == rightPane && rightPane.root.getParent() == root) {
                        float delta = Math.abs(aspect - previous) / Math.max(previous, 0.01f);
                        if (delta > 0.02f) {
                            rightPane.root.setLayoutParams(pipWindowParams());
                        }
                    }
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                pane.hasFrame = false;
                showMessage(pane, sideName(pane) + "打不开\n" + error.getErrorCodeName());
                refreshTransport(pane);
                Log.e(TAG, pane.name + " error", error);
            }
        });
        pane.player.addAnalyticsListener(new AnalyticsListener() {
            @Override
            public void onVideoDecoderInitialized(
                    EventTime eventTime,
                    String decoderName,
                    long initializedTimestampMs,
                    long initializationDurationMs) {
                Log.i(TAG, pane.name + " decoder " + decoderName);
            }
        });
    }

    private void setHardware(Pane pane, boolean hardware) {
        if (pane.hardware != hardware) {
            Clip clip = clipOf(pane);
            pane.hardware = hardware;
            attachPlayer(pane);
            if (clip.uri.isEmpty()) {
                showPlaceholder(pane);
            } else {
                applyClip(pane, clip);
            }
            save();
            Log.i(TAG, pane.name + " mode " + (hardware ? "hardware" : "software"));
        }
        hideMenu();
    }

    private ExoPlayer createPlayer(Pane pane) {
        boolean hardware = pane.hardware;
        DefaultRenderersFactory factory = new DefaultRenderersFactory(this)
                .setEnableDecoderFallback(true)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
                .setMediaCodecSelector(hardware ? CodecOrder.hardwareFirst() : CodecOrder.softwareFirst());
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(HTTP_AGENT)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(12_000)
                .setReadTimeoutMs(20_000);
        ExoPlayer player = new ExoPlayer.Builder(this)
                .setRenderersFactory(factory)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(
                        new DefaultDataSource.Factory(this, http)))
                .setLoadControl(new DefaultLoadControl.Builder()
                        .setBufferDurationsMs(2_000, 12_000, 1_000, 2_000)
                        .build())
                .build();
        // Both panes must keep playing. The default audio-focus handler would pause one of them.
        player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(), false);
        player.setRepeatMode(Player.REPEAT_MODE_ONE);
        player.setVolume(pane.volume);
        int maxW = hardware ? 1920 : 1280;
        int maxH = hardware ? 1080 : 720;
        int maxRate = hardware ? 6_000_000 : 2_000_000;
        player.setTrackSelectionParameters(player.getTrackSelectionParameters()
                .buildUpon()
                .setMaxVideoSize(maxW, maxH)
                .setMaxVideoBitrate(maxRate)
                .build());
        return player;
    }

    private void clearLastCast() {
        leftPane.uri = "";
        rightPane.uri = "";
        leftPane.resume = false;
        rightPane.resume = false;
        showPlaceholder(leftPane);
        showPlaceholder(rightPane);
        save();
        Log.i(TAG, "clear cast");
    }

    private void showGuide() {
        if (!guideOpen) {
            guideSavedPip = pipMode;
        }
        guideOpen = true;
        guidePage = 0;
        guide.setVisibility(View.VISIBLE);
        bindGuide();
        Log.i(TAG, "guide show");
    }

    private void bindGuide() {
        guideStep.setText((guidePage + 1) + " / " + GUIDE_TITLE.length);
        guideTitle.setText(GUIDE_TITLE[guidePage]);
        guideBody.setText(GUIDE_BODY[guidePage]);
        boolean last = guidePage == GUIDE_TITLE.length - 1;
        guideHint.setText(last
                ? (helpMode ? "按确认关闭" : "按确认开始")
                : "确认继续  ·  向左返回");
        switch (GUIDE_SPOT[guidePage]) {
            case PANES:
                ensureSplitForGuide();
                holdStroke(null);
                break;
            case LEFT:
                ensureSplitForGuide();
                holdStroke(leftPane);
                break;
            case HANDLE:
                ensureSplitForGuide();
                holdStroke(null);
                showGuideHandle();
                break;
            case RIGHT:
                ensureSplitForGuide();
                holdStroke(rightPane);
                break;
            case PIP:
                ensurePipForGuide();
                holdStroke(rightPane);
                break;
            default:
                break;
        }
        Log.i(TAG, "guide page " + (guidePage + 1));
        schedulePlaceGuide();
    }

    private void ensureSplitForGuide() {
        hideHandleView();
        if (!pipMode) {
            return;
        }
        pipMode = false;
        applySplitLayout();
    }

    private void ensurePipForGuide() {
        hideHandleView();
        if (!pipMode) {
            pipMode = true;
        }
        applyPipLayout();
    }

    private void showGuideHandle() {
        if (handle == null) {
            return;
        }
        handle.setVisibility(View.VISIBLE);
        handle.setAlpha(1f);
        handle.setScaleX(1.35f);
        handle.setScaleY(1.08f);
        placeHandle();
    }

    private void holdStroke(Pane pane) {
        handler.removeCallbacks(hideStroke);
        strokeHideAt = Long.MAX_VALUE;
        if (leftPane == null || rightPane == null) {
            return;
        }
        leftPane.stroke.setVisibility(pane == leftPane ? View.VISIBLE : View.INVISIBLE);
        rightPane.stroke.setVisibility(pane == rightPane ? View.VISIBLE : View.INVISIBLE);
    }

    private int guidePlaceGen;

    private void schedulePlaceGuide() {
        int generation = ++guidePlaceGen;
        root.requestLayout();
        ViewTreeObserver observer = root.getViewTreeObserver();
        observer.addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                ViewTreeObserver current = root.getViewTreeObserver();
                if (current.isAlive()) {
                    current.removeOnGlobalLayoutListener(this);
                }
                root.post(() -> {
                    if (generation != guidePlaceGen || !guideOpen || destroyed) {
                        return;
                    }
                    placeGuide();
                });
            }
        });
    }

    private void placeGuide() {
        if (!guideOpen || destroyed || root.getWidth() == 0 || guideCard.getWidth() == 0) {
            if (guideOpen && !destroyed) {
                guideCard.post(this::placeGuide);
            }
            return;
        }
        if (GUIDE_SPOT[guidePage] == GuideSpot.HANDLE) {
            placeHandle();
        }
        Rect hole = spotRect();
        int margin = dp(28);
        int cardW = guideCard.getWidth();
        int cardH = guideCard.getHeight();
        if (cardH == 0) {
            guideCard.post(this::placeGuide);
            return;
        }
        boolean tall = hole.height() > root.getHeight() * 0.72f;
        GuideSpot spot = GUIDE_SPOT[guidePage];
        int x;
        int y;
        if (spot == GuideSpot.PIP) {
            boolean onRight = hole.centerX() > root.getWidth() / 2f;
            boolean onBottom = hole.centerY() > root.getHeight() / 2f;
            x = onRight ? margin : root.getWidth() - cardW - margin;
            y = onBottom ? margin : root.getHeight() - cardH - margin;
        } else if (!tall && hole.centerY() > root.getHeight() * 0.58f) {
            x = (root.getWidth() - cardW) / 2;
            y = margin;
        } else {
            x = (root.getWidth() - cardW) / 2;
            y = root.getHeight() - cardH - margin;
        }
        x = Math.max(margin, Math.min(x, root.getWidth() - cardW - margin));
        y = Math.max(margin, Math.min(y, root.getHeight() - cardH - margin));
        if (spot == GuideSpot.HANDLE) {
            // The bar sits on the split, not the screen center. Line the card up
            // under it, and keep a clear gap below the ring.
            int gap = dp(28);
            int barBottom = hole.bottom - dp(20);
            y = root.getHeight() - cardH - dp(10);
            int ringBottom = barBottom + dp(8);
            if (ringBottom + gap > y) {
                ringBottom = y - gap;
            }
            hole.bottom = Math.max(hole.top + dp(48), ringBottom);
            x = hole.centerX() - cardW / 2;
            x = Math.max(margin, Math.min(x, root.getWidth() - cardW - margin));
        } else if (!tall && Rect.intersects(new Rect(x, y, x + cardW, y + cardH), hole)) {
            int below = hole.bottom + dp(16);
            if (below + cardH <= root.getHeight() - dp(16)) {
                y = below;
            } else {
                hole.bottom = Math.min(hole.bottom, y - dp(14));
            }
        }
        guideCard.setX(x);
        guideCard.setY(y);
        guideMask.setHole(new RectF(hole), dp(spot == GuideSpot.HANDLE ? 28 : 18));
        guideCard.setVisibility(View.VISIBLE);
    }

    private Rect spotRect() {
        Rect rect = new Rect();
        GuideSpot spot = GUIDE_SPOT[guidePage];
        View target = panes;
        int pad = dp(10);
        if (spot == GuideSpot.LEFT) {
            target = leftPane.root;
            pad = dp(4);
        } else if (spot == GuideSpot.RIGHT || spot == GuideSpot.PIP) {
            target = rightPane.root;
            pad = dp(4);
        } else if (spot == GuideSpot.HANDLE) {
            target = handle;
            pad = dp(20);
        }
        target.getDrawingRect(rect);
        root.offsetDescendantRectToMyCoords(target, rect);
        // The divider is positioned with translation; older platforms omit that here.
        rect.offset(Math.round(target.getTranslationX()), Math.round(target.getTranslationY()));
        if (spot == GuideSpot.PANES) {
            rect.inset(dp(14), dp(14));
        } else {
            rect.inset(-pad, -pad);
        }
        int edge = dp(8);
        rect.left = Math.max(edge, rect.left);
        rect.top = Math.max(edge, rect.top);
        rect.right = Math.min(root.getWidth() - edge, rect.right);
        rect.bottom = Math.min(root.getHeight() - edge, rect.bottom);
        return rect;
    }

    private void advanceGuide() {
        if (guidePage < GUIDE_TITLE.length - 1) {
            guidePage++;
            bindGuide();
            return;
        }
        finishGuide();
    }

    private void retreatGuide() {
        if (guidePage == 0) {
            return;
        }
        guidePage--;
        bindGuide();
    }

    private void finishGuide() {
        boolean restorePip = guideSavedPip;
        guideOpen = false;
        helpMode = false;
        guide.setVisibility(View.GONE);
        hideHandleView();
        hideStrokes();
        if (pipMode != restorePip) {
            setPipMode(restorePip);
        }
        focusPane(leftPane);
        getSharedPreferences("dual", MODE_PRIVATE).edit()
                .putBoolean("guide_done", true)
                .putInt("guide_rev", GUIDE_REV)
                .apply();
        Log.i(TAG, "guide done");
    }

    private boolean onGuideKey(KeyEvent event) {
        int code = event.getKeyCode();
        int action = event.getAction();
        if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN) {
            // Let the system adjust TV volume even while the guide covers the screen.
            return false;
        }
        boolean confirm = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER;
        if (action == KeyEvent.ACTION_DOWN && confirm && event.isLongPress()) {
            centerLong = true;
            return true;
        }
        if (action != KeyEvent.ACTION_UP) {
            return true;
        }
        if (confirm) {
            if (centerLong) {
                centerLong = false;
                return true;
            }
            advanceGuide();
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_RIGHT) {
            advanceGuide();
        } else if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_BACK) {
            retreatGuide();
        }
        return true;
    }

    private void forgetDemo() {
        new File(getCacheDir(), "demo_left.mp4").delete();
        new File(getCacheDir(), "demo_right.mp4").delete();
    }

    private void applyIntent(Intent intent) {
        String left = intent.getStringExtra("left");
        String right = intent.getStringExtra("right");
        if (left != null && !left.isEmpty()) {
            play(leftPane, left);
        }
        if (right != null && !right.isEmpty()) {
            play(rightPane, right);
        }
    }

    private void play(Pane pane, String uri) {
        if (pane == null || pane.player == null || destroyed || uri == null || uri.isEmpty()) {
            return;
        }
        if (!supported(uri)) {
            Log.w(TAG, "play " + pane.name + " rejected scheme");
            if (pane.player.getMediaItemCount() == 0) {
                showMessage(pane, sideName(pane) + "打不开\n不支持的地址");
            }
            return;
        }
        pane.uri = uri;
        pane.hasFrame = false;
        resetVideoAspect(pane);
        pane.empty.setVisibility(View.GONE);
        showOpening(pane);
        pane.resume = true;
        boolean remote = uri.startsWith("http://") || uri.startsWith("https://");
        try {
            pane.player.setRepeatMode(remote ? Player.REPEAT_MODE_OFF : Player.REPEAT_MODE_ONE);
            pane.player.setMediaItem(MediaItem.fromUri(uri));
            pane.player.prepare();
            if (started) {
                pane.player.play();
            } else {
                // Foreground-only: a background cast prepares but waits for onStart.
                pane.player.setPlayWhenReady(false);
            }
        } catch (RuntimeException e) {
            // MediaItem -> MediaSource happens synchronously; unsupported content throws here.
            pane.hasFrame = false;
            pane.uri = "";
            pane.resume = false;
            pane.player.stop();
            pane.player.clearMediaItems();
            showMessage(pane, sideName(pane) + "打不开\n播放失败");
            refreshTransport(pane);
            Log.e(TAG, pane.name + " play failed", e);
            return;
        }
        refreshTransport(pane);
        save();
        int query = uri.indexOf('?');
        Log.i(TAG, "play " + pane.name + " " + (query >= 0 ? uri.substring(0, query) : uri));
    }

    private static boolean supported(String uri) {
        String lower = uri.toLowerCase(Locale.US);
        return lower.startsWith("http://") || lower.startsWith("https://")
                || lower.startsWith("file://") || lower.startsWith("content://");
    }

    private void save() {
        getSharedPreferences("dual", MODE_PRIVATE).edit()
                .putFloat("left_vol", leftPane.volume)
                .putFloat("right_vol", rightPane.volume)
                .putFloat("left_share", leftShare)
                .putBoolean("pip_mode", guideOpen ? guideSavedPip : pipMode)
                .putInt("pip_size", pipSize)
                .putInt("pip_corner", pipCorner)
                .putBoolean("left_hw", leftPane.hardware)
                .putBoolean("right_hw", rightPane.hardware)
                .apply();
    }

    private void focusPane(Pane pane) {
        focused = pane;
        focusSlot = pane == leftPane ? FOCUS_LEFT : FOCUS_RIGHT;
        hideHandleView();
        showStrokeBriefly(pane);
        if (!menuOpen) {
            pane.root.requestFocus();
        }
        Log.i(TAG, "focus " + pane.name);
    }

    private void onMenuSwitch() {
        if (pipMode) {
            focusPane(focused == rightPane ? leftPane : rightPane);
            return;
        }
        if (focusSlot == FOCUS_HANDLE) {
            focusPane(barToRight ? rightPane : leftPane);
            return;
        }
        barToRight = focused != rightPane;
        focusBar();
    }

    private void focusBar() {
        if (pipMode) {
            return;
        }
        focusSlot = FOCUS_HANDLE;
        hideStrokes();
        if (handle == null) {
            return;
        }
        handle.setVisibility(View.VISIBLE);
        handle.setAlpha(1f);
        handle.setScaleX(1.35f);
        handle.setScaleY(1.08f);
        placeHandle();
        if (!menuOpen) {
            handle.requestFocus();
        }
        Log.i(TAG, "focus handle");
    }

    private void hideHandleView() {
        if (handle == null) {
            return;
        }
        handle.setVisibility(View.INVISIBLE);
        handle.setAlpha(1f);
        handle.setScaleX(1f);
        handle.setScaleY(1f);
    }

    private void showPlaceholder(Pane pane) {
        pane.emptyTitle.setText(pane.left ? "双屏左" : "双屏右");
        pane.emptyHint.setText("从视频 App 投屏到这里\n长按确认键打开菜单");
        pane.emptyHint.setLineSpacing(dp(4), 1f);
        pane.emptyHint.setVisibility(View.VISIBLE);
        hideLoading(pane);
        pane.empty.setVisibility(View.VISIBLE);
    }

    private void showMessage(Pane pane, String text) {
        pane.emptyTitle.setText(text);
        pane.emptyHint.setVisibility(View.GONE);
        hideLoading(pane);
        pane.empty.setVisibility(View.VISIBLE);
    }

    private void showOpening(Pane pane) {
        pane.loadingLabel.setVisibility(View.VISIBLE);
        pane.loading.setVisibility(View.VISIBLE);
    }

    private void hideLoading(Pane pane) {
        pane.loading.setVisibility(View.GONE);
        pane.loadingLabel.setVisibility(View.GONE);
    }

    private void syncLoading(Pane pane) {
        if (pane.player == null) {
            hideLoading(pane);
            return;
        }
        int state = pane.player.getPlaybackState();
        boolean pending = pane.player.getPlayerError() == null
                && pane.uri != null
                && !pane.uri.isEmpty()
                && !pane.hasFrame
                && state != Player.STATE_ENDED
                && state != Player.STATE_IDLE;
        boolean stall = pane.player.getPlayerError() == null
                && pane.hasFrame
                && pane.player.getPlayWhenReady()
                && state == Player.STATE_BUFFERING;
        if (pending) {
            showOpening(pane);
        } else if (stall) {
            pane.loadingLabel.setVisibility(View.GONE);
            pane.loading.setVisibility(View.VISIBLE);
        } else {
            hideLoading(pane);
        }
    }

    private void showStrokeBriefly(Pane pane) {
        if (guideOpen) {
            return;
        }
        leftPane.stroke.setVisibility(pane == leftPane ? View.VISIBLE : View.INVISIBLE);
        rightPane.stroke.setVisibility(pane == rightPane ? View.VISIBLE : View.INVISIBLE);
        strokeHideAt = SystemClock.uptimeMillis() + OVERLAY_MS;
        handler.removeCallbacks(hideStroke);
        handler.postDelayed(hideStroke, OVERLAY_MS);
    }

    private void hideStrokes() {
        strokeHideAt = 0;
        handler.removeCallbacks(hideStroke);
        if (leftPane != null) {
            leftPane.stroke.setVisibility(View.INVISIBLE);
            rightPane.stroke.setVisibility(View.INVISIBLE);
        }
    }

    private void changeVolume(Pane pane, float delta) {
        pane.volume = clamp(pane.volume + delta);
        pane.player.setVolume(pane.volume);
        showVolume(pane);
        save();
        Log.i(TAG, "volume " + pane.name + "=" + pane.volume);
    }

    private void setAbsoluteVolume(Pane pane) {
        if (pane.player == null) {
            return;
        }
        pane.volume = clamp(pane.volume);
        pane.player.setVolume(pane.volume);
        showVolume(pane);
        save();
    }

    private void setAbsoluteVolume(Pane pane, float volume) {
        pane.volume = volume;
        setAbsoluteVolume(pane);
        Log.i(TAG, "volume " + pane.name + "=" + pane.volume);
    }

    private void pausePane(Pane pane) {
        if (pane.player == null) {
            return;
        }
        pane.player.pause();
        pane.resume = false;
        refreshTransport(pane);
    }

    private void resumePane(Pane pane) {
        if (pane.player == null) {
            return;
        }
        pane.resume = true;
        if (pane.player.getMediaItemCount() == 0 && pane.uri != null && !pane.uri.isEmpty()) {
            play(pane, pane.uri);
        } else {
            startPlayback(pane);
            refreshTransport(pane);
        }
        bringForward();
    }

    private void startPlayback(Pane pane) {
        if (pane.player == null || pane.player.getMediaItemCount() == 0) {
            return;
        }
        int state = pane.player.getPlaybackState();
        if (state == Player.STATE_ENDED) {
            pane.player.seekTo(0);
        } else if (state == Player.STATE_IDLE) {
            pane.player.prepare();
        }
        if (!started) {
            // Do not produce audio/video while the activity is in the background.
            pane.player.setPlayWhenReady(false);
            return;
        }
        pane.player.play();
    }

    private void stopPane(Pane pane) {
        if (pane.player == null) {
            return;
        }
        // UPnP Stop has to surface as STOPPED, not PAUSED_PLAYBACK.
        pane.player.pause();
        pane.player.seekTo(0);
        pane.player.stop();
        pane.resume = false;
        refreshTransport(pane);
    }

    private void refreshTransport(Pane pane) {
        if (pane.player == null) {
            return;
        }
        int state = pane.player.getPlaybackState();
        boolean play = pane.player.getPlayWhenReady();
        if (pane.uri == null || pane.uri.isEmpty()) {
            pane.transport = "NO_MEDIA_PRESENT";
        } else if (state == Player.STATE_BUFFERING) {
            pane.transport = "TRANSITIONING";
        } else if (state == Player.STATE_READY && play) {
            pane.transport = "PLAYING";
        } else if (state == Player.STATE_ENDED || state == Player.STATE_IDLE) {
            pane.transport = "STOPPED";
        } else {
            pane.transport = play ? "PLAYING" : "PAUSED_PLAYBACK";
        }
        pane.positionMs = Math.max(0, pane.player.getCurrentPosition());
        long duration = pane.player.getDuration();
        pane.durationMs = duration < 0 ? 0 : duration;
    }

    private void showVolume(Pane pane) {
        int pct = Math.round(pane.volume * 100f);
        flash(pane, pct == 0 ? "静音" : ("音量 " + pct + "%"));
    }

    private void seekBy(Pane pane, long deltaMs) {
        if (pane.player == null || pane.player.getMediaItemCount() == 0) {
            return;
        }
        long duration = pane.player.getDuration();
        long next = pane.player.getCurrentPosition() + deltaMs;
        if (next < 0) {
            next = 0;
        }
        if (duration > 0 && next > duration) {
            next = Math.max(0, duration - 500);
        }
        pane.player.seekTo(next);
        String label = clock(next);
        if (duration > 0) {
            label = label + " / " + clock(duration);
        }
        flash(pane, label);
        refreshTransport(pane);
        Log.i(TAG, "seek " + pane.name + " " + next);
    }

    private void nudgeSplit(float delta) {
        if (pipMode) {
            return;
        }
        float next = Math.max(SPLIT_MIN, Math.min(SPLIT_MAX, leftShare + delta));
        if (next == leftShare) {
            return;
        }
        leftShare = next;
        applySplit();
        save();
        Log.i(TAG, "split " + leftShare);
    }

    private void applySplit() {
        if (pipMode) {
            return;
        }
        setWeight(leftPane.root, leftShare);
        setWeight(rightPane.root, 1f - leftShare);
        if (leftPane.root != null) {
            leftPane.root.post(this::placeHandle);
        }
    }

    private void placeHandle() {
        if (pipMode) {
            return;
        }
        if (handle == null || handle.getWidth() == 0 || leftPane == null || rightPane == null) {
            return;
        }
        int gapStart = leftPane.root.getRight();
        int gapEnd = rightPane.root.getLeft();
        if (gapStart <= 0 && gapEnd <= 0) {
            return;
        }
        float center = (gapStart + gapEnd) / 2f;
        handle.setTranslationX(center - handle.getWidth() / 2f - handle.getLeft());
        View parent = (View) handle.getParent();
        float desiredY = (parent.getHeight() - handle.getHeight()) / 2f;
        handle.setTranslationY(desiredY - handle.getTop());
    }

    private void swapSides() {
        Clip left = clipOf(leftPane);
        Clip right = clipOf(rightPane);
        applyClip(leftPane, right);
        applyClip(rightPane, left);
        hideStrokes();
        save();
        Log.i(TAG, "swap");
    }

    private Clip clipOf(Pane pane) {
        Clip clip = new Clip();
        clip.uri = pane.uri == null ? "" : pane.uri;
        clip.position = Math.max(0, pane.player.getCurrentPosition());
        clip.play = pane.player.getPlayWhenReady();
        clip.repeat = pane.player.getRepeatMode();
        return clip;
    }

    private void applyClip(Pane pane, Clip clip) {
        resetVideoAspect(pane);
        pane.uri = clip.uri;
        pane.resume = clip.play;
        if (clip.uri.isEmpty()) {
            pane.player.stop();
            pane.player.clearMediaItems();
            pane.hasFrame = false;
            showPlaceholder(pane);
            refreshTransport(pane);
            return;
        }
        pane.hasFrame = false;
        pane.empty.setVisibility(View.GONE);
        showOpening(pane);
        try {
            pane.player.setRepeatMode(clip.repeat);
            pane.player.setMediaItem(MediaItem.fromUri(clip.uri), clip.position);
            pane.player.prepare();
            pane.player.setPlayWhenReady(clip.play);
        } catch (RuntimeException e) {
            pane.hasFrame = false;
            pane.uri = "";
            pane.resume = false;
            pane.player.stop();
            pane.player.clearMediaItems();
            showMessage(pane, sideName(pane) + "打不开\n播放失败");
            refreshTransport(pane);
            Log.e(TAG, pane.name + " swap failed", e);
            return;
        }
        syncLoading(pane);
        refreshTransport(pane);
        int query = clip.uri.indexOf('?');
        Log.i(TAG, "swap-to " + pane.name + " " + (query >= 0 ? clip.uri.substring(0, query) : clip.uri));
    }

    private static String sideName(Pane pane) {
        return pane.left ? "左边" : "右边";
    }

    private static void setWeight(View view, float weight) {
        if (!(view.getLayoutParams() instanceof LinearLayout.LayoutParams)) {
            return;
        }
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) view.getLayoutParams();
        params.width = 0;
        params.weight = weight;
        view.setLayoutParams(params);
    }

    private void flash(Pane pane, String text) {
        pane.volumeText.setText(text);
        pane.volumeText.setVisibility(View.VISIBLE);
        pane.hideAt = SystemClock.uptimeMillis() + OVERLAY_MS;
        scheduleHide(SystemClock.uptimeMillis());
    }

    private void scheduleHide(long now) {
        handler.removeCallbacks(hideOverlay);
        long next = Long.MAX_VALUE;
        if (leftPane != null && leftPane.hideAt > now) {
            next = leftPane.hideAt;
        }
        if (rightPane != null && rightPane.hideAt > now && rightPane.hideAt < next) {
            next = rightPane.hideAt;
        }
        if (next != Long.MAX_VALUE) {
            handler.postDelayed(hideOverlay, next - now);
        }
    }

    private void hideIfDue(Pane pane, long now) {
        if (pane != null && pane.hideAt > 0 && pane.hideAt <= now) {
            pane.volumeText.setVisibility(View.GONE);
            pane.hideAt = 0;
        }
    }

    private static String clock(long ms) {
        long total = Math.max(0, ms) / 1000;
        long hours = total / 3600;
        long minutes = (total % 3600) / 60;
        long seconds = total % 60;
        if (hours > 0) {
            return String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(Locale.US, "%d:%02d", minutes, seconds);
    }

    private static float clamp(float volume) {
        return Math.max(0f, Math.min(1f, volume));
    }

    private void onCenter(Pane pane) {
        if (pane.player.getMediaItemCount() == 0) {
            showRoot();
            return;
        }
        if (pane.player.getPlaybackState() == Player.STATE_ENDED) {
            startPlayback(pane);
            pane.resume = true;
            return;
        }
        if (pane.player.getPlayWhenReady()) {
            pane.player.pause();
            pane.resume = false;
        } else {
            startPlayback(pane);
            pane.resume = true;
        }
    }

    private void bringForward() {
        if (hasWindowFocus()) {
            return;
        }
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
    }

    private void showRoot() {
        menuLevel = 1;
        Pane pane = focused == null ? leftPane : focused;
        menuTitle.setText(sideName(pane));
        menuList.removeAllViews();
        addItem("解码 · " + (pane.hardware ? "硬解" : "软解"), this::showDecoder);
        addItem("本机视频", this::showFiles);
        addItem("使用说明", this::showHelp);
        if (pipMode) {
            addItem("画中画设置", this::showPipSettings);
            addItem("分屏", () -> {
                hideMenu();
                setPipMode(false);
            });
            addItem("关闭", this::hideMenu);
            sizeMenu(6);
        } else {
            addItem("画中画", () -> {
                hideMenu();
                setPipMode(true);
            });
            addItem("关闭", this::hideMenu);
            sizeMenu(5);
        }
        openMenu();
    }

    private void showPipSettings() {
        menuLevel = 2;
        menuTitle.setText("画中画设置");
        menuList.removeAllViews();
        addItem("小窗大小 · " + PIP_SIZE_NAMES[clampIndex(pipSize, 2)], this::showPipSize);
        addItem("小窗位置 · " + PIP_CORNER_NAMES[clampIndex(pipCorner, 3)], this::showPipCorner);
        addItem("左右对换", () -> {
            swapSides();
            focusPane(focused == leftPane ? rightPane : leftPane);
            hideMenu();
        });
        addItem("返回", this::showRoot);
        sizeMenu(4);
        openMenu();
    }

    private void showPipSize() {
        menuLevel = 3;
        menuTitle.setText("小窗大小");
        menuList.removeAllViews();
        for (int i = 0; i < PIP_SIZE_NAMES.length; i++) {
            int size = i;
            String label = PIP_SIZE_NAMES[i] + (size == pipSize ? " · 当前" : "");
            addItem(label, () -> {
                pipSize = size;
                applyPipLayout();
                save();
                hideMenu();
            });
        }
        addItem("返回", this::showPipSettings);
        sizeMenu(4);
        openMenu();
    }

    private void showPipCorner() {
        menuLevel = 3;
        menuTitle.setText("小窗位置");
        menuList.removeAllViews();
        for (int i = 0; i < PIP_CORNER_NAMES.length; i++) {
            int corner = i;
            String label = PIP_CORNER_NAMES[i] + (corner == pipCorner ? " · 当前" : "");
            addItem(label, () -> {
                pipCorner = corner;
                applyPipLayout();
                save();
                hideMenu();
            });
        }
        addItem("返回", this::showPipSettings);
        sizeMenu(5);
        openMenu();
    }

    private void showDecoder() {
        menuLevel = 2;
        Pane pane = focused == null ? leftPane : focused;
        menuTitle.setText(sideName(pane) + "的解码");
        menuList.removeAllViews();
        addItem(pane.hardware ? "硬解 · 当前" : "硬解", () -> setHardware(pane, true));
        addItem(pane.hardware ? "软解" : "软解 · 当前", () -> setHardware(pane, false));
        addItem("返回", this::showRoot);
        sizeMenu(3);
        openMenu();
    }

    private void showHelp() {
        helpMode = true;
        hideMenu();
        showGuide();
        Log.i(TAG, "help");
    }

    private void showFiles() {
        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, STORAGE_REQUEST);
            return;
        }
        menuLevel = 2;
        String side = sideName(focused);
        menuTitle.setText("正在查找" + side + "的视频…");
        menuList.removeAllViews();
        openMenu();
        new Thread(() -> {
            List<VideoLibrary.Entry> entries = VideoLibrary.sorted(VideoLibrary.list(this));
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || destroyed) {
                    return;
                }
                menuTitle.setText("给" + sideName(focused) + "选片");
                menuList.removeAllViews();
                if (entries.isEmpty()) {
                    addItem("没有找到视频", this::showRoot);
                } else {
                    for (VideoLibrary.Entry entry : entries) {
                        addItem(entry.title, () -> {
                            Pane target = focused;
                            hideMenu();
                            play(target, entry.uri);
                        });
                    }
                }
                addItem("返回", this::showRoot);
                sizeMenu(menuList.getChildCount());
                focusFirst();
            });
        }, "scan").start();
    }

    private void addItem(String label, Runnable action) {
        TextView item = new TextView(this);
        item.setText(label);
        item.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        item.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        int pad = dp(16);
        item.setPadding(pad, pad, pad, pad);
        item.setFocusable(true);
        item.setClickable(true);
        item.setBackgroundResource(R.drawable.menu_item);
        item.setTextColor(getResources().getColorStateList(R.color.menu_text, null));
        item.setOnClickListener(v -> action.run());
        item.setOnFocusChangeListener((v, has) -> {
            if (has) {
                menuScroll.smoothScrollTo(0, v.getTop());
            }
        });
        menuList.addView(item);
    }

    private void openMenu() {
        menuOpen = true;
        menu.setVisibility(View.VISIBLE);
        leftPane.root.setFocusable(false);
        rightPane.root.setFocusable(false);
        handle.setFocusable(false);
        focusFirst();
    }

    private void focusFirst() {
        menuList.post(() -> {
            if (menuList.getChildCount() > 0) {
                menuList.getChildAt(0).requestFocus();
            }
        });
    }

    private void hideMenu() {
        menuOpen = false;
        menuLevel = 0;
        menu.setVisibility(View.GONE);
        leftPane.root.setFocusable(true);
        rightPane.root.setFocusable(true);
        handle.setFocusable(true);
        focusPane(focused == null ? leftPane : focused);
    }

    private void backMenu() {
        if (menuLevel == 3) {
            showPipSettings();
        } else if (menuLevel > 1) {
            showRoot();
        } else {
            hideMenu();
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void sizeMenu(int items) {
        int height = Math.min(dp(460), dp(58) * Math.max(items, 1));
        menuScroll.getLayoutParams().height = height;
        menuScroll.requestLayout();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == STORAGE_REQUEST
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            showFiles();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent.hasExtra("left") || intent.hasExtra("right")) {
            applyIntent(intent);
        }
        applyDebugLayout(intent);
        applyDebugGuide(intent);
    }

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
        if (leftPane.resume) {
            startPlayback(leftPane);
        }
        if (rightPane.resume) {
            startPlayback(rightPane);
        }
        handler.postDelayed(progress, 2000);
    }

    @Override
    protected void onStop() {
        leftPane.resume = leftPane.player.getPlayWhenReady();
        rightPane.resume = rightPane.player.getPlayWhenReady();
        leftPane.player.pause();
        rightPane.player.pause();
        started = false;
        refreshTransport(leftPane);
        refreshTransport(rightPane);
        handler.removeCallbacks(progress);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        handler.removeCallbacksAndMessages(null);
        if (exitHint != null) {
            exitHint.animate().cancel();
        }
        if (dlna != null) {
            dlna.stop();
        }
        release(leftPane);
        release(rightPane);
        super.onDestroy();
    }

    private void release(Pane pane) {
        if (pane == null || pane.player == null) {
            return;
        }
        pane.playerView.setPlayer(null);
        pane.player.release();
        pane.player = null;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (guideOpen) {
            return onGuideKey(event);
        }
        int code = event.getKeyCode();
        int action = event.getAction();
        if (code == KeyEvent.KEYCODE_BACK) {
            if (action == KeyEvent.ACTION_UP) {
                if (menuOpen) {
                    backMenu();
                } else if (pipMode && focused == rightPane) {
                    focusPane(leftPane);
                } else if (focusSlot == FOCUS_HANDLE) {
                    focusPane(barToRight ? leftPane : rightPane);
                } else {
                    confirmExit();
                }
            }
            return true;
        }
        if (menuOpen) {
            if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                return true;
            }
            // Volume keys fall through so the TV's own volume is always used.
            return super.dispatchKeyEvent(event);
        }
        if (action == KeyEvent.ACTION_DOWN) {
            if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                onHorizontal(code, event.getRepeatCount());
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_UP) {
                changeVolume(focused, 0.05f);
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                changeVolume(focused, -0.05f);
                return true;
            }
            if (code == KeyEvent.KEYCODE_MENU && event.getRepeatCount() == 0) {
                onMenuSwitch();
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_CENTER) {
                if (event.isLongPress() && !centerLong) {
                    centerLong = true;
                    if (focusSlot == FOCUS_HANDLE) {
                        swapSides();
                    } else {
                        showRoot();
                    }
                }
                return true;
            }
        }
        if (action == KeyEvent.ACTION_UP && code == KeyEvent.KEYCODE_DPAD_CENTER) {
            if (centerLong) {
                centerLong = false;
                return true;
            }
            if (focusSlot == FOCUS_HANDLE) {
                return true;
            }
            onCenter(focused);
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_LEFT
                || code == KeyEvent.KEYCODE_DPAD_RIGHT
                || code == KeyEvent.KEYCODE_DPAD_UP
                || code == KeyEvent.KEYCODE_DPAD_DOWN
                || code == KeyEvent.KEYCODE_MENU) {
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private void confirmExit() {
        if (exitArmed) {
            exitHint.animate().cancel();
            exitArmed = false;
            finish();
            return;
        }
        exitArmed = true;
        exitHint.animate().cancel();
        exitHint.setAlpha(1f);
        exitHint.setVisibility(View.VISIBLE);
        exitHint.animate()
                .alpha(0f)
                .setStartDelay(1000)
                .setDuration(1800)
                .setInterpolator(new LinearInterpolator())
                .withEndAction(() -> {
                    if (!exitArmed) {
                        return;
                    }
                    exitArmed = false;
                    exitHint.setVisibility(View.GONE);
                    exitHint.setAlpha(1f);
                })
                .start();
    }

    private void onHorizontal(int code, int repeat) {
        int dir = code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1;
        if (focusSlot == FOCUS_HANDLE) {
            nudgeSplit(dir * SPLIT_STEP);
            return;
        }
        if (repeat % 5 == 0) {
            Pane pane = focused == null ? leftPane : focused;
            Log.i(TAG, "seek key " + pane.name);
            seekBy(pane, dir > 0 ? SEEK_STEP_MS : -SEEK_STEP_MS);
        }
    }

    private void setPipMode(boolean on) {
        if (destroyed || pipMode == on) {
            return;
        }
        pipMode = on;
        if (on) {
            applyPipLayout();
        } else {
            applySplitLayout();
        }
        hideHandleView();
        focusPane(focused == null ? leftPane : focused);
        save();
        Log.i(TAG, "pip " + (on ? "on" : "off") + " size " + pipSize + " corner " + pipCorner);
    }

    private void applyPipLayout() {
        if (destroyed || leftPane == null || rightPane == null || root == null) {
            return;
        }
        ViewGroup.LayoutParams leftParams = leftPane.root.getLayoutParams();
        if (leftParams instanceof LinearLayout.LayoutParams) {
            LinearLayout.LayoutParams left = (LinearLayout.LayoutParams) leftParams;
            left.width = 0;
            left.height = ViewGroup.LayoutParams.MATCH_PARENT;
            left.weight = 1f;
            leftPane.root.setLayoutParams(left);
        }
        FrameLayout.LayoutParams window = pipWindowParams();
        ViewParent parent = rightPane.root.getParent();
        if (parent == root && root.indexOfChild(rightPane.root) == 1) {
            rightPane.root.setLayoutParams(window);
        } else {
            if (parent instanceof ViewGroup) {
                ((ViewGroup) parent).removeView(rightPane.root);
            }
            root.addView(rightPane.root, Math.min(1, root.getChildCount()), window);
        }
        rightPane.root.setForeground(getDrawable(R.drawable.pip_border));
        rightPane.root.setBackgroundColor(0xFF000000);
        hideHandleView();
        if (!menuOpen) {
            focusPane(focused == rightPane ? rightPane : leftPane);
        }
        if (root.getWidth() <= 0 || root.getHeight() <= 0) {
            root.post(() -> {
                if (destroyed || !pipMode || rightPane.root.getParent() != root) {
                    return;
                }
                rightPane.root.setLayoutParams(pipWindowParams());
            });
        }
        Log.i(TAG, "pip window " + window.width + "x" + window.height
                + " aspect " + rightPane.videoAspect);
    }

    private void applySplitLayout() {
        if (rightPane == null || panes == null) {
            return;
        }
        ViewParent parent = rightPane.root.getParent();
        if (parent == root) {
            root.removeView(rightPane.root);
        }
        if (rightPane.root.getParent() != panes) {
            if (panes.indexOfChild(leftPane.root) != 0 && leftPane.root.getParent() == panes) {
                panes.removeView(leftPane.root);
                panes.addView(leftPane.root, 0);
            }
            int index = Math.min(1, panes.getChildCount());
            panes.addView(rightPane.root, index, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 1f - leftShare));
        }
        rightPane.root.setForeground(null);
        rightPane.root.setBackground(null);
        applySplit();
        hideHandleView();
    }

    private FrameLayout.LayoutParams pipWindowParams() {
        int screenW = root.getWidth() > 0 ? root.getWidth()
                : getResources().getDisplayMetrics().widthPixels;
        int screenH = root.getHeight() > 0 ? root.getHeight()
                : getResources().getDisplayMetrics().heightPixels;
        float aspect = rightPane.videoAspect;
        if (!(aspect > 0.1f) || Float.isNaN(aspect)) {
            aspect = PIP_DEFAULT_ASPECT;
        }
        float frac = PIP_SIZE_FRACTION[Math.max(0, Math.min(2, pipSize))];
        int w;
        int h;
        if (aspect >= 1f) {
            w = Math.round(screenW * frac);
            h = Math.round(w / aspect);
        } else {
            h = Math.round(screenH * frac);
            w = Math.round(h * aspect);
        }
        int maxH = Math.round(screenH * PIP_MAX_HEIGHT_FRACTION);
        if (h > maxH) {
            h = maxH;
            w = Math.round(h * aspect);
        }
        int margin = dp(PIP_MARGIN_DP);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h);
        switch (pipCorner) {
            case 0:
                lp.gravity = Gravity.END | Gravity.BOTTOM;
                lp.rightMargin = margin;
                lp.bottomMargin = margin;
                break;
            case 1:
                lp.gravity = Gravity.END | Gravity.TOP;
                lp.rightMargin = margin;
                lp.topMargin = margin;
                break;
            case 2:
                lp.gravity = Gravity.START | Gravity.BOTTOM;
                lp.leftMargin = margin;
                lp.bottomMargin = margin;
                break;
            case 3:
                lp.gravity = Gravity.START | Gravity.TOP;
                lp.leftMargin = margin;
                lp.topMargin = margin;
                break;
            default:
                lp.gravity = Gravity.END | Gravity.BOTTOM;
                lp.rightMargin = margin;
                lp.bottomMargin = margin;
                break;
        }
        return lp;
    }

    private void resetVideoAspect(Pane pane) {
        pane.videoAspect = PIP_DEFAULT_ASPECT;
        if (pipMode && pane == rightPane && rightPane.root.getParent() == root) {
            rightPane.root.setLayoutParams(pipWindowParams());
        }
    }

    /** @return true when a debug layout extra will be applied on the next frame. */
    private boolean applyDebugLayout(Intent intent) {
        if (intent == null || root == null) {
            return false;
        }
        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0) {
            return false;
        }
        String layout = intent.getStringExtra("layout");
        if (layout == null) {
            return false;
        }
        int size = debugPipSize(intent.getStringExtra("pip_size"));
        int corner = debugPipCorner(intent.getStringExtra("pip_corner"));
        if (size >= 0) {
            pipSize = size;
        }
        if (corner >= 0) {
            pipCorner = corner;
        }
        root.post(() -> {
            if (destroyed || guideOpen) {
                return;
            }
            if ("pip".equals(layout)) {
                if (!pipMode) {
                    setPipMode(true);
                } else {
                    applyPipLayout();
                    save();
                }
            } else if ("split".equals(layout)) {
                if (pipMode) {
                    setPipMode(false);
                } else {
                    applySplit();
                    save();
                }
            }
            Log.i(TAG, "debug layout " + layout + " size " + pipSize + " corner " + pipCorner);
        });
        return true;
    }

    private void applyDebugGuide(Intent intent) {
        int page = debugGuidePage(intent);
        if (page < 0 || root == null) {
            return;
        }
        root.post(() -> {
            if (destroyed) {
                return;
            }
            if (!guideOpen) {
                showGuide();
            }
            guidePage = Math.max(0, Math.min(GUIDE_TITLE.length - 1, page));
            bindGuide();
        });
    }

    private int debugGuidePage(Intent intent) {
        if (intent == null) {
            return -1;
        }
        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0) {
            return -1;
        }
        if (!intent.hasExtra("guide_page")) {
            return -1;
        }
        return intent.getIntExtra("guide_page", 0);
    }

    private static int debugPipSize(String value) {
        if ("small".equals(value)) {
            return 0;
        }
        if ("medium".equals(value)) {
            return 1;
        }
        if ("large".equals(value)) {
            return 2;
        }
        return -1;
    }

    private static int debugPipCorner(String value) {
        if ("br".equals(value)) {
            return 0;
        }
        if ("tr".equals(value)) {
            return 1;
        }
        if ("bl".equals(value)) {
            return 2;
        }
        if ("tl".equals(value)) {
            return 3;
        }
        return -1;
    }

    private static int clampIndex(int value, int max) {
        return Math.max(0, Math.min(max, value));
    }

    private static final class Pane {
        boolean left;
        String name;
        FrameLayout root;
        PlayerView playerView;
        ExoPlayer player;
        View empty;
        TextView emptyTitle;
        TextView emptyHint;
        View loading;
        TextView loadingLabel;
        TextView volumeText;
        boolean hardware;
        View stroke;
        String uri;
        float volume;
        boolean resume;
        boolean hasFrame;
        float videoAspect = PIP_DEFAULT_ASPECT;
        long hideAt;
        volatile String transport = "NO_MEDIA_PRESENT";
        volatile long positionMs;
        volatile long durationMs;
    }

    private static final class Clip {
        String uri;
        long position;
        boolean play;
        int repeat;
    }
}
