package com.fongmi.android.tv.ui.custom;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.widget.FrameLayout;

import androidx.activity.ComponentActivity;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import androidx.palette.graphics.Palette;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.ViewWallBinding;
import com.fongmi.android.tv.event.ConfigEvent;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.FileUtil;
import com.github.catvod.crawler.SpiderDebug;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import pl.droidsonroids.gif.GifDrawable;

public class CustomWallView extends FrameLayout implements DefaultLifecycleObserver {

    private static final int DEFAULT_WALL_COLOR = Setting.getBuiltInWallColor(Setting.WALL_DREAM_PURPLE);
    private static final int GREEN_WALL_COLOR = 0xFF40C090;
    private static final int MAX_WALL_BITMAP_SIDE = 1920;
    private static final int TYPE_RES = 0;
    private static final int TYPE_GIF = 1;
    private static final int TYPE_VIDEO = 2;
    // Independent of the pool used by network sources. One worker also lets
    // consecutive Settings screens share one decoded current wallpaper.
    private static final ExecutorService WALL_WORKER = Executors.newSingleThreadExecutor();
    private static WallSnapshot lastSnapshot; // WALL_WORKER only

    private ViewWallBinding binding;
    private GifDrawable drawable;
    private PlayerView video;
    private ExoPlayer player;
    private Future<?> pendingWork;
    private long wallpaperRequest;
    private File pendingVideo;
    private boolean resumed;
    private boolean observerAdded;
    private boolean motionEnabled = true;

    public CustomWallView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public CustomWallView setMotionEnabled(boolean motionEnabled) {
        this.motionEnabled = motionEnabled;
        return this;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (isInEditMode()) return;
        if (binding == null) {
            binding = ViewWallBinding.inflate(LayoutInflater.from(getContext()), this, true);
            binding.image.setImageDrawable(new ColorDrawable(DEFAULT_WALL_COLOR));
        }
        if (!observerAdded) {
            ((ComponentActivity) getContext()).getLifecycle().addObserver(this);
            observerAdded = true;
        }
        refresh();
    }

    @Override
    protected void onDetachedFromWindow() {
        cancelWork();
        super.onDetachedFromWindow();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onConfigEvent(ConfigEvent event) {
        if (event.type() == ConfigEvent.Type.WALL) refresh();
    }

    private void refresh() {
        if (!isReady()) return;
        cancelWork();
        if (drawable != null) binding.image.setImageDrawable(new ColorDrawable(DEFAULT_WALL_COLOR));
        stop();
        int wall = Setting.getWall();
        int type = Setting.getWallType();
        if (isBuiltInColor(wall, type)) {
            int color = Setting.getBuiltInWallColor(wall);
            binding.image.setImageDrawable(new ColorDrawable(color));
            theme(color);
            return;
        }
        int resId = isBuiltInDesign(wall, type) ? getDesignResId(wall)
                : isGreen(wall, type) ? R.drawable.wallpaper_1 : 0;
        int flatColor = isGreen(wall, type) ? GREEN_WALL_COLOR
                : isBuiltInDesign(wall, type) ? Setting.getBuiltInWallColor(wall) : DEFAULT_WALL_COLOR;
        Resources resources = getResources();
        DisplayMetrics metrics = resources.getDisplayMetrics();
        int width = Math.max(1, Math.min(metrics.widthPixels, MAX_WALL_BITMAP_SIDE));
        int height = Math.max(1, Math.min(metrics.heightPixels, MAX_WALL_BITMAP_SIDE));
        int density = metrics.densityDpi;
        boolean motion = motionEnabled;
        long request = wallpaperRequest;
        pendingWork = WALL_WORKER.submit(() -> {
            WallSnapshot snapshot = snapshot(resources, resId, flatColor, width, height, density);
            File file = motion && (type == TYPE_GIF || type == TYPE_VIDEO) ? FileUtil.getWall(wall) : null;
            GifDrawable gif = file != null && type == TYPE_GIF ? gif(file) : null;
            if (gif != null) gif.pause();
            App.post(() -> {
                if (!isReady() || request != wallpaperRequest || wall != Setting.getWall()
                        || type != Setting.getWallType()) {
                    if (gif != null) gif.recycle();
                    return;
                }
                binding.image.setImageDrawable(snapshot.bitmap() == null ? new ColorDrawable(snapshot.color())
                        : new BitmapDrawable(resources, snapshot.bitmap()));
                theme(snapshot.color());
                if (gif != null) {
                    drawable = gif;
                    binding.image.setImageDrawable(gif);
                }
                pendingVideo = type == TYPE_VIDEO ? file : null;
                if (resumed) resumeMotion();
            });
        });
    }

    private void cancelWork() {
        ++wallpaperRequest;
        if (pendingWork != null) pendingWork.cancel(false);
        pendingWork = null;
    }

    private boolean isReady() {
        return binding != null && isAttachedToWindow();
    }

    private void stop() {
        pendingVideo = null;
        if (video != null) {
            video.setPlayer(null);
            video.setVisibility(GONE);
        }
        if (player != null && player.getMediaItemCount() > 0) {
            player.stop();
            player.clearMediaItems();
        }
        if (drawable != null) {
            drawable.recycle();
            drawable = null;
        }
    }

    private void theme(int color) {
        if (color == Setting.getWallColor()) return;
        Setting.putWallColor(color);
        if (Setting.getThemeColor() == 0) RefreshEvent.theme();
    }

    private int getDesignResId(int wall) {
        return switch (wall) {
            case Setting.WALL_AURORA_GLASS -> R.drawable.wallpaper_design_10_aurora_glass;
            case Setting.WALL_SUNSET_PRISM -> R.drawable.wallpaper_design_11_sunset_prism;
            case Setting.WALL_MINT_GLACIER -> R.drawable.wallpaper_design_12_mint_glacier;
            case Setting.WALL_LIQUID_CHROME -> R.drawable.wallpaper_design_13_liquid_chrome;
            case Setting.WALL_NEON_BERRY -> R.drawable.wallpaper_design_14_neon_berry;
            case Setting.WALL_CHAMPAGNE_MIST -> R.drawable.wallpaper_design_15_champagne_mist;
            case Setting.WALL_GLASS_GRADIENT -> R.drawable.wallpaper_design_16_glass_gradient;
            case Setting.WALL_DEEP_SPACE_GLASS -> R.drawable.wallpaper_design_17_deep_space_glass;
            case Setting.WALL_POLAR_LIGHT_GLASS -> R.drawable.wallpaper_design_18_polar_light_glass;
            case Setting.WALL_NEON_CYBER -> R.drawable.wallpaper_design_19_neon_cyber;
            case Setting.WALL_WARM_MOON_GLASS -> R.drawable.wallpaper_design_20_warm_moon_glass;
            case Setting.WALL_CRYSTAL_SKY -> R.drawable.wallpaper_design_21_crystal_sky;
            case Setting.WALL_DREAM_PURPLE -> R.drawable.wallpaper_design_22_dream_purple;
            case Setting.WALL_SKY_MINT -> R.drawable.wallpaper_design_23_sky_mint;
            case Setting.WALL_FOREST_MIST -> R.drawable.wallpaper_design_24_forest_mist;
            case Setting.WALL_DAYLIGHT_MINIMAL -> R.drawable.wallpaper_design_25_daylight_minimal;
            case Setting.WALL_DEEP_SEA -> R.drawable.wallpaper_design_26_deep_sea;
            case Setting.WALL_VIOLET_SMOKE -> R.drawable.wallpaper_design_27_violet_smoke;
            case Setting.WALL_ROSE_VEIL -> R.drawable.wallpaper_design_28_rose_veil;
            case Setting.WALL_EMERALD_AURORA -> R.drawable.wallpaper_design_29_emerald_aurora;
            case Setting.WALL_BLUE_SILK -> R.drawable.wallpaper_design_30_blue_silk;
            case Setting.WALL_PEACH_DAWN -> R.drawable.wallpaper_design_31_peach_dawn;
            case Setting.WALL_GRAPHITE_SMOKE -> R.drawable.wallpaper_design_32_graphite_smoke;
            case Setting.WALL_PASTEL_PRISM -> R.drawable.wallpaper_design_33_pastel_prism;
            case Setting.WALL_MIDNIGHT_MOON -> R.drawable.wallpaper_design_34_midnight_moon;
            case Setting.WALL_CYAN_CRYSTAL -> R.drawable.wallpaper_design_35_cyan_crystal;
            case Setting.WALL_LAVENDER_CRYSTAL -> R.drawable.wallpaper_design_36_lavender_crystal;
            default -> 0;
        };
    }

    private void resumeMotion() {
        if (drawable != null) drawable.start();
        if (pendingVideo != null) {
            ensurePlayer();
            ensureVideoView();
            video.setPlayer(player);
            video.setVisibility(VISIBLE);
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(pendingVideo)));
            pendingVideo = null;
            player.prepare();
        }
        if (hasVideo()) {
            video.setPlayer(player);
            player.play();
        }
    }

    private static WallSnapshot snapshot(Resources resources, int resId, int flatColor,
                                         int width, int height, int density) {
        File file = resId == 0 ? FileUtil.getWallCache() : null;
        WallKey key = new WallKey(resId, width, height, density,
                file == null ? 0 : file.lastModified(), file == null ? 0 : file.length());
        if (lastSnapshot != null && key.equals(lastSnapshot.key())) return lastSnapshot;
        Bitmap bitmap = null;
        int color = flatColor;
        try {
            if (resId != 0 || key.length() > 0) {
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                decode(resources, resId, file, bounds);
                if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inSampleSize = 1;
                    while (bounds.outWidth / options.inSampleSize > width
                            || bounds.outHeight / options.inSampleSize > height) options.inSampleSize *= 2;
                    options.inPreferredConfig = Bitmap.Config.RGB_565;
                    bitmap = decode(resources, resId, file, options);
                    if (bitmap != null && resId == 0) color = paletteColor(bitmap);
                }
            }
        } catch (OutOfMemoryError | RuntimeException e) {
            SpiderDebug.log("startup", "wall bitmap fallback error=%s", e.getClass().getSimpleName());
        }
        WallSnapshot snapshot = new WallSnapshot(key, bitmap, color);
        // A transient unreadable snapshot should be retried, not cached.
        if (bitmap != null) lastSnapshot = snapshot;
        return snapshot;
    }

    private static Bitmap decode(Resources resources, int resId, File file, BitmapFactory.Options options) {
        if (resId != 0) return BitmapFactory.decodeResource(resources, resId, options);
        return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
    }

    private static GifDrawable gif(File file) {
        try {
            return new GifDrawable(file);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private record WallKey(int resId, int width, int height, int density, long modified, long length) {}
    private record WallSnapshot(WallKey key, Bitmap bitmap, int color) {}

    private void ensurePlayer() {
        if (player != null) return;
        player = new ExoPlayer.Builder(getContext()).build();
        player.setRepeatMode(Player.REPEAT_MODE_ALL);
        player.setPlayWhenReady(true);
        player.mute();
    }

    private void ensureVideoView() {
        if (video != null) return;
        video = (PlayerView) LayoutInflater.from(getContext()).inflate(R.layout.view_wall_video, this, false);
        addView(video, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    private boolean hasVideo() {
        return player != null && video != null && video.getVisibility() == VISIBLE && player.getMediaItemCount() > 0;
    }

    private static int paletteColor(Bitmap bitmap) {
        Palette palette = Palette.from(bitmap).maximumColorCount(8).generate();
        Palette.Swatch swatch = palette.getVibrantSwatch();
        if (swatch == null) swatch = palette.getDominantSwatch();
        return swatch != null ? swatch.getRgb() : DEFAULT_WALL_COLOR;
    }

    private boolean isBuiltInColor(int wall, int type) {
        return type == TYPE_RES && Setting.isBuiltInColorWall(wall);
    }

    private boolean isBuiltInDesign(int wall, int type) {
        return type == TYPE_RES && Setting.isBuiltInDesignWall(wall);
    }

    private boolean isGreen(int wall, int type) {
        return type == TYPE_RES && wall == Setting.WALL_GREEN;
    }

    @Override
    public void onCreate(@NonNull LifecycleOwner owner) {
        EventBus.getDefault().register(this);
    }

    @Override
    public void onResume(@NonNull LifecycleOwner owner) {
        resumed = true;
        resumeMotion();
    }

    @Override
    public void onPause(@NonNull LifecycleOwner owner) {
        resumed = false;
        if (drawable != null) drawable.pause();
        if (!hasVideo()) return;
        video.setPlayer(null);
        player.pause();
    }

    @Override
    public void onDestroy(@NonNull LifecycleOwner owner) {
        cancelWork();
        EventBus.getDefault().unregister(this);
        if (drawable != null && binding != null) binding.image.setImageDrawable(null);
        stop();
        if (video != null) removeView(video);
        if (player != null) player.release();
        observerAdded = false;
        drawable = null;
        binding = null;
        player = null;
        video = null;
    }
}
