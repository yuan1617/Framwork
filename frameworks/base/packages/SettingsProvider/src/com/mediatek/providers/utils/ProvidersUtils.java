package com.mediatek.providers.utils;

import android.content.Context;
import android.content.res.Resources;
import android.database.sqlite.SQLiteStatement;
import android.util.Log;
import android.os.SystemProperties;
import android.provider.Settings;
import com.android.providers.settings.R;

import com.mediatek.audioprofile.AudioProfileManager;
import com.mediatek.common.MPlugin;
import com.mediatek.providers.settings.ext.DefaultDatabaseHelperExt;
import com.mediatek.providers.settings.ext.IDatabaseHelperExt;

public class ProvidersUtils {
    private static final String TAG = "ProvidersUtils";
    private IDatabaseHelperExt mExt;
    private Context mContext;
    private Resources mRes;

    public ProvidersUtils(Context context) {
        mContext = context;
        mRes = mContext.getResources();
        initDatabaseHelperPlgin(mContext);
    }

    private void initDatabaseHelperPlgin(Context context) {
        mExt = (IDatabaseHelperExt) MPlugin.createInstance(
                     IDatabaseHelperExt.class.getName(), context);
        if (mExt == null) {
            mExt = new DefaultDatabaseHelperExt(context);
        }
    }

    public void loadCustomSystemSettings(SQLiteStatement stmt) {
        // M: Add for GENERAL profile as the default profile.
        loadStringSetting(stmt, AudioProfileManager.KEY_ACTIVE_PROFILE,
                R.string.def_active_profile);

        // M: Add for gemini default value
        if (SystemProperties.get("ro.mtk_gemini_support").equals("1")) {
            loadIntegerSetting(stmt, Settings.System.MSIM_MODE_SETTING,
                    R.integer.def_dual_sim_mode);
        } else {
            loadIntegerSetting(stmt, Settings.System.MSIM_MODE_SETTING,
                    R.integer.def_single_sim_mode);
        }

        // M: Add for dual sim card
        loadIntegerSetting(stmt, Settings.System.BOOT_UP_SELECT_MODE,
                R.integer.boot_up_select_mode);

        // M: Add for internet call default value
        loadSetting(stmt, Settings.System.ENABLE_INTERNET_CALL, 0);
        loadSetting(stmt, Settings.System.ROAMING_REMINDER_MODE_SETTING, 1);
        loadBooleanSetting(stmt, Settings.System.ROAMING_INDICATION_NEEDED,
                R.bool.def_roaming_indicate_needed);

        // M: Add for IPO
        loadBooleanSetting(stmt, Settings.System.IPO_SETTING,
                R.bool.def_ipo_setting);

        loadStringSetting(stmt, Settings.System.LANDSCAPE_LAUNCHER,
                R.string.def_landscape_launcher);

        // M: Add for reselect among SSID
        loadIntegerSetting(stmt, Settings.System.WIFI_SELECT_SSID_TYPE,
                R.integer.wifi_select_ssid_type);

        // M: Add for Streaming
        loadStringSetting(stmt, Settings.System.MTK_RTSP_NAME,
                R.string.mtk_rtsp_name);
        loadStringSetting(stmt, Settings.System.MTK_RTSP_TO_PROXY,
                R.string.mtk_rtsp_to_proxy);
        loadStringSetting(stmt, Settings.System.MTK_RTSP_NETINFO,
                R.string.mtk_rtsp_netinfo);
        loadStringSetting(stmt, Settings.System.MTK_RTSP_TO_NAPID,
                R.string.mtk_rtsp_to_napid);
        loadStringSetting(stmt, Settings.System.MTK_RTSP_MAX_UDP_PORT,
                R.string.mtk_rtsp_max_udp_port);
        loadStringSetting(stmt, Settings.System.MTK_RTSP_MIN_UDP_PORT,
                R.string.mtk_rtsp_min_udp_port);

        // M: Add for voice call reject mode
        loadIntegerSetting(stmt, Settings.System.VOICE_CALL_REJECT_MODE,
                R.integer.def_voice_call_reject_mode);
        // M: Add for video call reject mode
        loadIntegerSetting(stmt, Settings.System.VT_CALL_REJECT_MODE,
                R.integer.def_video_call_reject_mode);

        loadIntegerSetting(stmt, Settings.System.IVSR_SETTING,
                R.integer.def_ivsr_setting);

        loadIntegerSetting(stmt, Settings.System.CRO_SETTING,
                R.integer.def_cro_setting); // ALPS00279048

        loadIntegerSetting(stmt, Settings.System.HOO_SETTING,
                R.integer.def_hoo_setting); // ALPS00310187

        // M: Add for ipv6 tethering feature
        loadIntegerSetting(stmt, Settings.System.TETHER_IPV6_FEATURE,
                R.integer.def_tether_ipv6_feature);

        // M: Add for CT main sim selection settings
        loadIntegerSetting(stmt, Settings.System.CT_MAIN_SIM_SELECTION,
                R.integer.def_ct_main_sim_selection);

        // M: Add for CT time display mode
        loadIntegerSetting(stmt, Settings.System.CT_TIME_DISPLAY_MODE,
                R.integer.def_ct_time_display_mode);

        // M: Add for sSurding dialing:
        loadBooleanSetting(stmt, Settings.System.ESURFING_DIALING,
                R.bool.def_eSurfing_dialing);

        // M: Add for international dialing:
        loadIntegerSetting(stmt, Settings.System.INTER_DIAL_SETTING,
                R.integer.def_international_dialing);

        loadSetting(stmt, Settings.System.DTMF_TONE_WHEN_DIALING, 1);
        loadSetting(stmt, Settings.System.GPRS_CONNECTION_SETTING,
                Settings.System.GPRS_CONNECTION_SETTING_DEFAULT);

        // M: Add for HDMI
        loadIntegerSetting(stmt, Settings.System.HDMI_ENABLE_STATUS,
                R.integer.def_hdmi_enable_status);
        loadIntegerSetting(stmt, Settings.System.HDMI_VIDEO_RESOLUTION,
                R.integer.def_hdmi_video_resolution);
        loadIntegerSetting(stmt, Settings.System.HDMI_VIDEO_SCALE,
                R.integer.def_hdmi_video_scale);
        loadIntegerSetting(stmt, Settings.System.HDMI_COLOR_SPACE,
                R.integer.def_hdmi_color_space);
        loadIntegerSetting(stmt, Settings.System.HDMI_DEEP_COLOR,
                R.integer.def_hdmi_deep_color);
        loadIntegerSetting(stmt, Settings.System.HDMI_CABLE_PLUGGED,
                R.integer.def_hdmi_cable_plugged);

        // M: Enable/disable ANR mechanism from adb command
        loadSetting(stmt, Settings.System.ANR_DEBUGGING_MECHANISM, 2);
        loadSetting(stmt, Settings.System.ANR_DEBUGGING_MECHANISM_STATUS, 0);

        // M: Pointer primary key for smart book
        loadBooleanSetting(stmt, Settings.System.CHANGE_POINTER_PRIMARY_KEY,
                R.bool.def_change_pointer_primary_key);

        // M: Pointer double click speed for smart book
        loadIntegerSetting(stmt, Settings.System.POINTER_DOUBLE_CLICK_SPEED,
                R.integer.def_double_click_speed);

        // M: Background power saving
        loadIntegerSetting(stmt, Settings.System.BG_POWER_SAVING_ENABLE,
                R.integer.def_bg_power_saving);

        // / M: Add for voice wake up {@
        if (SystemProperties.get("ro.mtk_voice_unlock_support").equals("1")) {
            loadIntegerSetting(stmt, Settings.System.VOICE_WAKEUP_MODE,
                    R.integer.def_voice_unlock_mode);
        } else {
            loadIntegerSetting(stmt, Settings.System.VOICE_WAKEUP_MODE,
                    R.integer.def_voice_wakeup_mode);
        }
        // / @}

    }

    public void loadCustomSecureSettings(SQLiteStatement stmt) {
        loadBooleanSetting(stmt, Settings.Secure.WFD_AUTO_CONNECT_ON,
                R.bool.def_wfd_auto_connect_on);
    }

    public void loadCustomGlobalSettings(SQLiteStatement stmt) {
        loadSetting(stmt, Settings.Global.DATA_ROAMING_2,
                "true".equalsIgnoreCase(SystemProperties.get(
                        "ro.com.android.dataroaming2", "false")) ? 1 : 0);

        loadBooleanSetting(stmt, Settings.Global.AUTO_TIME_GPS,
                R.bool.def_auto_time_gps); // Sync time to GPS

        // M: Add for passpoint
        loadIntegerSetting(stmt, Settings.Global.WIFI_PASSPOINT_ON,
                R.integer.def_wifi_passpoint_on);

        // M: Add for AutoJoin
        loadBooleanSetting(stmt, Settings.Global.WIFI_AUTO_JOIN,
                com.android.internal.R.bool.config_wifi_framework_enable_associated_network_selection);

        // M: Add for Network mode switch. ALPS01577029
        loadSetting(stmt, Settings.Global.TELEPHONY_MISC_FEATURE_CONFIG,
            getIntegerValue(Settings.Global.TELEPHONY_MISC_FEATURE_CONFIG, R.integer.def_telephony_misc_feature_config));

        //  LTE IMS
        loadBooleanSetting(stmt, Settings.Global.IMS_SWITCH, R.bool.def_ims_status);
    }

    private void loadSetting(SQLiteStatement stmt, String key, Object value) {
        stmt.bindString(1, key);
        stmt.bindString(2, value.toString());
        stmt.execute();
    }

    private void loadStringSetting(SQLiteStatement stmt, String key, int resid) {
        loadSetting(stmt, key, mRes.getString(resid));
    }

    private void loadBooleanSetting(SQLiteStatement stmt, String key, int resid) {
        loadSetting(stmt, key, mRes.getBoolean(resid) ? "1" : "0");
    }

    private void loadIntegerSetting(SQLiteStatement stmt, String key, int resid) {
        loadSetting(stmt, key, Integer.toString(mRes.getInteger(resid)));
    }

    private void loadFractionSetting(SQLiteStatement stmt, String key,
            int resid, int base) {
        loadSetting(stmt, key,
                Float.toString(mRes.getFraction(resid, base, base)));
    }

    public String getBooleanValue(String name, int resId) {
        String defaultValue = mRes.getBoolean(resId) ? "1" : "0";
        return mExt.getResBoolean(mContext, name, defaultValue);
    }

    public String getStringValue(String name, int resId) {
        return mExt.getResStr(mContext, name, mRes.getString(resId));
    }

    public String getIntegerValue(String name, int resId) {
        String defaultValue = Integer.toString(mRes.getInteger(resId));
        return mExt.getResInteger(mContext, name, defaultValue);
    }

    public String getFractionValue(String name, int resId, int defBase) {
        String defaultValue = Float.toString(mRes.getFraction(resId, defBase,
                defBase));
        return mExt.getResFraction(mContext, name, defaultValue, defBase);
    }
}
