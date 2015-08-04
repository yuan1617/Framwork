package com.mediatek.internal.telephony;

import java.util.ArrayList;
import android.os.Parcelable;
import android.os.Parcel;
// TODO: need to add a convert function later
//import com.android.internal.telephony.dataconnection.DcFailCause;

import com.mediatek.internal.telephony.DedicateDataCallState.SetupResult;

public class DedicateBearerProperties implements Parcelable, Cloneable {
    public int interfaceId;
    public int cid; //connection ID of the DedicateDataConnection   
    public int defaultCid; //the cid of the default bearer that the dedicate bearer associated to
    public int signalingFlag; //if the signaling flag is set
    public int bearerId;
    public QosStatus qosStatus;
    public TftStatus tftStatus;
    public PcscfInfo pcscfInfo;
    public ArrayList<DedicateBearerProperties> concatenateBearers = new ArrayList<DedicateBearerProperties>();

    public DedicateBearerProperties() {
        clear();
    }

    public void clear() {
        interfaceId = -1;
        cid = -1;
        defaultCid = -1;
        signalingFlag = 0;
        bearerId = -1;
        qosStatus = null;
        tftStatus = null;
        pcscfInfo = null;
        concatenateBearers.clear();
    }

    public SetupResult setProperties(DedicateDataCallState dedicateCallState) {
        SetupResult result = SetupResult.SUCCESS;
        clear();

        interfaceId = dedicateCallState.interfaceId;
        cid = dedicateCallState.cid;
        defaultCid = dedicateCallState.defaultCid;
        signalingFlag = dedicateCallState.signalingFlag;
        bearerId = dedicateCallState.bearerId;
        if (dedicateCallState.qosStatus != null) {
            qosStatus = new QosStatus();
            qosStatus.copyFrom(dedicateCallState.qosStatus);
        }

        if (dedicateCallState.tftStatus != null) {
            tftStatus = new TftStatus();
            tftStatus.copyFrom(dedicateCallState.tftStatus);
        }

        if (dedicateCallState.pcscfInfo != null) {
            pcscfInfo = new PcscfInfo();
            pcscfInfo.copyFrom(dedicateCallState.pcscfInfo);
        }

        // TODO: need to add a convert function later
        //DcFailCause failCause = DcFailCause.fromInt(dedicateCallState.failCause);
        int failCause = dedicateCallState.failCause;
        if (failCause == 0/*DcFailCause.NONE*/ && dedicateCallState.active != 2) {
            result = SetupResult.FAIL;
            // TODO: need to add a convert function later
            //result.failCause = DcFailCause.UNACCEPTABLE_NETWORK_PARAMETER;
            result.failCause = 0x10002;
            //unreasonable that fail cause is none but connection is not activated
        } else if (failCause != 0 /*DcFailCause.NONE*/ && dedicateCallState.active == 2) {
            //although we have fail cause, but dedicate bearer is still activated
            //this means that the activation is failed but network assign contenated bearers
            //to handle this case, keep result success but set fail cause
            result.failCause = failCause;
        } else if (failCause != 0 /*DcFailCause.NONE*/) {
            result = SetupResult.FAIL;
            result.failCause = failCause;
        }
        concatenateBearers.trimToSize();
        return result;
    }

    public SetupResult setProperties(DedicateDataCallState[] dedicateCallStates) {
        //the first is main context
        SetupResult result = setProperties(dedicateCallStates[0]);

        //the others are concatenated contexts
        for (int i=1, length=dedicateCallStates.length; i<length; i++) {
            DedicateBearerProperties properties = new DedicateBearerProperties();
            SetupResult concatenateResult = properties.setProperties(dedicateCallStates[i]);
            if (concatenateResult == SetupResult.SUCCESS)
                concatenateBearers.add(properties);
        }

        return result;
    }

    @Override
    public String toString() {
        StringBuffer buf = new StringBuffer("[interfaceId=" + interfaceId + ", cid=" + cid + ", defaultCid=" + defaultCid +
            ", signalingFlag=" + signalingFlag + ", bearerId=" + bearerId + ", PCSCF=" + pcscfInfo + ", QOS=" + qosStatus + ", TFT=" + tftStatus + "]");

        for (DedicateBearerProperties properties : concatenateBearers)
            buf.append(properties);

        return buf.toString();
    }

    @Override
    public Object clone() throws CloneNotSupportedException {
        //use parcel read/write to implement clone
        Parcel parcel = Parcel.obtain();
        writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        return CREATOR.createFromParcel(parcel);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(interfaceId);
        dest.writeInt(cid);
        dest.writeInt(defaultCid);
        dest.writeInt(signalingFlag);
        dest.writeInt(bearerId);
        dest.writeInt(qosStatus == null ? 0 : 1); //has QOS or not
        if (qosStatus != null)
            qosStatus.writeTo(dest);
        dest.writeInt(tftStatus == null ? 0 : 1); //has TFT or not
        if (tftStatus != null)
            tftStatus.writeTo(dest);
        dest.writeInt(pcscfInfo == null ? 0 : 1); //has PCSCF or not
        if (pcscfInfo != null)
            pcscfInfo.writeTo(dest);
        dest.writeInt(concatenateBearers.size());
        for (DedicateBearerProperties properties : concatenateBearers)
            properties.writeToParcel(dest, flags);
    }

    public static final Creator<DedicateBearerProperties> CREATOR = new Creator<DedicateBearerProperties>() {
        public DedicateBearerProperties createFromParcel(Parcel p) {
            DedicateBearerProperties properties = new DedicateBearerProperties();
            properties.interfaceId = p.readInt();
            properties.cid = p.readInt();
            properties.defaultCid = p.readInt();
            properties.signalingFlag = p.readInt();
            properties.bearerId = p.readInt();
            if (p.readInt() == 1) { //has QOS
                properties.qosStatus = new QosStatus();
                properties.qosStatus.readFrom(p);
            }
            if (p.readInt() == 1) { //has TFT
                properties.tftStatus = new TftStatus();
                properties.tftStatus.readFrom(p);
            }
            if (p.readInt() == 1) { //has PCSCF
                properties.pcscfInfo = new PcscfInfo();
                properties.pcscfInfo.readFrom(p);
            }
            int concatenateNum = p.readInt();
            for (int i=0; i<concatenateNum; i++)
                properties.concatenateBearers.add(createFromParcel(p));

            return properties;
        }

        public DedicateBearerProperties[] newArray(int size) {
            return new DedicateBearerProperties[size];
        }
    };

}
