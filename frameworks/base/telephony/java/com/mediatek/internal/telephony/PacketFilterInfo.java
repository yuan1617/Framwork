package com.mediatek.internal.telephony;

import android.os.Parcel;
import android.os.Parcelable;

public class PacketFilterInfo implements Parcelable{
    public static final int IMC_BMP_NONE = 0x00000000;
    public static final int IMC_BMP_V4_ADDR = 0x00000001;
    public static final int IMC_BMP_V6_ADDR = 0x00000002;
    public static final int IMC_BMP_PROTOCOL = 0x00000004;
    public static final int IMC_BMP_LOCAL_PORT_SINGLE = 0x00000008;
    public static final int IMC_BMP_LOCAL_PORT_RANGE = 0x00000010;
    public static final int IMC_BMP_REMOTE_PORT_SINGLE = 0x00000020;
    public static final int IMC_BMP_REMOTE_PORT_RANGE = 0x00000040;
    public static final int IMC_BMP_SPI = 0x00000080;
    public static final int IMC_BMP_TOS = 0x00000100;
    public static final int IMC_BMP_FLOW_LABEL = 0x00000200;

    public int id;
    public int precedence;
    public int direction;
    public int networkPfIdentifier;
    public int bitmap; //bit mask that use to check if the the following member is valid
    public String address;
    public String mask;
    public int protocolNextHeader;
    public int localPortLow;
    public int localPortHigh;
    public int remotePortLow;
    public int remotePortHigh;
    public int spi;
    public int tos;
    public int tosMask;
    public int flowLabel;

    public void readFrom(Parcel p) {
        id = p.readInt();
        precedence = p.readInt();
        direction = p.readInt();
        networkPfIdentifier = p.readInt();
        bitmap = p.readInt();
        address = p.readString();
        mask = p.readString();
        protocolNextHeader = p.readInt();
        localPortLow = p.readInt();
        localPortHigh = p.readInt();
        remotePortLow = p.readInt();
        remotePortHigh = p.readInt();
        spi = p.readInt();
        tos = p.readInt();
        tosMask = p.readInt();
        flowLabel = p.readInt();
    }

    public void writeTo(Parcel p) {
        p.writeInt(id);
        p.writeInt(precedence);
        p.writeInt(direction);
        p.writeInt(networkPfIdentifier);
        p.writeInt(bitmap);
        p.writeString(address == null ?  "" : address);
        p.writeString(mask == null ? "" : mask);
        p.writeInt(protocolNextHeader);
        p.writeInt(localPortLow);
        p.writeInt(localPortHigh);
        p.writeInt(remotePortLow);
        p.writeInt(remotePortHigh);
        p.writeInt(spi);
        p.writeInt(tos);
        p.writeInt(tosMask);
        p.writeInt(flowLabel);
    }

    @Override
    public String toString() {
        return "[id=" + id + ", precedence=" + precedence + ", direction=" + direction + ", networkPfIdentifier=" + networkPfIdentifier + 
            ", bitmap=" + Integer.toHexString(bitmap) + ", address=" + address + ", mask=" + mask + ", protocolNextHeader=" + protocolNextHeader +
            ", localPortLow=" + localPortLow + ", localPortHigh=" + localPortHigh + ", remotePortLow=" + remotePortLow +
            ", remotePortHigh=" + remotePortHigh + ", spi=" + Integer.toHexString(spi) + ", tos=" + tos + ", tosMask=" + tosMask + ", flowLabel=" + Integer.toHexString(flowLabel) + "]";
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        writeTo(dest);
    }
    
    public static final Parcelable.Creator<PacketFilterInfo> CREATOR = new Parcelable.Creator<PacketFilterInfo>() {
        @Override
        public PacketFilterInfo createFromParcel(Parcel source) {
            PacketFilterInfo packetFilterInfo = new PacketFilterInfo();
            packetFilterInfo.readFrom(source);
            return packetFilterInfo;
        }

        @Override
        public PacketFilterInfo[] newArray(int size) {
            return new PacketFilterInfo[size];
        }
    };
}