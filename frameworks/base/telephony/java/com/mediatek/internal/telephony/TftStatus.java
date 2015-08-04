package com.mediatek.internal.telephony;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.lang.StringBuffer;

public class TftStatus implements Parcelable{
    public static final int OPCODE_SPARE =          0; // (0x00) Spare
    public static final int OPCODE_CREATE_NEW_TFT = 1; // (0x01) Create new TFT
    public static final int OPCODE_DELETE_TFT =     2; // (0x02) Delete existing TFT
    public static final int OPCODE_ADD_PF =         3; // (0x03) Add packet filters to existing TFT
    public static final int OPCODE_REPLACE_PF =     4; // (0x04) Replace packet filters in existing TFT
    public static final int OPCODE_DELETE_PF =      5; // (0x05) Delete packet filters from existing TFT
    public static final int OPCODE_NOTFT_OP =       6; // (0x06) No TFT operation
    public static final int OPCODE_RESERVED =       7; // (0x07) Reserved

    public int operation = -1;
    public ArrayList<PacketFilterInfo> packetFilterInfoList = new ArrayList<PacketFilterInfo>();
    public TftParameter tftParameter = new TftParameter();

    //this method make current instance share the same fields with the tftStatus passed in
    //Be aware that this does NOT mean CLONE a new one
    public void copyFrom(TftStatus tftStatus) {
        operation = tftStatus.operation;
        packetFilterInfoList = tftStatus.packetFilterInfoList;
        tftParameter = tftStatus.tftParameter;
    }

    public void readFrom(Parcel p) {
        reset();

        operation = p.readInt();
        int pfNumber = p.readInt();
        for (int i=0; i<pfNumber; i++) {
            PacketFilterInfo pfInfo = new PacketFilterInfo();
            pfInfo.readFrom(p);
            packetFilterInfoList.add(pfInfo);
        }

        tftParameter.readFrom(p);

        packetFilterInfoList.trimToSize();
    }

    public void writeTo(Parcel p) {
        p.writeInt(operation);
        p.writeInt(packetFilterInfoList.size());
        for (PacketFilterInfo pfInfo : packetFilterInfoList)
            pfInfo.writeTo(p);

        tftParameter.writeTo(p);
    }

    public void reset() {
        operation = -1;
        packetFilterInfoList.clear();
        tftParameter.reset();
    }

    @Override
    public String toString() {
        StringBuffer buf = new StringBuffer("[PacketFilterInfo[operation=" + operation + " ");
        for (PacketFilterInfo pfInfo : packetFilterInfoList)
            buf.append(pfInfo.toString());

        buf.append("], TftParameter[" + tftParameter + "]]");
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
    
    public static final Parcelable.Creator<TftStatus> CREATOR = new Parcelable.Creator<TftStatus>() {
        @Override
        public TftStatus createFromParcel(Parcel source) {
            TftStatus tftStatus = new TftStatus();
            tftStatus.readFrom(source);
            return tftStatus;
        }

        @Override
        public TftStatus[] newArray(int size) {
            return new TftStatus[size];
        }
    };
}