package com.mediatek.systemui.qs.tiles;

import android.content.ComponentName;
import android.content.Intent;
import android.telephony.SubscriptionManager;

import com.android.internal.telephony.PhoneConstants;
import com.android.systemui.R;
import com.android.systemui.qs.QSTile;

/**
 * Customize the DataUsageTile.
 *
 */
public class DataUsageTile extends QSTile<QSTile.State> {
    private static final Intent CELLULAR_SETTINGS = new Intent().setComponent(new ComponentName(
            "com.android.settings", "com.android.settings.Settings$DataUsageSummaryActivity"));

    /**
     * Constructor.
     * @param host The QSTileHost.
     */
    public DataUsageTile(Host host) {
        super(host);
    }

    @Override
    protected State newTileState() {
        return new State();
    }

    @Override
    public void setListening(boolean listening) {
    }

    @Override
    protected void handleClick() {
        final long subId = SubscriptionManager.getDefaultDataSubId();
        CELLULAR_SETTINGS.putExtra(PhoneConstants.SUBSCRIPTION_KEY, subId);
        mHost.startSettingsActivity(CELLULAR_SETTINGS);
    }

    @Override
    protected void handleUpdateState(State state, Object arg) {
        state.visible = true;
        state.iconId = R.drawable.ic_qs_data_usage;
        state.label = mContext.getString(R.string.data_usage);
        state.contentDescription = mContext.getString(R.string.data_usage);
    }
}
