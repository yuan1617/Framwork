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

package com.android.systemui.qs.tiles;

import android.content.Context;
import android.content.Intent;
import android.hardware.display.WifiDisplayStatus;
import android.net.wifi.p2p.WifiP2pDevice;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnAttachStateChangeListener;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.systemui.R;
import com.android.systemui.qs.QSDetailItems;
import com.android.systemui.qs.QSDetailItems.Item;
import com.android.systemui.qs.QSTile;
import com.android.systemui.statusbar.policy.CastController;
import com.android.systemui.statusbar.policy.CastController.CastDevice;
import com.android.systemui.statusbar.policy.KeyguardMonitor;

import java.util.LinkedHashMap;
import java.util.Set;

/** Quick settings tile: Cast **/
public class CastTile extends QSTile<QSTile.BooleanState> {
    private static final Intent CAST_SETTINGS =
            new Intent(Settings.ACTION_CAST_SETTINGS);
    // M: Add WFD sink setting intent
    private static final Intent WFD_SINK_SETTINGS =
            new Intent("mediatek.settings.WFD_SINK_SETTINGS");
    private static final boolean DEBUG = true;

    private final CastController mController;
    private final CastDetailAdapter mDetailAdapter;
    private final KeyguardMonitor mKeyguard;
    private final Callback mCallback = new Callback();

    public CastTile(Host host) {
        super(host);
        mController = host.getCastController();
        mDetailAdapter = new CastDetailAdapter();
        mKeyguard = host.getKeyguardMonitor();
        // M: WFD sink support
        mController.setListening(true);
    }

    @Override
    public DetailAdapter getDetailAdapter() {
        return mDetailAdapter;
    }

    @Override
    protected BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    public void setListening(boolean listening) {
        if (mController == null) return;
        if (DEBUG) Log.d(TAG, "setListening " + listening);
        if (listening) {
            mController.addCallback(mCallback);
            mKeyguard.addCallback(mCallback);
        } else {
            mController.setDiscovering(false);
            mController.removeCallback(mCallback);
            mKeyguard.removeCallback(mCallback);
        }
    }

    /// M: WFD sink support {@
    @Override
    protected void handleDestroy() {
        super.handleDestroy();
        if (mController == null) return;
        if (DEBUG) Log.d(TAG, "handle destroy");
        mController.setListening(false);
    }
    /// @}

    @Override
    protected void handleUserSwitch(int newUserId) {
        super.handleUserSwitch(newUserId);
        if (mController == null) return;
        mController.setCurrentUserId(newUserId);
    }

    @Override
    protected void handleClick() {
        if (DEBUG) Log.d(TAG, "handleClick");
        showDetail(true);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        if (DEBUG) Log.d(TAG, "handleUpdateState");
        state.visible = !(mKeyguard.isSecure() && mKeyguard.isShowing());
        state.label = mContext.getString(R.string.quick_settings_cast_title);
        state.value = false;
        state.autoMirrorDrawable = false;
        final Set<CastDevice> devices = mController.getCastDevices();
        boolean connecting = false;
        for (CastDevice device : devices) {
            if (device.state == CastDevice.STATE_CONNECTED) {
                state.value = true;
                state.label = getDeviceName(device);
            } else if (device.state == CastDevice.STATE_CONNECTING) {
                connecting = true;
            }
        }
        if (!state.value && connecting) {
            state.label = mContext.getString(R.string.quick_settings_connecting);
        }
        state.iconId = state.value ? R.drawable.ic_qs_cast_on : R.drawable.ic_qs_cast_off;
        mDetailAdapter.updateItems(devices);
        // M: WFD sink support
        mDetailAdapter.updateSinkView();
    }

    @Override
    protected String composeChangeAnnouncement() {
        if (!mState.value) {
            // We only announce when it's turned off to avoid vocal overflow.
            return mContext.getString(R.string.accessibility_casting_turned_off);
        }
        return null;
    }

    private String getDeviceName(CastDevice device) {
        return device.name != null ? device.name
                : mContext.getString(R.string.quick_settings_cast_device_default_name);
    }

    private final class Callback implements CastController.Callback, KeyguardMonitor.Callback {
        @Override
        public void onCastDevicesChanged() {
            if (DEBUG) Log.d(TAG, "onCastDevicesChanged");
            refreshState();
        }

        /// M: WFD sink support {@
        @Override
        public void onWfdStatusChanged(WifiDisplayStatus status, boolean sinkMode) {
            if (DEBUG) Log.d(TAG, "onWfdStatusChanged: " + status.getActiveDisplayState());
            mDetailAdapter.wfdStatusChanged(status, sinkMode);
            refreshState();
        }

        @Override
        public void onWifiP2pDeviceChanged(WifiP2pDevice device) {
            if (DEBUG) Log.d(TAG, "onWifiP2pDeviceChanged");
            mDetailAdapter.updateDeviceName(device);
        }
        /// @}

        @Override
        public void onKeyguardChanged() {
            if (DEBUG) Log.d(TAG, "onKeyguardChanged");
            refreshState();
        }
    };

    private final class CastDetailAdapter implements DetailAdapter, QSDetailItems.Callback {
        private final LinkedHashMap<String, CastDevice> mVisibleOrder = new LinkedHashMap<>();

        private QSDetailItems mItems;
        /// M: WFD sink support {@
        private View mWfdSinkView;
        private LinearLayout mDetailView;
        /// @}

        @Override
        public int getTitle() {
            return R.string.quick_settings_cast_title;
        }

        @Override
        public Boolean getToggleState() {
            return null;
        }

        @Override
        public Intent getSettingsIntent() {
            return CAST_SETTINGS;
        }

        @Override
        public void setToggleState(boolean state) {
            // noop
        }

        @Override
        public View createDetailView(Context context, View convertView, ViewGroup parent) {
            /// M: WFD sink support {@
            if (mController.isWfdSinkSupported()) {
                mItems = QSDetailItems.convertOrInflate(context, mItems, parent);
            } else {
                mItems = QSDetailItems.convertOrInflate(context, convertView, parent);
            }
            /// @}
            mItems.setTagSuffix("Cast");
            if (convertView == null) {
                if (DEBUG) Log.d(TAG, "addOnAttachStateChangeListener");
                mItems.addOnAttachStateChangeListener(new OnAttachStateChangeListener() {
                    @Override
                    public void onViewAttachedToWindow(View v) {
                        if (DEBUG) Log.d(TAG, "onViewAttachedToWindow");
                    }

                    @Override
                    public void onViewDetachedFromWindow(View v) {
                        if (DEBUG) Log.d(TAG, "onViewDetachedFromWindow");
                        mVisibleOrder.clear();
                    }
                });
            }
            mItems.setEmptyState(R.drawable.ic_qs_cast_detail_empty,
                    R.string.quick_settings_cast_detail_empty_text);
            mItems.setCallback(this);
            updateItems(mController.getCastDevices());
            mController.setDiscovering(true);
            /// M: WFD sink support {@
            if (mController.isWfdSinkSupported()) {
                if (DEBUG) Log.d(TAG, "add WFD sink view: " + (mWfdSinkView == null));
                if (mWfdSinkView == null) {
                    LayoutInflater layoutInflater = LayoutInflater.from(context);
                    mWfdSinkView = layoutInflater
                        .inflate(R.layout.qs_wfd_prefrence_material, parent, false);
                    final ViewGroup widgetFrame = (ViewGroup) mWfdSinkView
                        .findViewById(com.android.internal.R.id.widget_frame);
                    layoutInflater.inflate(R.layout.qs_wfd_widget_checkbox, widgetFrame);
                    ImageView view = (ImageView) mWfdSinkView.findViewById(com.android.internal.R.id.icon);
                    if (context.getResources().getBoolean(com.android.internal.R.bool.config_voice_capable)) {
                        view.setImageResource(R.drawable.ic_wfd_cellphone);
                    } else {
                        view.setImageResource(R.drawable.ic_wfd_laptop);
                    }
                    TextView summary = (TextView) mWfdSinkView.findViewById(com.android.internal.R.id.summary);
                    summary.setText(R.string.wfd_sink_summary);
                    mWfdSinkView.setOnClickListener(new View.OnClickListener() {
                        public void onClick(View v) {
                            CheckBox box = (CheckBox) v.findViewById(com.android.internal.R.id.checkbox);
                            boolean checked = box.isChecked();
                            if (!checked) {
                                getHost().startSettingsActivity(WFD_SINK_SETTINGS);
                            }
                            box.setChecked(!checked);
                        }
                    });
                }
                if (convertView instanceof LinearLayout) {
                    mDetailView = (LinearLayout) convertView;
                    updateSinkView();
                } else {
                    mDetailView = new LinearLayout(context);
                    mDetailView.setOrientation(LinearLayout.VERTICAL);
                    mDetailView.addView(mWfdSinkView);
                    View devider = new View(context);
                    int dh = context.getResources().getDimensionPixelSize(R.dimen.qs_tile_divider_height);
                    devider.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, dh));
                    devider.setBackgroundColor(context.getResources().getColor(R.color.qs_tile_divider));
                    mDetailView.addView(devider);
                    mDetailView.addView(mItems);
                }
                updateDeviceName(mController.getWifiP2pDev());
                setSinkViewVisible(mController.isNeedShowWfdSink());
            }
            if (mDetailView != null) {
                return mDetailView;
            } else {
                return mItems;
            }
            /// @}
        }

        private void updateItems(Set<CastDevice> devices) {
            if (DEBUG) Log.d(TAG, "update items: " + devices.size());
            if (mItems == null) return;
            Item[] items = null;
            if (devices != null && !devices.isEmpty()) {
                // if we are connected, simply show that device
                for (CastDevice device : devices) {
                    if (device.state == CastDevice.STATE_CONNECTED) {
                        final Item item = new Item();
                        item.icon = R.drawable.ic_qs_cast_on;
                        item.line1 = getDeviceName(device);
                        item.line2 = mContext.getString(R.string.quick_settings_connected);
                        item.tag = device;
                        item.canDisconnect = true;
                        items = new Item[] { item };
                        break;
                    }
                }
                // otherwise list all available devices, and don't move them around
                if (items == null) {
                    for (CastDevice device : devices) {
                        mVisibleOrder.put(device.id, device);
                    }
                    items = new Item[devices.size()];
                    int i = 0;
                    for (String id : mVisibleOrder.keySet()) {
                        final CastDevice device = mVisibleOrder.get(id);
                        if (!devices.contains(device)) continue;
                        final Item item = new Item();
                        item.icon = R.drawable.ic_qs_cast_off;
                        item.line1 = getDeviceName(device);
                        if (device.state == CastDevice.STATE_CONNECTING) {
                            item.line2 = mContext.getString(R.string.quick_settings_connecting);
                        }
                        item.tag = device;
                        items[i++] = item;
                    }
                }
            }
            mItems.setItems(items);
        }

        @Override
        public void onDetailItemClick(Item item) {
            if (item == null || item.tag == null) return;
            final CastDevice device = (CastDevice) item.tag;
            if (DEBUG) Log.d(TAG, "onDetailItemClick: " + device.name);
            mController.startCasting(device);
            // M: WFD sink support
            mController.updateWfdFloatMenu(true);
        }

        @Override
        public void onDetailItemDisconnect(Item item) {
            if (item == null || item.tag == null) return;
            final CastDevice device = (CastDevice) item.tag;
            if (DEBUG) Log.d(TAG, "onDetailItemDisconnect: " + device.name);
            mController.stopCasting(device);
            // M: WFD sink support
            mController.updateWfdFloatMenu(false);
        }

        /// M: WFD sink support {@
        private void wfdStatusChanged(WifiDisplayStatus status, boolean sinkMode) {
            boolean show = mController.isNeedShowWfdSink();
            setSinkViewVisible(show);
            handleWfdStateChanged(show ? status.getActiveDisplayState() :
                WifiDisplayStatus.DISPLAY_STATE_NOT_CONNECTED, sinkMode);
        }

        private void handleWfdStateChanged(int wfdState, boolean sinkMode) {
            switch (wfdState) {
            case WifiDisplayStatus.DISPLAY_STATE_NOT_CONNECTED:
                if (!sinkMode) {
                    setSinkViewEnabled(true);
                    setSinkViewChecked(false);
                    mController.updateWfdFloatMenu(false);
                }
                break;
            case WifiDisplayStatus.DISPLAY_STATE_CONNECTING:
                if (!sinkMode) {
                    setSinkViewEnabled(false);
                }
                break;
            case WifiDisplayStatus.DISPLAY_STATE_CONNECTED:
                if (!sinkMode) {
                    setSinkViewEnabled(false);
                }
                break;
            default:
                break;
            }
        }

        private void updateDeviceName(WifiP2pDevice device) {
            if (device != null && mWfdSinkView != null) {
                if (DEBUG) Log.d(TAG, "updateDeviceName: " + device.deviceName);
                TextView textView = (TextView) mWfdSinkView.findViewById(com.android.internal.R.id.title);
                if (TextUtils.isEmpty(device.deviceName)) {
                    textView.setText(device.deviceAddress);
                } else {
                    textView.setText(device.deviceName);
                }
            }
        }

        private void setSinkViewVisible(boolean visible) {
            if (mWfdSinkView == null) {
                return;
            }
            if (DEBUG) Log.d(TAG, "setSinkViewVisible: " + visible);
            if (visible) {
                if (mWfdSinkView.getVisibility() != View.VISIBLE) {
                    updateDeviceName(mController.getWifiP2pDev());
                    mWfdSinkView.setVisibility(View.VISIBLE);
                }
            } else {
                mWfdSinkView.setVisibility(View.GONE);
            }
        }

        private void setSinkViewEnabled(boolean enabled) {
            if (mWfdSinkView == null) {
                return;
            }
            if (DEBUG) Log.d(TAG, "setSinkViewEnabled: " + enabled);
            setEnabledStateOnViews(mWfdSinkView, enabled);
        }

        private void setEnabledStateOnViews(View v, boolean enabled) {
            v.setEnabled(enabled);
            if (v instanceof ViewGroup) {
                final ViewGroup vg = (ViewGroup) v;
                for (int i = vg.getChildCount() - 1; i >= 0; i--) {
                    setEnabledStateOnViews(vg.getChildAt(i), enabled);
                }
            }
        }

        private void setSinkViewChecked(boolean checked) {
            if (mWfdSinkView == null) {
                return;
            }
            if (DEBUG) Log.d(TAG, "setSinkViewChecked: " + checked);
            CheckBox box = (CheckBox) mWfdSinkView.findViewById(com.android.internal.R.id.checkbox);
            box.setChecked(checked);
        }

        private void updateSinkView() {
            if (mWfdSinkView == null) {
                return;
            }
            if (DEBUG) Log.d(TAG, "updateSinkView summary");
            final TextView summary = (TextView) mWfdSinkView.
                findViewById(com.android.internal.R.id.summary);
            summary.post(new Runnable() {

                @Override
                public void run() {
                    summary.setText(R.string.wfd_sink_summary);
                }
                
            });
        }
        /// @}
    }
}
