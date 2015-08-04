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

import android.content.Intent;
import android.content.Context;
import android.content.IntentFilter;
import android.content.BroadcastReceiver;

import com.android.systemui.R;
import com.android.systemui.qs.QSTile;

import com.mediatek.xlog.Xlog;
import com.mediatek.hotknot.HotKnotAdapter;
import com.mediatek.systemui.statusbar.policy.HotKnotController;

/** Quick settings tile: Cast **/
public class HotKnotTile extends QSTile<QSTile.BooleanState> {
    private static final String TAG = "HotKnotTile";
    private final HotKnotController mController;
    private boolean mListening;

    public HotKnotTile(Host host) {
        super(host);
        mController = host.getHotKnotController();
    }

    @Override
    protected BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    public void setListening(boolean listening) {
    if (mListening == listening) return;
        mListening = listening;
        if (listening) {
            final IntentFilter filter = new IntentFilter();
            filter.addAction(HotKnotAdapter.ACTION_ADAPTER_STATE_CHANGED);
            mContext.registerReceiver(mReceiver, filter);
        } else {
            mContext.unregisterReceiver(mReceiver);
        }
    }

    @Override
    protected void handleClick() {
        HotKnotAdapter adapter = mController.getAdapter();
        boolean desiredState = !mController.isHotKnotOn();
        Xlog.d(TAG, "hotknot desiredState=" + desiredState);
        if (desiredState) {
            adapter.enable();
        } else {
            adapter.disable();
        }
    }

    @Override
    protected void handleLongClick() {
        Intent intent = new Intent(HotKnotAdapter.ACTION_HOTKNOT_SETTINGS);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        mHost.startSettingsActivity(intent);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        state.label = mContext.getString(R.string.quick_settings_hotknot_label);
        state.visible = true;
        boolean desiredState;
        desiredState = mController.isHotKnotOn();
        Xlog.d(TAG, "HotKnot UpdateState desiredState=" + desiredState);
        if (desiredState)
            state.iconId =  R.drawable.ic_hotknot_qs_on;
        else
            state.iconId =  R.drawable.ic_hotknot_qs_off;
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (HotKnotAdapter.ACTION_ADAPTER_STATE_CHANGED.equals(intent.getAction())) {
                Xlog.d(TAG, "HotKnotAdapter onReceive DAPTER_STATE_CHANGED");
                refreshState();
            }
        }
    };
}
