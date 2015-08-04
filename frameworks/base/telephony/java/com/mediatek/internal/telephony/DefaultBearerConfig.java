package com.mediatek.internal.telephony;

import android.os.Parcel;
import android.os.Parcelable;
import com.mediatek.internal.telephony.QosStatus;

public class DefaultBearerConfig implements Parcelable{
    public int mIsValid;
    public QosStatus mQos;
    public int mEmergency_ind;
    public int mPcscf_discovery_flag;
    public int mSignaling_flag;


    public DefaultBearerConfig() {
        mQos = new QosStatus();
        reset();
    }
    public DefaultBearerConfig(int isValid, QosStatus qos, int emergency_ind, int pcscf_discovery_flag, int signaling_flag) {
        mIsValid = isValid;
        mQos = qos;
        mEmergency_ind = emergency_ind;
        mPcscf_discovery_flag = pcscf_discovery_flag;
        mSignaling_flag = signaling_flag;
    }
    
    public void copyFrom(DefaultBearerConfig defaultBearerConfig) {
        mIsValid = defaultBearerConfig.mIsValid;
        mQos = defaultBearerConfig.mQos;
        mEmergency_ind = defaultBearerConfig.mEmergency_ind;
        mPcscf_discovery_flag = defaultBearerConfig.mPcscf_discovery_flag;
        mSignaling_flag = defaultBearerConfig.mSignaling_flag;
    }


    public void readFrom(Parcel p) {
        mIsValid = p.readInt();
        mQos.readFrom(p);
        mEmergency_ind = p.readInt();
        mPcscf_discovery_flag = p.readInt();
        mSignaling_flag = p.readInt();
    }

    public void writeTo(Parcel p) {
        p.writeInt(mIsValid);
        mQos.writeTo(p);
        p.writeInt(mEmergency_ind);
        p.writeInt(mPcscf_discovery_flag);
        p.writeInt(mSignaling_flag);
    }

    public void reset() {
        mIsValid = 0;
        mQos.reset();
        mEmergency_ind = 0;
        mPcscf_discovery_flag = 0;
        mSignaling_flag = 0;
    }

    @Override
    public String toString() {
        return "[isValid=" + mIsValid + ", qos=" + mQos + ", emergency_ind=" + mEmergency_ind + ", pcscf_discovery_flag=" + mPcscf_discovery_flag + ", signaling_flag=" + mSignaling_flag + "]";
    }

    public static final Parcelable.Creator<DefaultBearerConfig> CREATOR = new Parcelable.Creator<DefaultBearerConfig>() {
        @Override
        public DefaultBearerConfig createFromParcel(Parcel source) {
            DefaultBearerConfig defaultBearerConfig = new DefaultBearerConfig();
            defaultBearerConfig.readFrom(source);
            return defaultBearerConfig;
        }
        @Override
        public DefaultBearerConfig[] newArray(int size) {
            return new DefaultBearerConfig[size];
        }
    };
        
    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        writeTo(dest);
    }
}