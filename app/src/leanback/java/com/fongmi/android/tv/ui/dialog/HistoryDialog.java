package com.fongmi.android.tv.ui.dialog;

import android.view.View;

import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.LiveConfig;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.api.config.WallConfig;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.databinding.DialogHistoryBinding;
import com.fongmi.android.tv.impl.ConfigListener;
import com.fongmi.android.tv.ui.adapter.ConfigAdapter;
import com.fongmi.android.tv.ui.custom.SpaceItemDecoration;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.Task;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.List;
import java.util.concurrent.Future;

public class HistoryDialog extends BaseAlertDialog implements ConfigAdapter.OnClickListener {

    private DialogHistoryBinding binding;
    private ConfigAdapter adapter;
    private ConfigListener listener;
    private boolean readOnly;
    private Future<?> query;
    private int type;

    public static HistoryDialog create() {
        return new HistoryDialog();
    }

    public HistoryDialog vod() {
        type = 0;
        return this;
    }

    public HistoryDialog live() {
        type = 1;
        return this;
    }

    public HistoryDialog wall() {
        type = 2;
        return this;
    }

    public HistoryDialog readOnly() {
        readOnly = true;
        return this;
    }

    public void show(FragmentActivity activity) {
        show(activity.getSupportFragmentManager(), null);
        if (activity instanceof ConfigListener) listener = (ConfigListener) activity;
    }

    public void show(FragmentActivity activity, ConfigListener listener) {
        show(activity.getSupportFragmentManager(), null);
        this.listener = listener;
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogHistoryBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return builder().setView(getBinding().getRoot());
    }

    @Override
    protected void initView() {
        adapter = new ConfigAdapter(this);
        binding.recycler.setItemAnimator(null);
        binding.recycler.setHasFixedSize(false);
        binding.recycler.addItemDecoration(new SpaceItemDecoration(1, 16));
        binding.recycler.setAdapter(adapter.readOnly(readOnly));
        DialogHistoryBinding target = binding;
        String currentUrl = getConfig().getUrl();
        int configType = type;
        query = Task.submit(() -> {
            try {
                List<Config> items = Config.getAll(configType);
                App.post(() -> {
                    if (binding != target || !isAdded()) return;
                    target.loading.setVisibility(View.GONE);
                    adapter.addAll(items, currentUrl);
                    if (adapter.getItemCount() == 0) {
                        Notify.show(R.string.error_empty);
                        dismissAllowingStateLoss();
                    } else {
                        target.recycler.requestFocus();
                    }
                });
            } catch (RuntimeException failure) {
                App.post(() -> {
                    if (binding != target || !isAdded()) return;
                    Notify.show(R.string.error_config_get);
                    dismissAllowingStateLoss();
                });
            }
        });
    }

    private Config getConfig() {
        return switch (type) {
            case 0 -> VodConfig.get().getConfig();
            case 1 -> LiveConfig.get().getConfig();
            case 2 -> WallConfig.get().getConfig();
            default -> Config.create(type);
        };
    }

    @Override
    public void onTextClick(Config item) {
        if (listener != null) listener.setConfig(item);
        dismiss();
    }

    @Override
    public void onDeleteClick(Config item) {
        if (adapter.remove(item) == 0) dismiss();
    }

    @Override
    public void onStart() {
        super.onStart();
        setWidth(0.4f);
    }

    @Override
    public void onDestroyView() {
        if (query != null) query.cancel(false);
        query = null;
        binding = null;
        super.onDestroyView();
    }
}
