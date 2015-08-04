package com.mediatek.systemui.ext;

import android.content.Context;
import android.util.Log;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.mediatek.systemui.statusbar.util.SIMHelper;

/**
 * M: Base ISignalClusterExt implements.
 */
public class BaseSignalClusterExt extends DefaultSignalClusterExt {

    private static final String TAG = "BaseSignalClusterExt";
    protected static final boolean DEBUG = true;

    protected boolean mWifiVisible = false;
    protected boolean[] mMobileVisible;
    protected boolean mIsAirplaneMode = false;
    protected boolean[] mRoaming;
    protected boolean[] mAdjustedLayout;

    protected ViewGroup[] mSignalClusterCombo;
    protected FrameLayout[] mNetworkDataTypeActivityCombo;
    protected ImageView[] mSignalNetworkTypesImageViews;

    protected IconIdWrapper[] mMobileSignalStrengthIconId;
    protected ImageView[] mMobileSignalStrength;

    protected IconIdWrapper[] mMobileDataTypeIconId;
    protected ImageView[] mMobileDataType;

    protected IconIdWrapper[] mMobileDataActivityIconId;
    protected ImageView[] mMobileDataActivity;

    protected IconIdWrapper[] mMobileSlotIndicatorIconId;
    protected ImageView[] mMobileSlotIndicator;

    protected boolean mIsMobileTypeIconWide = false;
    protected int mWideTypeIconStartPadding = 0;

    protected IconIdWrapper[] mMobileRoamingIndicatorIconId;
    protected ImageView[] mMobileRoamingIndicator;

    protected boolean[] mDataConnectioned;

    protected boolean[] mIsSimInserted;
    protected boolean[] mHasSimService;
    protected boolean[] mIsSimAvailable;

    protected int mSlotCount = 0;

    protected Context mContext;

    /**
     * Constructs a new BaseSignalClusterExt instance.
     *
     * @param context A Context object
     */
    public BaseSignalClusterExt(Context context) {
        mContext = context;

        mSlotCount = SIMHelper.getSlotCount();

        mMobileVisible = new boolean[mSlotCount];
        mRoaming = new boolean[mSlotCount];

        mAdjustedLayout = new boolean[mSlotCount];

        mSignalClusterCombo = new ViewGroup[mSlotCount];
        mNetworkDataTypeActivityCombo = new FrameLayout[mSlotCount];
        mSignalNetworkTypesImageViews = new ImageView[mSlotCount];

        mMobileSignalStrengthIconId = new IconIdWrapper[mSlotCount];
        mMobileSignalStrength = new ImageView[mSlotCount];

        mMobileDataTypeIconId = new IconIdWrapper[mSlotCount];
        mMobileDataType = new ImageView[mSlotCount];

        mMobileDataActivityIconId = new IconIdWrapper[mSlotCount];
        mMobileDataActivity = new ImageView[mSlotCount];

        mMobileSlotIndicatorIconId = new IconIdWrapper[mSlotCount];
        mMobileSlotIndicator = new ImageView[mSlotCount];

        mMobileRoamingIndicatorIconId = new IconIdWrapper[mSlotCount];
        mMobileRoamingIndicator = new ImageView[mSlotCount];

        mDataConnectioned = new boolean[mSlotCount];

        mIsSimInserted = new boolean[mSlotCount];
        mHasSimService = new boolean[mSlotCount];
        mIsSimAvailable = new boolean[mSlotCount];
    }

    @Override
    public void setWifiIndicatorsVisible(boolean visible) {
        mWifiVisible = visible;
    }

    @Override
    public void setMobileDataIndicators(int slotId, boolean visible, boolean roaming,
            boolean isMobileTypeIconWide, int wideTypeIconStartPadding,
            boolean hasSimService, boolean dataConnectioned,
            IconIdWrapper signalStrengthIcon, IconIdWrapper dataActivityIcon,
            IconIdWrapper dataTypeIcon) {
        if (slotId >= 0 && slotId < mSlotCount) {
            mMobileVisible[slotId] = visible;
            mRoaming[slotId] = roaming;
            mIsMobileTypeIconWide = isMobileTypeIconWide;
            mWideTypeIconStartPadding = wideTypeIconStartPadding;
            mHasSimService[slotId] = hasSimService;
            mDataConnectioned[slotId] = dataConnectioned;
            mMobileSignalStrengthIconId[slotId] = signalStrengthIcon;
            mMobileDataTypeIconId[slotId] = dataTypeIcon;
            mMobileDataActivityIconId[slotId] = dataActivityIcon;
        }
    }

    @Override
    public void setAirplaneMode(boolean airplaneMode) {
        mIsAirplaneMode = airplaneMode;
    }

    @Override
    public void onAttachedToWindow(ViewGroup[] signalClusterCombos,
            ImageView[] signalNetworkTypesImageViews, ViewGroup[] mobileViewGroups,
            ImageView[] mobileTypeImageViews, ImageView[] mMobileSignalStrengthImageViews) {
    }

    @Override
    public void onDetachedFromWindow() {
        if (DEBUG) {
            Log.d(TAG, "onDetachedFromWindow()");
        }

        for (int i = SIMHelper.SLOT_INDEX_DEFAULT; i < mSlotCount; i++) {
            // Roaming
            if (mMobileRoamingIndicator[i] != null) {
                final ViewParent parent = mMobileRoamingIndicator[i].getParent();
                if (parent != null) {
                    ((ViewGroup) parent).removeView(mMobileRoamingIndicator[i]);
                }
                mMobileRoamingIndicator[i] = null;
            }

            // Slot Indicator
            if (mMobileSlotIndicator[i] != null) {
                final ViewParent parent = mMobileSlotIndicator[i].getParent();
                if (parent != null) {
                    ((ViewGroup) parent).removeView(mMobileSlotIndicator[i]);
                }
                mMobileSlotIndicator[i] = null;
            }

            mMobileDataType[i] = null;
            mMobileSignalStrength[i] = null;

            mSignalClusterCombo[i] = null;
            mSignalNetworkTypesImageViews[i] = null;
        }
    }

    @Override
    public void onRtlPropertiesChanged(int layoutDirection) {
    }

    @Override
    public void apply() {
        if (DEBUG) {
            Log.d(TAG, "apply(), mWifiVisible is " + mWifiVisible
                    + ", mIsAirplaneMode is " + mIsAirplaneMode);
        }

        for (int i = 0; i < mSlotCount; i++) {
            // Sim available
            mIsSimInserted[i] = SIMHelper.isSimInsertedBySlot(mContext, i);
            mIsSimAvailable[i] = mIsSimInserted[i] && mHasSimService[i];

            if (DEBUG) {
                Log.d(TAG, "apply(), slot=" + i
                        + ", mMobileVisible=" + mMobileVisible[i]
                        + ", mRoaming=" + mRoaming[i]
                        + ", mIsSimInserted=" + mIsSimInserted[i]
                        + ", mHasSimService=" + mHasSimService[i]
                        + ", mIsSimAvailable=" + mIsSimAvailable[i]
                        + ", mDataConnectioned=" + mDataConnectioned[i]
                        + ", mSignalStrengthIconId = " + toString(mMobileSignalStrengthIconId[i])
                        + ", mDataTypeIconId = " + toString(mMobileDataTypeIconId[i])
                        + ", mDataActivityIconId = " + toString(mMobileDataActivityIconId[i]));
            }

            if (mMobileVisible[i] && !mIsAirplaneMode) {
                // Signal strength
                applyMobileSignalStrength(i);

                // Roaming.
                applyMobileRoamingIndicator(i);

                // Slot Indicator
                applyMobileSlotIndicator(i);
            }

            // data and network switch
            applyNetworkDataSwitch(i);

            // Data activity
            applyMobileDataActivity(i);
        }
    }

    protected void applyMobileSignalStrength(int slotId) {
    }

    protected void applyMobileSlotIndicator(int slotId) {
    }

    protected void applyMobileRoamingIndicator(int slotId) {
    }

    protected void applyNetworkDataSwitch(int slotId) {
    }

    protected void applyMobileDataActivity(int slotId) {
    }

    /**
     * Set IconIdWrapper to ImageView.
     *
     * @param imageView ImageView
     * @param icon IconIdWrapper
     */
    protected static final void setImage(final ImageView imageView, final IconIdWrapper icon) {
        if (imageView != null) {
            if (icon != null) {
                if (icon.getResources() != null) {
                    imageView.setImageDrawable(icon.getDrawable());
                } else {
                    if (icon.getIconId() == 0) {
                        imageView.setImageDrawable(null);
                    } else {
                        imageView.setImageResource(icon.getIconId());
                    }
                }
            }
        }
    }

    protected static final String toString(IconIdWrapper icon) {
        if (icon == null) {
            return "null";
        } else {
            if (icon.getResources() == null) {
                return "IconIdWrapper [mResources == null, mIconId=" + icon.getIconId() + "]";
            } else {
                return "IconIdWrapper [mResources != null, mIconId=" + icon.getIconId() + "]";
            }
        }
    }

    protected boolean isMultiSlot() {
        return mSlotCount > 1;
    }
}
