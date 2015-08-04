/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.mediatek.systemui.qs.tiles;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.ComponentName;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Message;
import android.os.RemoteException;
import android.provider.Settings;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.systemui.R;
import com.android.systemui.qs.QSTile;

import com.mediatek.xlog.Xlog;
import com.mediatek.audioprofile.AudioProfileManager;
import com.mediatek.audioprofile.AudioProfileManager.Scenario;
import com.mediatek.common.audioprofile.AudioProfileListener;
import com.mediatek.systemui.statusbar.util.SIMHelper;

import java.util.ArrayList;
import java.util.List;

public class AudioProfileTile extends QSTile<QSTile.BooleanState> {
    private static final String TAG = "AudioProfileTile";
    private static final boolean DBG = false;
    private static final Intent WIFI_DISPLAY_SETTINGS =
            new Intent(Settings.ACTION_WIFI_DISPLAY_SETTINGS);

    private static final boolean ENABLE_AUDIO_PROFILE =
            SIMHelper.isMtkAudioProfilesSupport();

    private static final int PROFILE_SWITCH_DIALOG_LONG_TIMEOUT = 4000;
    private static final int PROFILE_SWITCH_DIALOG_SHORT_TIMEOUT = 2000;
    private static final int SHOW_PROFILE_SWITCH_DIALOG = 9000;

    private boolean mListening;
    private boolean mUpdating = false;

    private Dialog mProfileSwitchDialog;

    private ImageView mNormalProfileIcon;
    private ImageView mMettingProfileIcon;
    private ImageView mMuteProfileIcon;
    private ImageView mOutdoorSwitchIcon;
    private ImageView mAudioProfileIcon;

    private AudioProfileManager mProfileManager;
    private AudioManager mAudioManager;
    private List<String> mProfileKeys;
    private Scenario mCurrentScenario;
    
    private int mAudioState = R.drawable.ic_qs_custom_on;

    public AudioProfileTile(Host host) {
        super(host);
        createProfileSwitchDialog();
        setAudioProfileUpdates(true);
    }

    @Override
    protected BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    public void setListening(boolean listening) {
    }

    @Override
    protected void handleClick() {
        Message msg = mHandler.obtainMessage(SHOW_PROFILE_SWITCH_DIALOG);
        mHandler.sendMessage(msg);
    }
    @Override
    protected void handleLongClick() {
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(
                "com.android.settings",
                "com.android.settings.Settings$AudioProfileSettingsActivity"));
        mHost.startSettingsActivity(intent);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        state.label = mContext.getString(R.string.audio_profile);
        state.visible = true;
        state.iconId = mAudioState;
    }

    private void updateAudioProfile(String key) {
        if (key == null) {
            return;
        }
        if (DBG) {
            Xlog.i(TAG, "updateAudioProfile called, selected profile is: " + key);
        }
        if (ENABLE_AUDIO_PROFILE) {
            mProfileManager.setActiveProfile(key);
        }
        if (DBG) {
            Xlog.d(TAG, "updateAudioProfile called, setActiveProfile is: " + key);
        }
    }

    private void showProfileSwitchDialog() {
        createProfileSwitchDialog();
        if (!mProfileSwitchDialog.isShowing()) {
            try {
                WindowManagerGlobal.getWindowManagerService().dismissKeyguard();
            } catch (RemoteException e) {
            }
            mProfileSwitchDialog.show();
            dismissProfileSwitchDialog(PROFILE_SWITCH_DIALOG_LONG_TIMEOUT);
        }
    }

    private void createProfileSwitchDialog() {
        if (DBG) {
            Xlog.i(TAG, "createProfileSwitchDialog");
        }
        mProfileSwitchDialog = null;

        mProfileSwitchDialog = new Dialog(mContext);
        mProfileSwitchDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        mProfileSwitchDialog.setContentView(R.layout.quick_settings_profile_switch_dialog);
        mProfileSwitchDialog.setCanceledOnTouchOutside(true);
        mProfileSwitchDialog.getWindow().setType(WindowManager.LayoutParams.TYPE_STATUS_BAR_PANEL);
        mProfileSwitchDialog.getWindow().getAttributes().privateFlags |=
                WindowManager.LayoutParams.PRIVATE_FLAG_SHOW_FOR_ALL_USERS;
        mProfileSwitchDialog.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        mProfileSwitchDialog.getWindow().setFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);

        mMettingProfileIcon =
                (ImageView) mProfileSwitchDialog.findViewById(R.id.meeting_profile_icon);
        mOutdoorSwitchIcon =
                (ImageView) mProfileSwitchDialog.findViewById(R.id.outdoor_profile_icon);
        mMuteProfileIcon =
                (ImageView) mProfileSwitchDialog.findViewById(R.id.mute_profile_icon);
        mNormalProfileIcon =
                (ImageView) mProfileSwitchDialog.findViewById(R.id.normal_profile_icon);

        View normalProfile = (View) mProfileSwitchDialog.findViewById(R.id.normal_profile);
        TextView normalProfileText =
                (TextView) mProfileSwitchDialog.findViewById(R.id.normal_profile_text);
        normalProfileText.setText(mContext.getString(R.string.normal));
        normalProfile.setOnClickListener(mProfileSwitchListener);
        normalProfile.setTag(AudioProfileManager.getProfileKey(Scenario.GENERAL));

        View muteProfile = (View) mProfileSwitchDialog.findViewById(R.id.mute_profile);
        TextView muteProfileText =
                (TextView) mProfileSwitchDialog.findViewById(R.id.mute_profile_text);
        muteProfileText.setText(mContext.getString(R.string.mute));
        muteProfile.setOnClickListener(mProfileSwitchListener);
        muteProfile.setTag(AudioProfileManager.getProfileKey(Scenario.SILENT));

        View meetingProfile = (View) mProfileSwitchDialog.findViewById(R.id.meeting_profile);
        TextView meetingProfileText =
                (TextView) mProfileSwitchDialog.findViewById(R.id.meeting_profile_text);
        meetingProfileText.setText(mContext.getString(R.string.meeting));
        meetingProfile.setOnClickListener(mProfileSwitchListener);
        meetingProfile.setTag(AudioProfileManager.getProfileKey(Scenario.MEETING));

        View outdoorProfile = (View) mProfileSwitchDialog.findViewById(R.id.outdoor_profile);
        TextView outdoorProfileText =
                (TextView) mProfileSwitchDialog.findViewById(R.id.outdoor_profile_text);
        outdoorProfileText.setText(mContext.getString(R.string.outdoor));
        outdoorProfile.setOnClickListener(mProfileSwitchListener);
        outdoorProfile.setTag(AudioProfileManager.getProfileKey(Scenario.OUTDOOR));

        if (mCurrentScenario != null) {
            Xlog.i(TAG, "mCurrentScenario != null");
            loadEnabledProfileResource(mCurrentScenario);
        }
        else {
            Xlog.i(TAG, "mCurrentScenario = null");
        }
        
    }

    private View.OnClickListener mProfileSwitchListener = new View.OnClickListener() {
        public void onClick(View v) {
            if (ENABLE_AUDIO_PROFILE) {
                for (int i = 0; i < mProfileKeys.size(); i++) {
                    if (v.getTag().equals(mProfileKeys.get(i))) {
                        if (DBG) {
                            Xlog.d(TAG, "onClick called, profile clicked is:" +
                                    mProfileKeys.get(i));
                        }
                        String key = mProfileKeys.get(i);
                        updateAudioProfile(key);
                        Scenario senario = AudioProfileManager.getScenario(key);
                        updateProfileView(senario);
                        if (mProfileSwitchDialog != null) {
                            mProfileSwitchDialog.dismiss();
                        }
                    }
                }
            }
        }
    };

    private AudioProfileListener mAudioProfileListenr = new AudioProfileListener() {
        @Override
        public void onProfileChanged(String profileKey) {
            if (ENABLE_AUDIO_PROFILE) {
                if (profileKey != null) {
                    if (!mUpdating) {
                        /// M: AudioProfile is no ready, so skip update
                        Xlog.d(TAG, "onProfileChanged !mUpdating");
                        return;
                    }
                    Scenario senario = AudioProfileManager.getScenario(profileKey);
                    if (DBG) {
                        Xlog.d(TAG, "onProfileChanged onReceive called, profile type is: " +
                                senario);
                    }
                    if (senario != null) {
                        updateProfileView(senario);
                    }
                }
            }
        }
    };

    private void updateProfileView(Scenario scenario) {
        if (DBG) {
            Xlog.d(TAG, "updateProfileView before");
        }
        loadDisabledProfileResouceForAll();
        loadEnabledProfileResource(scenario);
    }

    private void loadDisabledProfileResouceForAll() {
        if (DBG) {
            Xlog.d(TAG, "loadDisabledProfileResouceForAll");
        }
        mNormalProfileIcon.setImageResource(R.drawable.ic_qs_normal_off);
        mMettingProfileIcon.setImageResource(R.drawable.ic_qs_meeting_profile_off);
        mOutdoorSwitchIcon.setImageResource(R.drawable.ic_qs_outdoor_off);
        mMuteProfileIcon.setImageResource(R.drawable.ic_qs_mute_profile_off);
    }

    private void loadEnabledProfileResource(Scenario scenario) {
        if (DBG) {
            Xlog.d(TAG, "loadEnabledProfileResource called, profile is: " + scenario);
        }
        mCurrentScenario = scenario;
        int audioState;
        switch (scenario) {
        case GENERAL:
            mNormalProfileIcon.setImageResource(R.drawable.ic_qs_normal_profile_enable);
            mAudioState = R.drawable.ic_qs_general_on;
            break;
        case MEETING:
            mMettingProfileIcon.setImageResource(R.drawable.ic_qs_meeting_profile_enable);
            mAudioState = R.drawable.ic_qs_meeting_on;
            break;
        case OUTDOOR:
            mOutdoorSwitchIcon.setImageResource(R.drawable.ic_qs_outdoor_profile_enable);
            mAudioState = R.drawable.ic_qs_outdoor_on;
            break;
        case SILENT:
            mMuteProfileIcon.setImageResource(R.drawable.ic_qs_mute_profile_enable);
            mAudioState = R.drawable.ic_qs_silent_on;
            break;
        case CUSTOM:
            mAudioState = R.drawable.ic_qs_custom_on;
        default:
            mAudioState = R.drawable.ic_qs_custom_on;
            break;
        }
        refreshState();
    }

    private void dismissProfileSwitchDialog(int timeout) {
        removeAllProfileSwitchDialogCallbacks();
        if (mProfileSwitchDialog != null) {
            mHandler.postDelayed(mDismissProfileSwitchDialogRunnable, timeout);
        }
    }

    private Runnable mDismissProfileSwitchDialogRunnable = new Runnable() {
        public void run() {
            if (DBG) {
                Xlog.d(TAG, "mDismissProfileSwitchDialogRunnable");
            }
            if (mProfileSwitchDialog != null && mProfileSwitchDialog.isShowing()) {
                mProfileSwitchDialog.dismiss();
            }
            removeAllProfileSwitchDialogCallbacks();
        };
    };

    private void removeAllProfileSwitchDialogCallbacks() {
        mHandler.removeCallbacks(mDismissProfileSwitchDialogRunnable);
    }

    public void setAudioProfileUpdates(boolean update) {
        if (update != mUpdating) {
            if (ENABLE_AUDIO_PROFILE) {
                mProfileManager =
                        (AudioProfileManager) mContext.getSystemService(Context.AUDIO_PROFILE_SERVICE);
                mProfileManager.listenAudioProfie(mAudioProfileListenr,
                        AudioProfileListener.LISTEN_PROFILE_CHANGE);
            }
            mProfileKeys = new ArrayList<String>();
            mProfileKeys = mProfileManager.getPredefinedProfileKeys();
            mUpdating = update;
        }
        else {
            if (ENABLE_AUDIO_PROFILE) {
                mProfileManager.listenAudioProfie(mAudioProfileListenr, AudioProfileListener.STOP_LISTEN);
            }
        }
    }
    
    Handler mHandler = new Handler() {
        public void handleMessage(Message msg) {
            switch (msg.what) {
            case SHOW_PROFILE_SWITCH_DIALOG:
                showProfileSwitchDialog();
                break;                    
            default:
                break;
            }
        }
    };
}
