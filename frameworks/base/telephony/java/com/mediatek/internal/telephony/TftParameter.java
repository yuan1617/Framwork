package com.mediatek.internal.telephony;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.lang.StringBuffer;

public class TftParameter implements Parcelable {
    public ArrayList<Integer> linkedPacketFilterIdList = new ArrayList<Integer>();
    public ArrayList<TftAuthToken> authTokenFlowIdList = new ArrayList<TftAuthToken>();

    public void reset() {
        linkedPacketFilterIdList.clear();
        authTokenFlowIdList.clear();
    }

    public boolean isEmpty() {
        return linkedPacketFilterIdList.size() == 0 && authTokenFlowIdList.size() == 0;
    }

    public void readFrom(Parcel p) {
        reset();

        int linkedPfNumber = p.readInt();
        for (int i=0; i<linkedPfNumber; i++)
            linkedPacketFilterIdList.add(p.readInt());
    
        int authtokenFlowIdNumber = p.readInt();
        for (int i=0; i<authtokenFlowIdNumber; i++) {
            TftAuthToken tftAuthToken= new TftAuthToken();
            tftAuthToken.readFrom(p);
            authTokenFlowIdList.add(tftAuthToken);
        }

        linkedPacketFilterIdList.trimToSize();
        authTokenFlowIdList.trimToSize();
    }

    public void writeTo(Parcel p) {
        p.writeInt(linkedPacketFilterIdList.size());
        for (Integer pfId : linkedPacketFilterIdList)
            p.writeInt(pfId);

        p.writeInt(authTokenFlowIdList.size());
        for (TftAuthToken authToken : authTokenFlowIdList)
            authToken.writeTo(p);
    }

    @Override
    public String toString() {
        StringBuffer buf = new StringBuffer("[LinkedPacketFilterIdList[");
        for (Integer linkedPacketFilterId : linkedPacketFilterIdList)
            buf.append(linkedPacketFilterId + " ");
        buf.append("]");
        buf.append(", AuthTokenFlowIdList[");
        for (TftAuthToken tftAuthToken : authTokenFlowIdList)
            buf.append(tftAuthToken + " ");
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
    
    public static final Parcelable.Creator<TftParameter> CREATOR = new Parcelable.Creator<TftParameter>() {
        @Override
        public TftParameter createFromParcel(Parcel source) {
            TftParameter tftParameter = new TftParameter();
            tftParameter.readFrom(source);
            return tftParameter;
        }

        @Override
        public TftParameter[] newArray(int size) {
            return new TftParameter[size];
        }
    };
}