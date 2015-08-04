/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.systemui.statusbar;

import android.content.Context;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.widget.ImageView;
import android.widget.LinearLayout;

import com.android.systemui.R;
import com.android.systemui.statusbar.policy.NetworkControllerImpl;
import com.android.systemui.statusbar.policy.SecurityController;
import com.android.systemui.statusbar.policy.TelephonyIcons;

import com.mediatek.systemui.ext.ISignalClusterExt;
import com.mediatek.systemui.ext.IconIdWrapper;
import com.mediatek.systemui.ext.NetworkType;
import com.mediatek.systemui.ext.PluginFactory;
import com.mediatek.systemui.statusbar.util.SIMHelper;
import com.mediatek.xlog.Xlog;

// Intimately tied to the design of res/layout/signal_cluster_view.xml
/// M: [SystemUI] Support "dual SIM"
public class SignalClusterView
        extends LinearLayout
        implements NetworkControllerImpl.SignalCluster,
        SecurityController.SecurityControllerCallback {

    static final String TAG = "SignalClusterView";
    static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    NetworkControllerImpl mNC;
    SecurityController mSC;

    private boolean mVpnVisible = false;
    private boolean mWifiVisible = false;
    //private int mWifiStrengthId = 0; ///IconIdWrapper
    private String mWifiDescription;

    private boolean[] mMobileVisible;
    //private int[] mMobileStrengthId; ///IconIdWrapper
    //private int[] mMobileTypeId; ///IconIdWrapper
    private String[] mMobileDescription;
    private String[] mMobileTypeDescription;

    private boolean mIsAirplaneMode = false;
    private int mAirplaneIconId = 0;
    private boolean[] mIsMobileTypeIconWide;
    private boolean[] mRoaming;
    private ViewGroup[] mSignalClusterCombo;

    private ViewGroup[] mMobileGroup;
    private ImageView[] mMobile;
    private ImageView[] mMobileType;
    private View[] mSpacer;

    ViewGroup mWifiGroup;
    ImageView mVpn, mWifi, mAirplane;
    View mWifiAirplaneSpacer;
    View mWifiSignalSpacer;

    private int mWideTypeIconStartPadding;
    private int mSlotCount = 0;

    /// M: Support "Service Network Type on Statusbar"
    private NetworkType[] mNetworkType;
    private ImageView[] mSignalNetworkType;
    /// M: Support "SystemUI - VoLTE icon".
    private ImageView mVoLTEIcon;
    /// M: Support "Default SIM Indicator".
    private boolean[] mShowSimIndicator;
    private int[] mSimIndicatorResource;
    /// M: IconIdWrapper.
    private IconIdWrapper mWifiStrengthId = new IconIdWrapper();
    private IconIdWrapper[][] mMobileStrengthId;
    private IconIdWrapper[] mMobileTypeId;
    private int mMobileStrengthIdNum = 2;
    /// M: MobileActivityIcon
    private IconIdWrapper[] mMobileActivityId;

    /// M: Support "Operator plugin's ISignalClusterExt: Data activity/type, strength icon".
    private ISignalClusterExt mSignalClusterExt = null;

    public SignalClusterView(Context context) {
        this(context, null);
    }

    public SignalClusterView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public SignalClusterView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        mSlotCount = SIMHelper.getSlotCount();

        mMobileDescription = new String[mSlotCount];
        mMobileTypeDescription = new String[mSlotCount];
        mSignalClusterCombo = new ViewGroup[mSlotCount];
        mMobileGroup = new ViewGroup[mSlotCount];
        mMobile = new ImageView[mSlotCount];
        mMobileType = new ImageView[mSlotCount];
        mSpacer = new View[mSlotCount];
        //mMobileTypeId = new int[mSlotCount];
        //mMobileStrengthId = new int[mSlotCount];
        mMobileVisible = new boolean[mSlotCount];
        mRoaming = new boolean[mSlotCount];
        mIsMobileTypeIconWide = new boolean[mSlotCount];
        /// M: Support "Service Network Type on Statusbar"
        mSignalNetworkType = new ImageView[mSlotCount];
        mNetworkType = new NetworkType[mSlotCount];
        /// M: Support "Default SIM Indicator".
        mShowSimIndicator = new boolean[mSlotCount];
        mSimIndicatorResource = new int[mSlotCount];
        /// M: IconIdWrapper
        mMobileStrengthId = new IconIdWrapper[mSlotCount][];
        mMobileActivityId = new IconIdWrapper[mSlotCount];
        mMobileTypeId = new IconIdWrapper[mSlotCount];
        for (int i = 0; i < mSlotCount ; i++) {
            mMobileStrengthId[i] = new IconIdWrapper[mMobileStrengthIdNum];
            for (int j = 0 ; j < mMobileStrengthIdNum ; j++) {
                mMobileStrengthId[i][j] = new IconIdWrapper(0);
            }
            mMobileTypeId[i] = new IconIdWrapper(0);
            /// M: MobileActivityIcon
            mMobileActivityId[i] = new IconIdWrapper(0);
        }

        /// M: Support "Operator plugin's ISignalClusterExt: Data activity/type, strength icon".
        mSignalClusterExt = PluginFactory.getStatusBarPlugin(this.getContext())
                .customizeSignalCluster();
    }

    public void setNetworkController(NetworkControllerImpl nc) {
        if (DEBUG) Log.d(TAG, "NetworkController=" + nc);
        mNC = nc;
    }

    public void setSecurityController(SecurityController sc) {
        if (DEBUG) Log.d(TAG, "SecurityController=" + sc);
        mSC = sc;
        mSC.addCallback(this);
        mVpnVisible = mSC.isVpnEnabled();
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mWideTypeIconStartPadding = getContext().getResources().getDimensionPixelSize(
                R.dimen.wide_type_icon_start_padding);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        mVpn            = (ImageView) findViewById(R.id.vpn);
        mWifiAirplaneSpacer =         findViewById(R.id.wifi_airplane_spacer);
        mWifiSignalSpacer =           findViewById(R.id.wifi_signal_spacer);
        mWifiGroup                    = (ViewGroup) findViewById(R.id.wifi_combo);
        mWifi                         = (ImageView) findViewById(R.id.wifi_signal);
        mAirplane                     = (ImageView) findViewById(R.id.airplane);

        for (int i = SIMHelper.SLOT_INDEX_DEFAULT ; i < mSlotCount; i++) {
            final int k = i + 1;
            if (i == SIMHelper.SLOT_INDEX_DEFAULT) {
                // load views for first SIM card
                mMobile[i]                    = (ImageView) findViewById(R.id.mobile_signal);
                mMobileGroup[i]               = (ViewGroup) findViewById(R.id.mobile_combo);
                mMobileType[i]                = (ImageView) findViewById(R.id.mobile_type);
                mSpacer[i]                    =             findViewById(R.id.spacer);
                mSignalClusterCombo[i]        = (ViewGroup) findViewById(R.id.signal_cluster_combo);
                /// M: Support "Service Network Type on Statusbar"
                mSignalNetworkType[i]         = (ImageView) findViewById(R.id.network_type);
            } else {
                mMobile[i]                    = (ImageView) findViewWithTag("mobile_signal_" + k);
                mMobileGroup[i]               = (ViewGroup) findViewWithTag("mobile_combo_" + k);
                mMobileType[i]                = (ImageView) findViewWithTag("mobile_type_" + k);
                mSpacer[i]                    =             findViewWithTag("spacer_" + k);
                mSignalClusterCombo[i]        = (ViewGroup) findViewWithTag("signal_cluster_combo_" + k);
                /// M: Support "Service Network Type on Statusbar"
                mSignalNetworkType[i]         = (ImageView) findViewWithTag("network_type_" + k);
            }
        }
        /// M: Support "SystemUI - VoLTE icon". @{
        mVoLTEIcon                    = (ImageView) findViewById(R.id.volte_icon);
        mVoLTEIcon.setVisibility(View.GONE);
        /// M: Support "SystemUI - VoLTE icon". @}

        /// M: Support "Operator plugin's ISignalClusterExt: Data activity/type, strength icon". @{
        mSignalClusterExt.onAttachedToWindow(mSignalClusterCombo, mSignalNetworkType, mMobileGroup,
                mMobileType, mMobile);
        /// M: Support "Operator plugin's ISignalClusterExt: Data activity/type, strength icon". @}

        apply();
    }

    @Override
    protected void onDetachedFromWindow() {
        mVpn            = null;
        mWifiGroup      = null;
        mWifi           = null;

        for (int i = SIMHelper.SLOT_INDEX_DEFAULT; i < mSlotCount ; i++) {
            mMobileGroup[i]          = null;
            mMobile[i]               = null;
            mMobileType[i]           = null;
            mSpacer[i]               = null;
        }

        /// M: Support "Operator plugin's ISignalClusterExt: Data activity/type, strength icon". @{
        mSignalClusterExt.onDetachedFromWindow();
        /// M: Support "Operator plugin's ISignalClusterExt: Data activity/type, strength icon". @}

        super.onDetachedFromWindow();
    }

    // From SecurityController.
    @Override
    public void onStateChanged() {
        post(new Runnable() {
            @Override
            public void run() {
                mVpnVisible = mSC.isVpnEnabled();
                apply();
            }
        });
    }

    @Override
    public void setWifiIndicators(boolean visible, IconIdWrapper strengthIcon,
            String contentDescription) {
        Xlog.d(TAG, "setWifiIndicators, visible=" + visible
            + ", strengthIcon=" + strengthIcon.getIconId()
            + ", contentDescription=" + contentDescription);

        mWifiVisible = visible;
        mWifiStrengthId = strengthIcon.clone();
        mWifiDescription = contentDescription;

        /// M: Support "Operator plugin's ISignalClusterExt: Data activity/type, strength icon". @{
        mSignalClusterExt.setWifiIndicatorsVisible(visible);
        /// M: Support "Operator plugin's ISignalClusterExt: Data activity/type, strength icon". @}

        //apply();
    }

    @Override
    public void setMobileDataIndicators(int slotId, boolean visible,
            IconIdWrapper[] strengthIcon, IconIdWrapper activityIcon, IconIdWrapper typeIcon,
            String contentDescription, String typeContentDescription, boolean roaming,
            boolean isTypeIconWide) {
        if (slotId >= 0 && slotId < mSlotCount) {
            Xlog.d(TAG, "setMobileDataIndicators(" + slotId + "), visible=" + visible 
                + ", strengthIcon[0] ~ [1] "
                + strengthIcon[0].getIconId() + " ~ " + strengthIcon[1].getIconId()
                + ", mMobileTypeId: " + typeIcon.getIconId()
                + ", isTypeIconWide: " + isTypeIconWide);
            mMobileVisible[slotId] = visible;
            mMobileStrengthId[slotId][0] = strengthIcon[0].clone();
            mMobileStrengthId[slotId][1] = strengthIcon[1].clone();
            /// M: MobileActivityIcon
            mMobileActivityId[slotId] = activityIcon.clone();
            mMobileTypeId[slotId] = typeIcon.clone();

            //mMobileStrengthId[slotId] = strengthIcon;
            //mMobileTypeId[slotId] = typeIcon;
            mMobileDescription[slotId] = contentDescription;
            mMobileTypeDescription[slotId] = typeContentDescription;
            mRoaming[slotId] = roaming;
            mIsMobileTypeIconWide[slotId] = isTypeIconWide;
        }

        /// M: Support "Operator plugin's ISignalClusterExt: Data activity/type, strength icon". @{
        boolean isDataConnected = false;
        boolean hasSimService = false;
        if (mNC != null) {
            isDataConnected = mNC.isDataConnected(slotId);
            hasSimService = mNC.hasSimService(slotId);
        }
        mSignalClusterExt.setMobileDataIndicators(slotId, visible, roaming, isTypeIconWide,
                mWideTypeIconStartPadding, hasSimService, isDataConnected,
                mMobileStrengthId[slotId][0], mMobileActivityId[slotId],
                mMobileTypeId[slotId]);
        /// M: Support "Operator plugin's ISignalClusterExt: Data activity/type, strength icon". @}

        //apply();
    }

    @Override
    public void setIsAirplaneMode(boolean is, int airplaneIconId) {
        Xlog.d(TAG, "setIsAirplaneMode=" + is
               + " airplaneIconId:" + airplaneIconId);
        mIsAirplaneMode = is;
        mAirplaneIconId = airplaneIconId;

        /// M: Support "Operator plugin's ISignalClusterExt: Data activity/type, strength icon". @{
        mSignalClusterExt.setAirplaneMode(is);
        /// M: Support "Operator plugin's ISignalClusterExt: Data activity/type, strength icon". @}

        //apply();
    }

    @Override
    public boolean dispatchPopulateAccessibilityEvent(AccessibilityEvent event) {
        // Standard group layout onPopulateAccessibilityEvent() implementations
        // ignore content description, so populate manually
        if (mWifiVisible && mWifiGroup != null && mWifiGroup.getContentDescription() != null) {
            event.getText().add(mWifiGroup.getContentDescription());
        }
        for (int i = 0; i < mSlotCount; i++) {
            if (mMobileVisible[i] && mMobileGroup[i] != null
                && mMobileGroup[i].getContentDescription() != null) {
                event.getText().add(mMobileGroup[i].getContentDescription());
            }
        }
        return super.dispatchPopulateAccessibilityEvent(event);
    }

    @Override
    public void onRtlPropertiesChanged(int layoutDirection) {
        super.onRtlPropertiesChanged(layoutDirection);

        /// M: Support "Operator plugin's ISignalClusterExt: Data activity/type, strength icon". @{
        mSignalClusterExt.onRtlPropertiesChanged(layoutDirection);
        /// M: Support "Operator plugin's ISignalClusterExt: Data activity/type, strength icon". @}

        apply();
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    // Run after each indicator change.
    public void apply() {
        if (mWifiGroup == null) {
            Xlog.d(TAG, "apply(), mWifiGroup is null, return");
            return;
        }
        Xlog.d(TAG, "apply(), mWifiVisible is " + mWifiVisible);

        mVpn.setVisibility(mVpnVisible ? View.VISIBLE : View.GONE);
        if (DEBUG) Log.d(TAG, String.format("vpn: %s", mVpnVisible ? "VISIBLE" : "GONE"));
        if (mWifiVisible) {
            /// M: IconIdWrapper
            if (mWifiStrengthId.getResources() != null) {
                mWifi.setImageDrawable(mWifiStrengthId.getDrawable());
            } else {
                mWifi.setImageResource(mWifiStrengthId.getIconId());
            }
            mWifiGroup.setContentDescription(mWifiDescription);
            mWifiGroup.setVisibility(View.VISIBLE);
        } else {
            mWifiGroup.setVisibility(View.GONE);
        }

        if (DEBUG) Log.d(TAG,
                String.format("wifi: %s sig=%d",
                    (mWifiVisible ? "VISIBLE" : "GONE"),
                    mWifiStrengthId.getIconId()));

        int noSIMinserted = 0;
        for (int i = 0; i < mSlotCount; i++) {
            Log.d(TAG, "apply(), slot=" + i + ", mMobileVisible=" + mMobileVisible[i]
                  + ", mIsAirplaneMode=" + mIsAirplaneMode);
            if (mMobileVisible[i] && !mIsAirplaneMode) {
                if (!SIMHelper.isSimInsertedBySlot(mContext, i))
                {
                    noSIMinserted ++;
                    mSignalClusterCombo[i].setVisibility(View.GONE);
                    continue;
                }
                mSignalClusterCombo[i].setVisibility(View.VISIBLE);

                /// M: Support "Service Network Type on Statusbar". @{
                if (!mIsAirplaneMode && mNetworkType[i] != null) {
                        IconIdWrapper resId = new IconIdWrapper(0);
                        int id = TelephonyIcons.getNetworkTypeIcon(mNetworkType[i]);
                    	// 5037 need not show G 3G yuanluo 
                        //id = 0;
                        resId.setIconId(id);

                        /// M: Support "Operator plugin - Data icon". @{
                        PluginFactory.getStatusBarPlugin(mContext)
                                .customizeDataNetworkTypeIcon(resId, mNetworkType[i]);
                        /// M: Support "Operator plugin - Data icon". @}

                        if (resId.getResources() != null) {
                            mSignalNetworkType[i].setImageDrawable(resId.getDrawable());
                        } else {
                            if (resId.getIconId() == 0) {
                                mSignalNetworkType[i].setImageDrawable(null);
                            } else {
                                mSignalNetworkType[i].setImageResource(resId.getIconId());
                            }
                        }
                        mSignalNetworkType[i].setVisibility(View.VISIBLE);
                        Xlog.d(TAG, "apply(), slot=" + i + ", mNetworkType=" + mNetworkType[i] 
                              + " resId= " + resId.getIconId());
                } else {
                    mSignalNetworkType[i].setImageDrawable(null);
                    mSignalNetworkType[i].setVisibility(View.GONE);
                }
                /// M: Support "Service Network Type on Statusbar". @}

                if (mMobileVisible[i] && !mIsAirplaneMode) {
                    //mMobile[i].setImageResource(mMobileStrengthId[i]);
                    //mMobileType[i].setImageResource(mMobileTypeId[i]);
                    mMobileType[i].setVisibility((mRoaming[i] || mMobileTypeId[i].getIconId() != 0) ? View.VISIBLE : View.GONE);
                    mMobileGroup[i].setContentDescription(mMobileTypeDescription[i] + " " + mMobileDescription[i]);
                    mMobileGroup[i].setVisibility(View.VISIBLE);
                    mMobile[i].setPaddingRelative(mIsMobileTypeIconWide[i] ?
                               mWideTypeIconStartPadding : 0, 0, 0, 0);
                    /// M: IconIdWrapper
                    if (mMobileStrengthId[i][0].getResources() != null) {
                        mMobile[i].setImageDrawable(mMobileStrengthId[i][0].getDrawable());
                    } else {
                        if (mMobileStrengthId[i][0].getIconId() == 0) {
                            mMobile[i].setImageDrawable(null);
                        } else {
                            mMobile[i].setImageResource(mMobileStrengthId[i][0].getIconId());
                        }
                    }
                    if (mMobileTypeId[i].getResources() != null) {
                        mMobileType[i].setImageDrawable(mMobileTypeId[i].getDrawable());
                    } else {
                        if (mMobileTypeId[i].getIconId() == 0) {
                            mMobileType[i].setImageDrawable(null);
                        } else {
                            mMobileType[i].setImageResource(mMobileTypeId[i].getIconId());
                        }
                    }
                } else {
                    mMobileGroup[i].setVisibility(View.GONE);
                }

                /// M: Support "Default SIM Indicator". @{
                if (mShowSimIndicator[i]) {
                    mSignalClusterCombo[i].setBackgroundResource(mSimIndicatorResource[i]);
                } else {
                    mSignalClusterCombo[i].setBackgroundDrawable(null);
                }
                /// M: Support "Default SIM Indicator". @}

                if (DEBUG) Log.d(TAG, "apply(), slot=" + i + ", "
                        + String.format("mobile: %s sig=%d typ=%d ",
                            (mMobileVisible[i] ? "VISIBLE" : "GONE")
                            , mMobileStrengthId[i], mMobileTypeId[i])
                        + " mIsAirplaneMode is " + mIsAirplaneMode);

                if (getNullIconIdGemini(i) == mMobileStrengthId[i][0].getIconId()
                    || mMobileStrengthId[i][0].getIconId() == R.drawable.stat_sys_signal_null) {
                    mMobileType[i].setVisibility(View.GONE);
                }
                Xlog.d(TAG, "apply(), slot=" + i + ", "
                        + " mIsAirplaneMode is " + mIsAirplaneMode
                        + ", mRoaming=" + mRoaming[i]
                        + " mMobileActivityId=" + mMobileActivityId[i].getIconId()
                        + " mMobileTypeId=" + mMobileTypeId[i].getIconId()
                        + " mMobileTypeRes=" + mMobileTypeId[i].getResources()
                        + " mMobileVisible=" + mMobileType[i].getVisibility()
                        + " mMobileStrengthId[0] = " + mMobileStrengthId[i][0].getIconId()
                        + " mMobileStrengthId[1] = " + mMobileStrengthId[i][1].getIconId());
            } else {
                mSignalClusterCombo[i].setVisibility(View.GONE);
            }
        }

        /// M: Show empty signal icon only when NO Sim is inserted. @{
        if (noSIMinserted == mSlotCount) {
            Log.d(TAG, "No SIM inserted: Show one empty signal icon only :" + mSlotCount);
            mSignalClusterCombo[0].setVisibility(View.VISIBLE);
            mMobile[0].setImageResource(R.drawable.stat_sys_signal_null);
            mMobileType[0].setVisibility(View.GONE);
            mMobileGroup[0].setVisibility(View.VISIBLE);
            mSignalNetworkType[0].setImageDrawable(null);
            mSignalNetworkType[0].setVisibility(View.GONE);
        }
        /// M: Show empty signal icon only when NO Sim is inserted. @}

        if (mIsAirplaneMode) {
            mAirplane.setImageResource(mAirplaneIconId);
            mAirplane.setVisibility(View.VISIBLE);
        } else {
            mAirplane.setVisibility(View.GONE);
        }

        if (mIsAirplaneMode && mWifiVisible) {
            mWifiAirplaneSpacer.setVisibility(View.VISIBLE);
        } else {
            mWifiAirplaneSpacer.setVisibility(View.GONE);
        }

        if (mWifiVisible) {
            mSpacer[0].setVisibility(View.INVISIBLE);
        } else {
            mSpacer[0].setVisibility(View.GONE);
        }

        /// M: Support "Operator plugin's ISignalClusterExt: Data activity/type, strength icon". @{
        mSignalClusterExt.apply();
        /// M: Support "Operator plugin's ISignalClusterExt: Data activity/type, strength icon". @}
    }

    /// M: Support "Default SIM Indicator". @{
    public void setShowSimIndicator(int slotId, boolean showSimIndicator, int simIndicatorResource) {
        Xlog.d(TAG, "setShowSimIndicator(" + slotId + "), showSimIndicator=" + showSimIndicator
                + " simIndicatorResource = " + simIndicatorResource);
        mShowSimIndicator[slotId] = showSimIndicator;
        mSimIndicatorResource[slotId] = simIndicatorResource;
    }
    /// M: Support "Default SIM Indicator". @}

    /// M: Support "Service Network Type on Statusbar". @{
    public void setNetworkType(int slotId, NetworkType mType) {
        Xlog.d(TAG, "setNetworkType(" + slotId + "), NetworkType=" + mType);
        mNetworkType[slotId] = mType;
    }
    /// M: Support "Service Network Type on Statusbar". @}

    /// M: Support "SystemUI - VoLTE icon". @{
    public void setVoLTE(boolean mShow)
    {
        if (mShow == true) {
            // VoLTE is on
            Xlog.d(TAG, "set VoLTE Icon on");
            mVoLTEIcon.setBackgroundResource(R.drawable.stat_sys_volte);
            mVoLTEIcon.setVisibility(View.VISIBLE);
        } else {
            // VoLTE is off
            Xlog.d(TAG, "set VoLTE Icon off");
            mVoLTEIcon.setVisibility(View.GONE);
        }
    }
    /// M: Support "SystemUI - VoLTE icon". @}

    /// M: IconIdWrapper
    private int getNullIconIdGemini(int slotId) {
        IconIdWrapper tempIconIdWrapper = new IconIdWrapper();
        PluginFactory.getStatusBarPlugin(mContext)
                .customizeSignalStrengthNullIcon(slotId, tempIconIdWrapper);
        return tempIconIdWrapper.getIconId();
    }
}

