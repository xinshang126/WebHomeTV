package com.fongmi.android.tv.ui.dialog;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.util.Log;
import android.view.KeyEvent;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.fongmi.android.tv.utils.VodFileSelection;
import com.github.catvod.crawler.SpiderDebug;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** A TV file dialog that keeps Settings visible while storage is enumerated. */
public final class VodConfigPickerDialog {

    private static final int ROOT = 1;
    private static final int DIRECTORY = 2;
    private static final int FILE = 3;
    private static final int ROOTS = 4;
    private static final int INFO = 5;
    private static final int MAX_ROWS = 2000;

    private final Activity activity;
    private final Consumer<File> selected;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = new ThreadPoolExecutor(0, 4, 30, TimeUnit.SECONDS,
            new SynchronousQueue<>(), new ThreadPoolExecutor.AbortPolicy());
    private final ArrayAdapter<String> adapter;
    private final ListView list;
    private final TextView status;
    private final AlertDialog dialog;
    private final List<Row> rows = new ArrayList<>();
    private Root root;
    private File current;
    private int navigation;

    public static void show(Activity activity, Consumer<File> selected) {
        new VodConfigPickerDialog(activity, selected).show();
    }

    private VodConfigPickerDialog(Activity activity, Consumer<File> selected) {
        this.activity = activity;
        this.selected = selected;
        this.adapter = new ArrayAdapter<>(activity, android.R.layout.simple_list_item_1);
        this.adapter.setNotifyOnChange(false);
        this.list = new ListView(activity);
        this.list.setAdapter(adapter);
        this.list.setFocusable(true);
        this.status = new TextView(activity);
        int inset = (int) (16 * activity.getResources().getDisplayMetrics().density);
        status.setPadding(inset, inset, inset, inset / 2);
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.addView(status);
        int listHeight = Math.max(inset * 10,
                (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.58f));
        content.addView(list, new LinearLayout.LayoutParams(-1, listHeight));
        this.dialog = new AlertDialog.Builder(activity).setTitle("选择点播 JSON")
                .setView(content).setNegativeButton("取消", (d, which) -> {}).create();
        list.setOnItemClickListener((parent, view, position, id) -> open(position));
        dialog.setOnDismissListener(d -> {
            navigation++;
            worker.shutdownNow();
        });
        dialog.setOnKeyListener((d, key, event) -> {
            if (key != KeyEvent.KEYCODE_BACK || current == null) return false;
            if (event.getAction() == KeyEvent.ACTION_UP) {
                if (root == null || same(current, root.directory)) showRoots();
                else showDirectory(root, current.getParentFile());
            }
            return true;
        });
    }

    private void show() {
        dialog.show();
        showRoots();
    }

    private void open(int position) {
        if (position < 0 || position >= rows.size()) return;
        Row row = rows.get(position);
        switch (row.kind) {
            case ROOTS -> showRoots();
            case ROOT -> showDirectory(row.root, row.file);
            case DIRECTORY -> showDirectory(root, row.file);
            case FILE -> {
                if (!VodFileSelection.accepts(row.file)) {
                    status.setText("文件不可读取，请选择其他 JSON");
                    return;
                }
                try {
                    selected.accept(row.file);
                    dialog.dismiss();
                } catch (RuntimeException failed) {
                    status.setText("文件选择失败，请重试");
                    trace("selection failed type=" + failed.getClass().getSimpleName());
                }
            }
            default -> { }
        }
    }

    private void showRoots() {
        root = null;
        current = null;
        int request = ++navigation;
        render(List.of(new Row(INFO, "正在读取存储位置…", null, null)), "存储位置");
        run(request, () -> {
            List<Row> found = new ArrayList<>();
            for (Root volume : roots()) found.add(new Row(ROOT,
                    "💾 " + volume.title + "\n" + volume.directory.getAbsolutePath(), volume.directory, volume));
            if (found.isEmpty()) found.add(new Row(INFO, "未发现可读取的存储位置", null, null));
            main.post(() -> {
                if (active(request)) render(found, "存储位置");
            });
        });
    }

    private void showDirectory(Root volume, File directory) {
        if (volume == null || directory == null || !inside(volume.directory, directory)) {
            showRoots();
            return;
        }
        root = volume;
        current = directory;
        int request = ++navigation;
        long started = SystemClock.elapsedRealtime();
        List<Row> navigationRows = new ArrayList<>();
        navigationRows.add(new Row(ROOTS, "↩ 返回存储位置", null, null));
        if (!same(directory, volume.directory)) navigationRows.add(new Row(DIRECTORY,
                "⬆ 返回上一级", directory.getParentFile(), null));
        List<Row> loading = new ArrayList<>(navigationRows);
        loading.add(new Row(INFO, "正在读取文件夹…", null, null));
        render(loading, "正在读取文件夹；可随时返回");
        String id = Integer.toHexString(directory.getAbsolutePath().hashCode());
        trace("folder open id=" + id + " nav=" + request);
        main.postDelayed(() -> {
            if (active(request)) status.setText("读取较慢，可选择返回上一级或存储位置");
        }, 5000);
        run(request, () -> {
            List<Row> files = listFiles(directory);
            main.post(() -> {
                if (!active(request)) return;
                List<Row> visible = new ArrayList<>(navigationRows);
                if (files == null) visible.add(new Row(INFO, "文件夹不可读取", null, null));
                else if (files.isEmpty()) visible.add(new Row(INFO, "没有可读取的 JSON 或子文件夹", null, null));
                else visible.addAll(files);
                render(visible, files == null ? "文件夹不可读取" : "选择 JSON 文件");
                trace("folder result id=" + id + " nav=" + request + " cost="
                        + (SystemClock.elapsedRealtime() - started) + "ms count="
                        + (files == null ? -1 : files.size()));
            });
        });
    }

    private void run(int request, Runnable work) {
        try {
            worker.execute(work);
        } catch (RuntimeException busy) {
            if (active(request)) status.setText("存储读取繁忙，请返回后重试");
        }
    }

    private boolean active(int request) {
        return request == navigation && dialog.isShowing() && !activity.isFinishing()
                && !activity.isDestroyed();
    }

    private void render(List<Row> values, String message) {
        rows.clear();
        rows.addAll(values);
        adapter.clear();
        for (Row row : values) adapter.add(row.label);
        adapter.notifyDataSetChanged();
        status.setText(message);
        list.requestFocus();
    }

    private List<Row> listFiles(File directory) {
        try {
            long started = SystemClock.elapsedRealtime();
            String id = Integer.toHexString(directory.getAbsolutePath().hashCode());
            List<Row> result = new ArrayList<>();
            trace("folder scan start id=" + id);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                DirectoryReader.read(directory, result);
            } else {
                String[] names = directory.list();
                if (names == null) return null;
                for (String name : names) {
                    addEntry(result, new File(directory, name));
                    if (result.size() >= MAX_ROWS) break;
                }
            }
            result.sort(Comparator.comparing((Row row) -> row.kind != DIRECTORY)
                    .thenComparing(row -> row.label, String.CASE_INSENSITIVE_ORDER));
            trace("folder filtered id=" + id + " count=" + result.size()
                    + " cost=" + (SystemClock.elapsedRealtime() - started) + "ms");
            return result;
        } catch (IOException | RuntimeException unavailable) {
            trace("folder error type=" + unavailable.getClass().getSimpleName());
            return null;
        }
    }

    private static void addEntry(List<Row> result, File file) {
        String name = file.getName();
        if (file.isDirectory()) {
            if (file.canRead()) result.add(new Row(DIRECTORY, "📁 " + name, file, null));
        } else if (name.toLowerCase(java.util.Locale.ROOT).endsWith(".json")
                && VodFileSelection.accepts(file)) {
            result.add(new Row(FILE, "📄 " + name, file, null));
        }
    }

    /** Kept separate so Android 7 loads no java.nio.file classes. */
    private static final class DirectoryReader {
        static void read(File directory, List<Row> result) throws IOException {
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory.toPath())) {
                for (Path entry : entries) {
                    addEntry(result, entry.toFile());
                    if (result.size() >= MAX_ROWS) break;
                }
            }
        }
    }

    private List<Root> roots() {
        List<Root> found = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        try {
            addRoot(found, seen, Environment.getExternalStorageDirectory(), "电视内部存储");
        } catch (RuntimeException ignored) { }
        try {
            for (File appDir : activity.getExternalFilesDirs(null)) {
                if (appDir == null) continue;
                String path = appDir.getAbsolutePath();
                int android = path.indexOf("/Android/");
                if (android > 0) addRoot(found, seen, new File(path.substring(0, android)), "应用可访问存储");
            }
        } catch (RuntimeException ignored) { }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                StorageManager manager = (StorageManager) activity.getSystemService(Context.STORAGE_SERVICE);
                if (manager != null) for (StorageVolume volume : manager.getStorageVolumes()) {
                    addRoot(found, seen, volume.getDirectory(), volume.isPrimary()
                            ? "电视内部存储" : "外接存储 · " + volume.getDescription(activity));
                }
            } catch (RuntimeException ignored) { }
        }
        try {
            File[] volumes = new File("/storage").listFiles();
            if (volumes != null) for (File volume : volumes) {
                if (!"emulated".equals(volume.getName()) && !"self".equals(volume.getName()))
                    addRoot(found, seen, volume, "外接存储");
            }
        } catch (RuntimeException ignored) { }
        return found;
    }

    private void addRoot(List<Root> found, Set<String> seen, File directory, String title) {
        try {
            if (directory == null || !directory.isDirectory() || !directory.canRead()) return;
            String key = canonical(directory);
            if (seen.add(key)) found.add(new Root(directory, title));
        } catch (RuntimeException ignored) { }
    }

    private boolean inside(File volume, File path) {
        String base = canonical(volume);
        String candidate = canonical(path);
        return candidate.equals(base) || candidate.startsWith(base + File.separator);
    }

    private boolean same(File first, File second) {
        return canonical(first).equals(canonical(second));
    }

    private String canonical(File file) {
        try {
            return file.getCanonicalPath();
        } catch (IOException | RuntimeException error) {
            return file.getAbsolutePath();
        }
    }

    private void trace(String message) {
        Log.i("JKTVPicker", message);
        SpiderDebug.log("picker", "%s", message);
    }

    private record Root(File directory, String title) { }
    private record Row(int kind, String label, File file, Root root) { }
}
