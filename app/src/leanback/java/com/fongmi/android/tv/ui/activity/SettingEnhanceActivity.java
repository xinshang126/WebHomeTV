package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.ActivitySettingEnhanceBinding;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.dialog.DebugLogDialog;

public class SettingEnhanceActivity extends BaseActivity {

    private ActivitySettingEnhanceBinding mBinding;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, SettingEnhanceActivity.class));
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivitySettingEnhanceBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        mBinding.debugLog.requestFocus();
    }

    @Override
    protected void initEvent() {
        mBinding.debugLog.setOnClickListener(view -> {
            boolean enabled = !Setting.isDebugLog();
            Setting.putDebugLog(enabled);
            updateDebugLogText();
            if (enabled) DebugLogDialog.show(this);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateDebugLogText();
    }

    private void updateDebugLogText() {
        mBinding.debugLogText.setText(Setting.isDebugLog() ? R.string.setting_enable : R.string.setting_disable);
    }
}
