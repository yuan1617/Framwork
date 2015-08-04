package com.mediatek.internal.telephony;

import android.os.Parcel;
import android.os.Parcelable;
// TODO: need to add a convert function later
//import com.android.internal.telephony.dataconnection.DcFailCause;

public class DedicateDataCallState implements Parcelable {
    public int interfaceId;
    public int defaultCid;
    public int cid;
    public int active;
    public int signalingFlag;
    public int bearerId;
    public int failCause;
    public QosStatus qosStatus;
    public TftStatus tftStatus;
    public PcscfInfo pcscfInfo;

    public enum SetupResult {
        SUCCESS,
        FAIL;

        // TODO: need to add a convert function later
        //public DcFailCause failCause = DcFailCause.fromInt(0);
        public int failCause = 0;
    }

    public void readFrom(int activeStatus, int cause, DedicateBearerProperties properties) {
       interfaceId = properties.interfaceId;
       defaultCid = properties.defaultCid;
       cid = properties.cid;
       active = activeStatus;
       signalingFlag = properties.signalingFlag;
       bearerId = properties.bearerId;
       failCause = cause;
       qosStatus = properties.qosStatus;
       tftStatus = properties.tftStatus;
       pcscfInfo = properties.pcscfInfo;
    }

    public void readFrom(Parcel p) {
        qosStatus = null;
        tftStatus = null;
        pcscfInfo = null;

        interfaceId = p.readInt();
        defaultCid = p.readInt();
        cid = p.readInt();
        active = p.readInt();
        signalingFlag = p.readInt();
        bearerId = p.readInt();
        failCause = p.readInt();

        //Qos
        if (p.readInt() == 1) {
            qosStatus = new QosStatus();
            qosStatus.readFrom(p);
        }

        //TFT
        if (p.readInt() == 1) {
            tftStatus = new TftStatus();
            tftStatus.readFrom(p);
        }

        //P-CSCF
        if (p.readInt() == 1) {
            pcscfInfo = new PcscfInfo();
            pcscfInfo.readAddressFrom(PcscfInfo.IMC_PCSCF_ACQUIRE_BY_NONE, p);
        }
    }

    public void writeTo(Parcel p) {
        p.writeInt(interfaceId);
        p.writeInt(defaultCid);
        p.writeInt(cid);
        p.writeInt(active);
        p.writeInt(signalingFlag);
        p.writeInt(bearerId);
        p.writeInt(failCause);
        p.writeInt(qosStatus == null ? 0 : 1);
        if (qosStatus != null)
            qosStatus.writeTo(p);
        p.writeInt(tftStatus== null ? 0 : 1);
        if (tftStatus != null)
            tftStatus.writeTo(p);
        p.writeInt(pcscfInfo== null ? 0 : 1);
        if (pcscfInfo != null)
            pcscfInfo.writeAddressTo(p);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        writeTo(dest);
    }


    @Override
    public String toString() {
        return "[interfaceId=" + interfaceId + ", defaultCid=" + defaultCid + ", cid=" + cid + ", active=" + active + ", signalingFlag=" +
            signalingFlag + ", bearerId=" + bearerId + ", failCause=" + failCause + ", QOS=" + qosStatus + ", TFT=" + tftStatus + ", PCSCF=" + pcscfInfo + "]";
    }

    public static final Creator<DedicateDataCallState> CREATOR = new Creator<DedicateDataCallState>() {
        public DedicateDataCallState createFromParcel(Parcel p) {
            DedicateDataCallState dedicateDataCallState = new DedicateDataCallState();
            dedicateDataCallState.readFrom(p);
            return dedicateDataCallState;
        }

        public DedicateDataCallState[] newArray(int size) {
            return new DedicateDataCallState[size];
        }
    };
}
