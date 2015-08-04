/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.keyguard;

import android.app.AlarmManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.os.Handler;
import android.provider.AlarmClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Slog;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridLayout;
import android.widget.TextClock;
import android.widget.TextView;

import com.android.internal.widget.LockPatternUtils;

import java.text.SimpleDateFormat;
import java.util.Locale;

import com.mediatek.keyguard.ext.ICustomizeClock;
import com.mediatek.keyguard.ext.KeyguardPluginFactory;
import com.mediatek.keyguard.Clock.ClockView ;

public class KeyguardStatusView extends GridLayout {
    private static final boolean DEBUG = KeyguardConstants.DEBUG;
    private static final String TAG = "KeyguardStatusView";

    private LockPatternUtils mLockPatternUtils;

    private TextView mAlarmStatusView;
    private TextClock mDateView;
    private ClockView mClockView;
    /// M: For customize clock
    private ICustomizeClock mCustomizeClock;
    private TextView mOwnerInfo;
    
    private final Handler mHandler;
    private final DateFormatObserver mDateFormatObserver;


    private KeyguardUpdateMonitorCallback mInfoCallback = new KeyguardUpdateMonitorCallback() {

        @Override
        public void onTimeChanged() {
            refresh();
        }

        @Override
        public void onKeyguardVisibilityChanged(boolean showing) {
            if (showing) {
                if (DEBUG) Slog.v(TAG, "refresh statusview showing:" + showing);
                refresh();
                updateOwnerInfo();
            }
        }

        @Override
        public void onScreenTurnedOn() {
            setEnableMarquee(true);
        }

        @Override
        public void onScreenTurnedOff(int why) {
            setEnableMarquee(false);
        }

        @Override
        public void onUserSwitchComplete(int userId) {
            refresh();
            updateOwnerInfo();
        }
    };

    public KeyguardStatusView(Context context) {
        this(context, null, 0);
    }

    public KeyguardStatusView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public KeyguardStatusView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        /// M: Init customize clock plugin
        mCustomizeClock = KeyguardPluginFactory.getCustomizeClock(mContext);
        
        mHandler = new Handler();
        mDateFormatObserver = new DateFormatObserver(mHandler);
        mDateFormatObserver.startObserving();

    }

    private void setEnableMarquee(boolean enabled) {
        if (DEBUG) Log.v(TAG, (enabled ? "Enable" : "Disable") + " transport text marquee");
        if (mAlarmStatusView != null) mAlarmStatusView.setSelected(enabled);
        if (mOwnerInfo != null) mOwnerInfo.setSelected(enabled);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        Log.d(TAG, "KeyguardStatus view fix in it");

        mCustomizeClock.addCustomizeClock(getContext(), (ViewGroup) findViewById(R.id.clock_container), (ViewGroup) findViewById(R.id.keyguard_status_area_id));

        mAlarmStatusView = (TextView) findViewById(R.id.alarm_status);
        mDateView = (TextClock) findViewById(R.id.date_view);
        mClockView = (ClockView) findViewById(R.id.clock_view);
        mOwnerInfo = (TextView) findViewById(R.id.owner_info);
        mLockPatternUtils = new LockPatternUtils(getContext());
        final boolean screenOn = KeyguardUpdateMonitor.getInstance(mContext).isScreenOn();
        setEnableMarquee(screenOn);

        /// M: For loading the mediatek resource and avoid changing a lot. @{
        if (mDateView == null) {
            if (DEBUG) Log.d(TAG, "onFinishInflate mDateView == null");
            mDateView = (TextClock) findViewById(R.id.date_view);
        }
        if (mAlarmStatusView == null) {
            if (DEBUG) Log.d(TAG, "onFinishInflate mAlarmStatusView == null");
            mAlarmStatusView = (TextView) findViewById(R.id.alarm_status);
        }
        if (mClockView == null) {
            if (DEBUG) Log.d(TAG, "onFinishInflate mClockView == null");
            mClockView = (ClockView) findViewById(R.id.clock_view);
        }
        /// @}

        if (DEBUG) Log.d(TAG, "onFinishInflate --before-- new LockPatternUtils(getContext())");
        mLockPatternUtils = new LockPatternUtils(getContext());

        if (DEBUG) Log.d(TAG, "onFinishInflate --before-- refresh()");
        refresh();
        updateOwnerInfo();

        // Disable elegant text height because our fancy colon makes the ymin value huge for no
        // reason.
        //mClockView.setElegantTextHeight(false);
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        //mClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
        //        getResources().getDimensionPixelSize(R.dimen.widget_big_font_size));
        mDateView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.widget_label_font_size));
        mOwnerInfo.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.widget_label_font_size));
    }

    public void refreshTime() {
        if (DEBUG) Log.d(TAG, "refresh mClockView.updateTime()");
		String dateFormat = Settings.System.getString(mContext.getContentResolver(), Settings.System.DATE_FORMAT);
		String tempFormat = "MM/dd/yyyy";
        final Locale l = Locale.getDefault();

        	if (dateFormat != null && !dateFormat.equals("")) {
    		if (dateFormat.equals("MM-dd-yyyy") || dateFormat.equals("dd-MM-yyyy") || dateFormat.equals("yyyy-MM-dd")) {
    			tempFormat = dateFormat.replaceAll("-", "/");
    		} else if (dateFormat.equals("EE-MMM-d-yyyy")) {
    			tempFormat = "EE, MMM d, yyyy";
    		} else if (dateFormat.equals("EE-d-MMM-yyyy")) {
    			tempFormat = "EE, d MMM yyyy";
    		} else if (dateFormat.equals("yyyy-MMM-d-EE")) {
    			tempFormat = "yyyy MMM d, EE";
    		} else{
			  tempFormat = dateFormat;
			} 
    	}
		
        mDateView.setFormat24Hour(tempFormat);
        mDateView.setFormat12Hour(tempFormat);

        if (null != mClockView) {
            mClockView.updateTime();
        }
    }

    private void refresh() {
        AlarmManager.AlarmClockInfo nextAlarm = mLockPatternUtils.getNextAlarm();
        Patterns.update(mContext, nextAlarm != null);

        refreshTime();
        refreshAlarmStatus(nextAlarm);
    }

    void refreshAlarmStatus(AlarmManager.AlarmClockInfo nextAlarm) {
        if (nextAlarm != null) {
            String alarm = formatNextAlarm(mContext, nextAlarm);
            mAlarmStatusView.setText(alarm);
            mAlarmStatusView.setContentDescription(
                    getResources().getString(R.string.keyguard_accessibility_next_alarm, alarm));
            mAlarmStatusView.setVisibility(View.VISIBLE);
        } else {
            mAlarmStatusView.setVisibility(View.GONE);
        }
    }

    public static String formatNextAlarm(Context context, AlarmManager.AlarmClockInfo info) {
        if (info == null) {
            return "";
        }
        String skeleton = DateFormat.is24HourFormat(context) ? "EHm" : "Ehma";
        String pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), skeleton);
        return DateFormat.format(pattern, info.getTriggerTime()).toString();
    }

    private void updateOwnerInfo() {
        if (mOwnerInfo == null) return;
        String ownerInfo = getOwnerInfo();
        if (!TextUtils.isEmpty(ownerInfo)) {
            mOwnerInfo.setVisibility(View.VISIBLE);
            mOwnerInfo.setText(ownerInfo);
        } else {
            mOwnerInfo.setVisibility(View.GONE);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        KeyguardUpdateMonitor.getInstance(mContext).registerCallback(mInfoCallback);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        KeyguardUpdateMonitor.getInstance(mContext).removeCallback(mInfoCallback);
        /// M: reset the customize clock register and etc.
        mCustomizeClock.reset();
    }

    public int getAppWidgetId() {
        return LockPatternUtils.ID_DEFAULT_STATUS_WIDGET;
    }

    private String getOwnerInfo() {
        ContentResolver res = getContext().getContentResolver();
        String info = null;
        final boolean ownerInfoEnabled = mLockPatternUtils.isOwnerInfoEnabled();
        if (ownerInfoEnabled) {
            info = mLockPatternUtils.getOwnerInfo(mLockPatternUtils.getCurrentUser());
        }
        return info;
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    // DateFormat.getBestDateTimePattern is extremely expensive, and refresh is called often.
    // This is an optimization to ensure we only recompute the patterns when the inputs change.
    private static final class Patterns {
        static String dateView;
        static String clockView12;
        static String clockView24;
        static String cacheKey;

        static void update(Context context, boolean hasAlarm) {
            final Locale locale = Locale.getDefault();
            final Resources res = context.getResources();
            final String dateViewSkel = res.getString(hasAlarm
                    ? R.string.abbrev_wday_month_day_no_year_alarm
                    : R.string.abbrev_wday_month_day_no_year);
            final String clockView12Skel = res.getString(R.string.clock_12hr_format);
            final String clockView24Skel = res.getString(R.string.clock_24hr_format);
            final String key = locale.toString() + dateViewSkel + clockView12Skel + clockView24Skel;
            if (key.equals(cacheKey)) return;

            dateView = DateFormat.getBestDateTimePattern(locale, dateViewSkel);

            clockView12 = DateFormat.getBestDateTimePattern(locale, clockView12Skel);
            // CLDR insists on adding an AM/PM indicator even though it wasn't in the skeleton
            // format.  The following code removes the AM/PM indicator if we didn't want it.
            if (!clockView12Skel.contains("a")) {
                clockView12 = clockView12.replaceAll("a", "").trim();
            }

            clockView24 = DateFormat.getBestDateTimePattern(locale, clockView24Skel);

            // Use fancy colon.
            clockView24 = clockView24.replace(':', '\uee01');
            clockView12 = clockView12.replace(':', '\uee01');
        }
    }

    /*
     * M: For CR ALPS00333114
     *
     * We need update updateStatusLines when dialog dismiss
     * which is in font of lock screen.
     *
     * @see android.view.View#onWindowFocusChanged(boolean)
     */
    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (hasWindowFocus) {
            refresh();
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        mCustomizeClock.updateClockLayout();
    }
    
    private class DateFormatObserver extends ContentObserver {
    	
    	public DateFormatObserver(Handler handler) {
    		super(handler);
    	}
    	@Override
    	public void onChange(boolean selfChange) {
    		// TODO Auto-generated method stub
    		super.onChange(selfChange);
    		refreshTime();
    	}
    	
    	public void stopObserving() {
    		ContentResolver cr = mContext.getContentResolver();
    		cr.unregisterContentObserver(this);
    	}
    	
    	public void startObserving() {
    		final ContentResolver cr = mContext.getContentResolver();
    		cr.registerContentObserver(Settings.System.getUriFor(Settings.System.DATE_FORMAT), true, this);
    	}
    }

}
