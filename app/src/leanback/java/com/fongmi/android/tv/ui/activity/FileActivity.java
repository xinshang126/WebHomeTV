package com.fongmi.android.tv.ui.activity;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.view.View;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.ActivityFileBinding;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.adapter.FileAdapter;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.utils.PermissionUtil;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.VodFileSelection;
import com.github.catvod.utils.Path;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FileActivity extends BaseActivity implements FileAdapter.OnClickListener {

    public static final String EXTRA_VOD_CONFIG = "select_jktv_vod_config";

    private ActivityFileBinding mBinding;
    private FileAdapter mAdapter;
    private File dir;
    private boolean selectDir;
    private boolean selectConfig;
    private ExecutorService directoryWorker;
    private int navigationVersion;

    private final ActivityResultLauncher<Intent> systemFiles = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() != RESULT_OK) return;
                Uri uri = result.getData() == null ? null : result.getData().getData();
                File original = getOriginalFile(uri);
                if (!VodFileSelection.accepts(original)) {
                    mBinding.configStatus.setText(R.string.jktv_picker_original_required);
                    return;
                }
                finishWithFile(original);
            });

    private boolean isRoot() {
        return dir == null || Path.root().equals(dir);
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityFileBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        selectDir = getIntent().getBooleanExtra("select_dir", false);
        setRecyclerView();
        selectConfig = getIntent().getBooleanExtra(EXTRA_VOD_CONFIG, false);
        if (selectConfig) {
            mBinding.configControls.setVisibility(View.VISIBLE);
            directoryWorker = Executors.newFixedThreadPool(2);
            mBinding.configUp.setOnClickListener(v -> {
                if (!isRoot()) update(dir.getParentFile());
            });
            mBinding.configSystem.setOnClickListener(v -> openSystemFiles());
            mBinding.configAccess.setOnClickListener(v -> requestConfigStorage());
            mBinding.configCancel.setOnClickListener(v -> finish());
            update(Path.root());
            mBinding.configSystem.requestFocus();
        } else {
            checkPermission();
        }
    }

    private void setRecyclerView() {
        mBinding.recycler.setHasFixedSize(true);
        mBinding.recycler.setVerticalSpacing(ResUtil.dp2px(16));
        mBinding.recycler.setAdapter(mAdapter = new FileAdapter(this));
    }

    private void checkPermission() {
        PermissionUtil.requestFile(this, allGranted -> update(Path.root()));
    }

    private void requestConfigStorage() {
        try {
            PermissionUtil.requestFile(this, granted -> {
                if (isFinishing() || isDestroyed()) return;
                update(dir == null ? Path.root() : dir);
            });
        } catch (ActivityNotFoundException | SecurityException denied) {
            mBinding.configStatus.setText(R.string.jktv_picker_access_missing);
        }
    }

    private void openSystemFiles() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*")
                .addCategory(Intent.CATEGORY_OPENABLE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        // Some TV firmware advertises only a stub, which otherwise opens a blank activity.
        ResolveInfo picker = getPackageManager().queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
                .stream().filter(info -> info.activityInfo != null
                        && !info.activityInfo.packageName.contains("frameworkpackagestubs"))
                .findFirst().orElse(null);
        if (picker == null) {
            mBinding.configStatus.setText(R.string.jktv_picker_system_missing);
            return;
        }
        try {
            systemFiles.launch(intent.setClassName(picker.activityInfo.packageName, picker.activityInfo.name));
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            mBinding.configStatus.setText(R.string.jktv_picker_system_missing);
        }
    }

    private File getOriginalFile(Uri uri) {
        if (uri == null) return null;
        try {
            if ("file".equalsIgnoreCase(uri.getScheme())) {
                return uri.getPath() == null ? null : new File(uri.getPath());
            }
            if (!"content".equalsIgnoreCase(uri.getScheme())) return null;
            if (DocumentsContract.isDocumentUri(this, uri)) {
                String docId = DocumentsContract.getDocumentId(uri);
                if ("com.android.externalstorage.documents".equals(uri.getAuthority())) {
                    String[] parts = docId.split(":", 2);
                    if (parts.length == 2 && parts[0].matches("[A-Za-z0-9-]+")) {
                        File volume = "primary".equalsIgnoreCase(parts[0])
                                ? Path.root() : new File("/storage", parts[0]);
                        File candidate = new File(volume, parts[1]).getCanonicalFile();
                        if (candidate.getPath().startsWith(volume.getCanonicalPath() + File.separator)) {
                            return candidate;
                        }
                    }
                }
                if ("com.android.providers.downloads.documents".equals(uri.getAuthority())
                        && docId.startsWith("raw:")) return new File(docId.substring(4));
            }
            String col = MediaStore.MediaColumns.DATA;
            try (Cursor cursor = getContentResolver().query(uri, new String[]{col}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int index = cursor.getColumnIndex(col);
                    String path = index < 0 ? null : cursor.getString(index);
                    if (path != null && path.startsWith(File.separator)) return new File(path);
                }
            }
        } catch (Exception unreadable) {
            // Never copy only a JSON to cache: relative JAR/script files would break.
        }
        return null;
    }

    private void finishWithFile(File file) {
        setResult(RESULT_OK, new Intent().setData(Uri.fromFile(file)));
        finish();
    }

    private void update(File target) {
        if (target == null) return;
        if (!selectConfig) {
            List<File> entries = list(target);
            mAdapter.addAll(dir = target, entries, selectDir);
            if (mAdapter.getItemCount() > 0) mBinding.recycler.setSelectedPosition(0);
            mBinding.progressLayout.showContent(true, mAdapter.getItemCount());
            return;
        }
        boolean moveFocus = dir == null || mBinding.recycler.hasFocus();
        dir = target;
        int version = ++navigationVersion;
        mBinding.configUp.setEnabled(!isRoot());
        mBinding.configStatus.setText(getString(R.string.jktv_picker_loading, target.getAbsolutePath()));
        mBinding.configAccess.setVisibility(Setting.hasFileAccess() ? View.GONE : View.VISIBLE);
        if (mBinding.recycler.hasFocus()) {
            if (isRoot()) mBinding.configSystem.requestFocus();
            else mBinding.configUp.requestFocus();
        }
        mAdapter.addAll(dir, List.of(), false);
        mBinding.progressLayout.showContent();
        directoryWorker.execute(() -> {
            List<File> entries = VodFileSelection.list(target);
            App.post(() -> {
                if (VodFileSelection.currentNavigation(version, navigationVersion, isFinishing() || isDestroyed())) {
                    renderConfigEntries(target, entries, moveFocus);
                }
            });
        });
    }

    private void renderConfigEntries(File target, List<File> entries, boolean moveFocus) {
        mAdapter.addAll(target, entries == null ? List.of() : entries, false);
        if (entries == null) {
            mBinding.configStatus.setText(R.string.jktv_picker_folder_unreadable);
        } else if (entries.isEmpty()) {
            mBinding.configStatus.setText(R.string.jktv_picker_empty);
        } else {
            mBinding.configStatus.setText(getString(R.string.jktv_picker_location, target.getAbsolutePath()));
            mBinding.recycler.setSelectedPosition(0);
            if (moveFocus && (mBinding.configSystem.hasFocus() || mBinding.configUp.hasFocus())) {
                mBinding.recycler.requestFocus();
            }
        }
    }

    private List<File> list(File dir) {
        if (!selectDir) return Path.list(dir);
        File[] files = dir.listFiles(File::isDirectory);
        if (files == null) return new ArrayList<>();
        Path.sort(files);
        return Arrays.asList(files);
    }

    @Override
    protected void onDestroy() {
        ++navigationVersion;
        if (directoryWorker != null) directoryWorker.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onItemClick(File file) {
        if (file.isDirectory()) {
            update(file);
        } else if (selectConfig && !VodFileSelection.accepts(file)) {
            mBinding.configStatus.setText(R.string.jktv_picker_invalid);
        } else {
            finishWithFile(file);
        }
    }

    @Override
    public void onCurrentDirClick(File dir) {
        if (dir == null) return;
        setResult(RESULT_OK, new Intent().setData(Uri.fromFile(dir)));
        finish();
    }

    @Override
    protected void onBackInvoked() {
        if (isRoot()) {
            super.onBackInvoked();
        } else {
            update(dir.getParentFile());
        }
    }
}
