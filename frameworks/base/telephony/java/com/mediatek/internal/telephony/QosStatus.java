package com.mediatek.internal.telephony;

import android.os.Parcel;
import android.os.Parcelable;

public class QosStatus implements Parcelable{
    public int qci; //class of EPS QoS
    //0: QCI is selected by network
    //[1-4]: value range for guaranteed bit rate Traffic Flows
    //[5-9]: value range for non-guarenteed bit rate Traffic Flows
    //[128-254]: value range for Operator-specific QCIs

    //For R8, this is also used for traffic class

    public int dlGbr; //downlink guaranteed bit rate
    public int ulGbr; //uplink guaranteed bit rate
    public int dlMbr; //downlink maximum bit rate
    public int ulMbr; //uplink maximum bit rate

    public void copyFrom(QosStatus qos) {
        qci = qos.qci;
        dlGbr = qos.dlGbr;
        ulGbr = qos.ulGbr;
        dlMbr = qos.dlMbr;
        ulMbr = qos.ulMbr;
    }

    public void readFrom(Parcel p) {
        qci = p.readInt();
        dlGbr = p.readInt();
        ulGbr = p.readInt();
        dlMbr = p.readInt();
        ulMbr = p.readInt();
    }

    public void writeTo(Parcel p) {
        p.writeInt(qci);
        p.writeInt(dlGbr);
        p.writeInt(ulGbr);
        p.writeInt(dlMbr);
        p.writeInt(ulMbr);
    }

    public void reset() {
        qci = 0;
        dlGbr = 0;
        ulGbr = 0;
        dlMbr = 0;
        ulMbr = 0;
    }

    @Override
    public String toString() {
        return "[qci=" + qci + ", dlGbr=" + dlGbr + ", ulGbr=" + ulGbr + ", dlMbr=" + dlMbr + ", ulMbr=" + ulMbr + "]";
    }

    @Override    
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        writeTo(dest);
    }

    public static final Parcelable.Creator<QosStatus> CREATOR = new Parcelable.Creator<QosStatus>() {
        @Override
        public QosStatus createFromParcel(Parcel source) {
            QosStatus qosStatus = new QosStatus();
            qosStatus.readFrom(source);
            return qosStatus;
        }

        @Override
        public QosStatus[] newArray(int size) {
            return new QosStatus[size];
        }
    };
}
