package com.mediatek.ims;

import android.content.Context;
import android.telephony.TelephonyManager;
import android.util.Log;


import com.android.ims.ImsConfig;
import com.android.ims.ImsConfigListener;
import com.android.ims.internal.IImsConfig;
import com.android.ims.mo.ImsIcsi;
import com.android.ims.mo.ImsLboPcscf;
import com.android.ims.mo.ImsPhoneCtx;

import java.util.ArrayList;
import java.util.List;

/**
 * IMSConfig class for handle the IMS MO configruation.
 *
 * The implementation is based on 3GPP 24.167  3GPP IMS Management Object (MO); Stage 3
 *
 *  @hide
 */
public class ImsConfigStub extends IImsConfig.Stub {
    private static final String TAG = "ImsConfigService";

    private static final int  MAX_MO_COUNT                 = 4;
    private static final int  MAX_BYTE_COUNT               = 256;

    private Context mContext;
    private String  mAtCmdResult = "";
    private static TelephonyManager sTelephonyManager = null;
    private ImsLboPcscf[] mImsLboPcscf;
    private String  mPcscf;

    /**
     *
     * Construction function for ImsConfigStub.
     *
     * @param context the application context
     *
     */
    public ImsConfigStub(Context context) {
        mContext = context;

        mImsLboPcscf = new ImsLboPcscf[MAX_MO_COUNT];
        for (int i = 0; i < MAX_MO_COUNT; i++) {
            mImsLboPcscf[i] = new ImsLboPcscf();
        }
        mPcscf = "";
    }

    /**
     * Gets the value for ims service/capabilities parameters from the master
     * value storage. Synchronous blocking call.
     *
     * @param item as defined in com.android.ims.ImsConfig#ConfigConstants.
     * @return value in Integer format.     
     */
    @Override
    public int getMasterValue(int item) {
        return handleGetMasterValue(item);
    }


    /**
     * Gets the value for ims service/capabilities parameters from the master
     * value storage. Synchronous blocking call.
     *
     * @param item as defined in com.android.ims.ImsConfig#ConfigConstants.
     * @return value in String format.     
     */
    @Override public String getMasterStringValue(int item) {
        if (sTelephonyManager == null) {
            sTelephonyManager = (TelephonyManager) mContext.getSystemService(
                                    Context.TELEPHONY_SERVICE);
        }

        if (item == ImsConfig.ConfigConstants.IMS_MO_IMPI) {
            return sTelephonyManager.getIsimImpi();
        } else if (item == ImsConfig.ConfigConstants.IMS_MO_DOMAIN) {
            return sTelephonyManager.getIsimDomain();
        } else if (item == ImsConfig.ConfigConstants.IMS_MO_PCSCF) {
            return mPcscf;
        }

        return "";
    }

    /**
     * Sets the value for IMS service/capabilities parameters by the operator device
     * management entity. It sets the config item value in the provisioned storage
     * from which the master value is derived. Synchronous blocking call.
     *
     * @param item as defined in com.android.ims.ImsConfig#ConfigConstants.
     * @param value in Integer format.     
     */
    @Override
    public void setProvisionedValue(int item, int value) {
        handleProvisionedValue(item, value);
    }

    /**
     * Sets the value for IMS service/capabilities parameters by the operator device
     * management entity. It sets the config item value in the provisioned storage
     * from which the master value is derived.  Synchronous blocking call.
     *
     * @param item as defined in com.android.ims.ImsConfig#ConfigConstants.
     * @param value in String format.
     */
    @Override
    public void setProvisionedStringValue(int item, String value) {

        if (item == ImsConfig.ConfigConstants.IMS_MO_PCSCF) {
            mPcscf = value;
            return;
        }

    }

    /**
     * Gets the value of the specified IMS feature item for specified network type.
     * This operation gets the feature config value from the master storage (i.e. final
     * value) asynchronous non-blocking call.
     *
     * @param feature as defined in com.android.ims.ImsConfig#FeatureConstants.
     * @param network as defined in android.telephony.TelephonyManager#NETWORK_TYPE_XXX.
     * @param listener feature value returned asynchronously through listener.
     */
    @Override
    public void getFeatureValue(int feature, int network, ImsConfigListener listener) {

    }

    /**
     * Sets the value for IMS feature item for specified network type.
     * This operation stores the user setting in setting db from which master db
     * is dervied.
     *
     * @param feature as defined in com.android.ims.ImsConfig#FeatureConstants.
     * @param network as defined in android.telephony.TelephonyManager#NETWORK_TYPE_XXX.
     * @param value as defined in com.android.ims.ImsConfig#FeatureValueConstants.
     * @param listener provided if caller needs to be notified for set result.
     */
    @Override
    public void setFeatureValue(int feature, int network, int value, ImsConfigListener listener) {

    }

    /**
     * Gets the value for IMS service/capabilities parameters used by IMS stack.
     * This function should not be called from the mainthread as it could block the
     * mainthread to cause ANR.
     *
     * @param item as defined in com.android.ims.ImsConfig#ConfigConstants.
     * @return the value in String array.     
     *
     */
    @Override
    public String[] getMasterStringArrayValue(int item) {
        if (sTelephonyManager == null) {
            sTelephonyManager = (TelephonyManager)
                                mContext.getSystemService(Context.TELEPHONY_SERVICE);
        }

        if (item == ImsConfig.ConfigConstants.IMS_MO_IMPU) {
            return sTelephonyManager.getIsimImpu();
        }

        return null;
    }

    /**
     * Gets the value for IMS service/capabilities parameters used by IMS stack.
     * This function should not be called from the mainthread as it could block the
     * mainthread to cause ANR.
     *
     * @return the Icsi object.     
     *
     */
    @Override
    public ImsIcsi[] getMasterIcsiValue() {
        List<ImsIcsi> icsiList = new ArrayList<ImsIcsi>();
        String atCmdString = "";
        String icsi = "";
        String isAllocated = "";
        int i = 0;

        for (i = 0; i < MAX_MO_COUNT; i++) {
            icsiList.add(new ImsIcsi("", false));
        }

        for (i = 0; i < MAX_MO_COUNT; i++) {
            atCmdString = "AT+ECFGGET=\"icsi_" + (i + 1) + "\"";
            icsi = executeCommandResponse(atCmdString);
            atCmdString = "AT+ECFGGET=\"icsi_resource_allocation_mode_" + (i + 1) + "\"";
            isAllocated = executeCommandResponse(atCmdString);

            if (icsi.length() > 0) {
                icsiList.set(i, new ImsIcsi(icsi, isAllocated.equals("1") ? true : false));
            }
        }

        return icsiList.toArray(new ImsIcsi[icsiList.size()]);
    }

    /**
     * Gets the value for IMS service/capabilities parameters used by IMS stack.
     * This function should not be called from the mainthread as it could block the
     * mainthread to cause ANR.
     *
     * @return the LboPcscf object.     
     */
    @Override
    public ImsLboPcscf[] getMasterLboPcscfValue() {
        return mImsLboPcscf;
    }

    /**
     * Gets the value for IMS service/capabilities parameters used by IMS stack.
     * This function should not be called from the mainthread as it could block the
     * mainthread to cause ANR.
     *
     * @return the ImsPhoneCtx object.     
     *
     */
    @Override
    public ImsPhoneCtx[] getMasterImsPhoneCtxValue() {
        List<ImsPhoneCtx> phoneCtxList = new ArrayList<ImsPhoneCtx>();
        List<String> phoneCtxImpi = new ArrayList<String>();
        String atCmdString = "";
        String ctx = "";
        String ctxImpu = "";
        int i = 0;
        int j = 0;

        phoneCtxList.clear();

        for (i = 0; i < MAX_MO_COUNT; i++) {
            ctx = "";
            phoneCtxImpi.clear();

            for (j = 0; j < MAX_MO_COUNT; j++) {
                phoneCtxImpi.add("");
            }

            phoneCtxList.add(new ImsPhoneCtx(ctx,
                            phoneCtxImpi.toArray(new String[phoneCtxImpi.size()])));
        }

        for (i = 0; i < MAX_MO_COUNT; i++) {

            if (i == 0) {
                atCmdString = "AT+ECFGGET=\"UA_phone_context\"";
            } else {
                atCmdString = "AT+ECFGGET=\"UA_phone_context_" + (i + 1) + "\"";
            }

            ctx = executeCommandResponse(atCmdString);
            Log.i(TAG, "readImsPhoneCtxMo:" + ctx);

            if (ctx.length() == 0) {
                continue;
            }

            for (j = 0; j < MAX_MO_COUNT; j++) {
                if (i == 0 && j == 0) {
                    atCmdString = "AT+ECFGGET=\"UA_phone_context_associated_impu\"";
                } else {
                    atCmdString = "AT+ECFGGET=\"UA_phone_context_associated_impu_"
                                  + (i + 1) + "_" + (j + 1) + "\"";
                }

                ctxImpu = executeCommandResponse(atCmdString);
                Log.d(TAG, i + " ctxImpu:" + ctxImpu);

                if (ctxImpu.length() > 0) {
                    phoneCtxImpi.set(j, ctxImpu);
                }
            }

            phoneCtxList.set(i, new ImsPhoneCtx(ctx,
                            phoneCtxImpi.toArray(new String[MAX_MO_COUNT])));

        }

        return phoneCtxList.toArray(new ImsPhoneCtx[MAX_MO_COUNT]);
    }

    /**
     * Sets the value for IMS service/capabilities parameters by
     * the operator device management entity.
     *
     * @param item as defined in com.android.ims.ImsConfig#ConfigConstants.
     * @param value in String Array format.
     *
     */
    @Override
    public void setProvisionedStringArrayValue(int item, String[] value) {
        //DoNothing
    }

    /**
     * Sets the value for IMS service/capabilities parameters by
     * the operator device management entity.
     *
     * @param value in ImsIcsi[] object.     
     *
     */
    @Override
    public void setProvisionedIcsiValue(ImsIcsi[] value) {
        int i = 0;
        String cmdStr = "";

        for (i = 0; i < MAX_MO_COUNT && i < value.length; i++) {
            cmdStr = "AT+ECFGSET=\"icsi_" + (i + 1) + "\",\"" + value[i].getIcsi() + "\"";
            executeCommandResponse(cmdStr);

            if (value[i].getIsAllocated()) {
                cmdStr = "AT+ECFGSET=\"icsi_resource_allocation_mode_" + (i + 1) + "\",\"1\"";
            } else {
                cmdStr = "AT+ECFGSET=\"icsi_resource_allocation_mode_" + (i + 1) + "\",\"0\"";
            }

            executeCommandResponse(cmdStr);
        }
    }

    /**
     * Sets the value for IMS service/capabilities parameters by
     * the operator device management entity.
     *
     * @param values in ImsLboPcscf[] object.
     *
     */
    @Override
    public void setProvisionedLboPcscfValue(ImsLboPcscf[] values) {
        int total = (MAX_MO_COUNT < values.length) ? MAX_MO_COUNT : values.length;

        for (int i = 0; i < total; i++) {
             mImsLboPcscf[i].setLboPcscfAddress(values[i].getLboPcscfAddress());
             mImsLboPcscf[i].setLboPcscfAddressType(values[i].getLboPcscfAddressType());
        }
    }

    /**
     * Sets the value for IMS service/capabilities parameters by
     * the operator device management entity.
     *
     * @param value in ImsPhoneCtx[] object.     
     *
     */
    @Override
    public void setProvisionedPhoneCtxValue(ImsPhoneCtx[] value) {
        String atCmdString = "";
        int i = 0;
        int j = 0;

        for (i = 0; i < MAX_MO_COUNT && i < value.length; i++) {
            if (i == 0) {
                atCmdString = "AT+ECFGSET=\"UA_phone_context\",\""
                        + value[i].getPhoneCtx() + "\"";
            } else {
                atCmdString = "AT+ECFGSET=\"UA_phone_context_"
                        + (i + 1) + "\",\"" + value[i].getPhoneCtx() + "\"";
            }

            executeCommandResponse(atCmdString);
            String[] ctxIpuis = value[i].getPhoneCtxIpuis();

            for (j = 0; j < ctxIpuis.length && j  < MAX_MO_COUNT; j++) {
                if (i == 0 && j == 0) {
                    atCmdString = "AT+ECFGSET=\"UA_phone_context_associated_impu\",\""
                                  + ctxIpuis[j] + "\"";
                } else {
                    atCmdString = "AT+ECFGSET=\"UA_phone_context_associated_impu_"
                                  + (i + 1) + "_" + (j + 1) + "\",\"" + ctxIpuis[j] + "\"";
                }
            }

            executeCommandResponse(atCmdString);
        }
    }

    private String getAtCmdLine(int item) {
        String atCmdString = "";

        Log.i(TAG, "getAtCmdLine:" + item);

        switch (item) {
        case ImsConfig.ConfigConstants.SIP_T1_TIMER:
            atCmdString = "AT+ECFGGET=\"UA_timer_T1\"";
            break;
        case ImsConfig.ConfigConstants.SIP_T2_TIMER:
            atCmdString = "AT+ECFGGET=\"UA_timer_T2\"";
            break;
        case ImsConfig.ConfigConstants.SIP_TF_TIMER:
            atCmdString = "AT+ECFGGET=\"UA_timer_T4\"";
            break;
        case ImsConfig.ConfigConstants.IMS_MO_RESOURCE:
            atCmdString = "AT+ECFGGET=\"resource_allocation_mode\"";
            break;
        case ImsConfig.ConfigConstants.IMS_MO_MOBILITY:
            atCmdString = "AT+CMMIVT?";
            break;
        case ImsConfig.ConfigConstants.IMS_MO_SMS:
            atCmdString = "AT+ECFGGET=\"sms_over_ip\"";
            break;
        case ImsConfig.ConfigConstants.IMS_MO_KEEPALIVE:
            atCmdString = "AT+ECFGGET=\"UA_keep_alive\"";
            break;
        case ImsConfig.ConfigConstants.IMS_MO_VOICE_E:
            atCmdString = "AT+CEVDP?";
            break;
        case ImsConfig.ConfigConstants.IMS_MO_VOICE_U:
            atCmdString = "AT+CVDP?";
            break;
        case ImsConfig.ConfigConstants.IMS_MO_REG_BASE:
            atCmdString = "AT+ECFGGET=\"UA_reg_retry_base_time\"";
            break;
        case ImsConfig.ConfigConstants.IMS_MO_REG_MAX:
            atCmdString = "AT+ECFGGET=\"UA_reg_retry_max_time\"";
            break;
        default:
            Log.e(TAG, "Unknown item option");
            break;
        }

        return atCmdString;
    }

    private String getAtCmdSetLine(int item, int value) {
        String atCmdString = "";

        Log.i(TAG, "getAtCmdLine:" + item);

        switch (item) {
        case ImsConfig.ConfigConstants.SIP_T1_TIMER:
            atCmdString = "AT+ECFGSET=\"UA_timer_T1\", \"" + value + "\"";
            break;
        case ImsConfig.ConfigConstants.SIP_T2_TIMER:
            atCmdString = "AT+ECFGSET=\"UA_timer_T2\", \"" + value + "\"";
            break;
        case ImsConfig.ConfigConstants.SIP_TF_TIMER:
            atCmdString = "AT+ECFGSET=\"UA_timer_T4\", \"" + value + "\"";
            break;
        case ImsConfig.ConfigConstants.IMS_MO_RESOURCE:
            atCmdString = "AT+ECFGSET=\"resource_allocation_mode\", \"" + value + "\"";
            break;
        case ImsConfig.ConfigConstants.IMS_MO_MOBILITY:
            atCmdString = "AT+CMMIVT=" + value;
            break;
        case ImsConfig.ConfigConstants.IMS_MO_SMS:
            atCmdString = "AT+ECFGSET=\"sms_over_ip\", \"" + value + "\"";
            break;
        case ImsConfig.ConfigConstants.IMS_MO_KEEPALIVE:
            atCmdString = "AT+ECFGSET=\"UA_keep_alive\", \"" + value + "\"";
            break;
        case ImsConfig.ConfigConstants.IMS_MO_VOICE_E:
            atCmdString = "AT+CEVDP=" + value;
            break;
        case ImsConfig.ConfigConstants.IMS_MO_VOICE_U:
            atCmdString = "AT+CVDP=" + value;
            break;
        case ImsConfig.ConfigConstants.IMS_MO_REG_BASE:
            atCmdString = "AT+ECFGSET=\"UA_reg_retry_base_time\", \"" + value + "\"";
            break;
        case ImsConfig.ConfigConstants.IMS_MO_REG_MAX:
            atCmdString = "AT+ECFGSET=\"UA_reg_retry_max_time\", \"" + value + "\"";
            break;
        default:
            Log.e(TAG, "Unknown item option");
            break;
        }

        return atCmdString;
    }

    private synchronized int handleGetMasterValue(int item) {
        Log.i(TAG, "handleGetMasterValue:" + item);

        String retValue = executeCommandResponse(getAtCmdLine(item));

        if (retValue.length() >  0) {
            try {
                return Integer.parseInt(retValue);
            } catch (NumberFormatException ne) {
                ne.printStackTrace();
            }
        }

        return 0;
    }

    private synchronized void handleProvisionedValue(int item, int value) {
        Log.i(TAG, "handleProvisionedValue:" + item + ":" + value);
        executeCommandResponse(getAtCmdSetLine(item, value));
    }

    private synchronized String executeCommandResponse(String atCmdLine) {
        String atCmdResult = "";

        if (sTelephonyManager == null) {
            sTelephonyManager = (TelephonyManager)
                                mContext.getSystemService(Context.TELEPHONY_SERVICE);
        }

        byte[] rawData = atCmdLine.getBytes();
        byte[] cmdByte = new byte[rawData.length + 1];
        byte[] respByte = new byte[MAX_BYTE_COUNT + 1];
        System.arraycopy(rawData, 0, cmdByte, 0, rawData.length);
        cmdByte[cmdByte.length - 1] = 0;

        if (sTelephonyManager.invokeOemRilRequestRaw(cmdByte, respByte) > 0) {
            atCmdResult = new String(respByte);
        }

        //Handle CME ERROR
        if (atCmdResult.indexOf("+CME ERROR") != -1) {
            atCmdResult = "";
        }
        return atCmdResult;
    }
}