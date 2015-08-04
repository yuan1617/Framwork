package com.mediatek.internal.telephony;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.lang.StringBuffer;

public class TftAuthToken implements Parcelable{
    public static final int FLOWID_LENGTH = 4;
    public ArrayList<Integer> authTokenList = new ArrayList<Integer>();
    public ArrayList<Integer[]> flowIdList = new ArrayList<Integer[]>();

    public void readFrom(Parcel p) {
        reset();

        int number = p.readInt();
        for (int i=0; i<number; i++)
            authTokenList.add(p.readInt());

        number = p.readInt();
        for (int i=0; i<number; i++) {
            Integer[] flowIds = new Integer[FLOWID_LENGTH];
            for (int j=0; j<FLOWID_LENGTH; j++)
                flowIds[j] = p.readInt();
            flowIdList.add(flowIds);
        }
    }

    public void writeTo(Parcel p) {
        p.writeInt(authTokenList.size());
        for (Integer authToken : authTokenList)
            p.writeInt(authToken);

        p.writeInt(flowIdList.size());
        for (Integer[] flowIds : flowIdList) {
            for (int j=0; j<FLOWID_LENGTH; j++)
                p.writeInt(flowIds[j]);
        }
    }

    public void reset() {
        authTokenList.clear();
        flowIdList.clear();
    }

    @Override
    public String toString() {
        StringBuffer buf = new StringBuffer("[AuthTokenList[");
        for (Integer authToken : authTokenList)
            buf.append(authToken + " ");
        buf.append("]");
        buf.append(", FlowIdList[");
        for (Integer[] flowIds : flowIdList) {
            buf.append("[");
            for (int j=0; j<FLOWID_LENGTH; j++)
                buf.append(flowIds[j] + " ");
            buf.append("]");
        }
        buf.append("]]");

        return buf.toString();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        writeTo(dest);
    }
    
    public static final Parcelable.Creator<TftAuthToken> CREATOR = new Parcelable.Creator<TftAuthToken>() {
        @Override
        public TftAuthToken createFromParcel(Parcel source) {
            TftAuthToken tftAuthToken = new TftAuthToken();
            tftAuthToken.readFrom(source);
            return tftAuthToken;
        }

        @Override
        public TftAuthToken[] newArray(int size) {
            return new TftAuthToken[size];
        }
    };
}
