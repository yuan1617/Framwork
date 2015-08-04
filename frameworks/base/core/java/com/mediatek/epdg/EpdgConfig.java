package com.mediatek.epdg;

import android.os.Parcel;
import android.os.Parcelable;
import android.os.SystemProperties;

import java.net.InetAddress;

/**
 * A simple container used to carry information in EpdgConfig.
 * Internal use only.
 *
 * @hide
 */

public class EpdgConfig implements Parcelable {

    public static final int IPV4   = 1;
    public static final int IPV6   = 2;
    public static final int IPV4V6 = 3;

    public static final int AKA_AUTH_TYPE = 1;
    public static final int SIM_AUTH_TYPE = 2;
    public static final int AKA_ISIM_AUTH_TYPE = 3;

    public static final int SIM1 = 1;
    public static final int SIM2 = 2;

    public static final int DSMIPV6_PROTOCOL = 1;
    public static final int NBM_PROTOCOL = 2;

    public String apnName;
    public String imsi;
    public String mnc;
    public String mcc;

    public int    accessIpType;
    public String wifiInterface;
    public InetAddress wifiIpv4Address;
    public InetAddress wifiIpv6Address;
    public String edpgServerAddress;

    public int   authType;
    public int   simIndex;
    public int   mobilityProtocol;

    public String certPath;
    public String ikeaAlgo;
    public String espAlgo;

    /**
     * Construction function of EpdgConfig.
     *
     */
    public EpdgConfig() {

        apnName = "";
        imsi = "";
        mnc = "";
        mcc = "";

        accessIpType = IPV4;
        simIndex = SIM1;
        wifiInterface = SystemProperties.get("wifi.interface", "wlan0");

        authType = AKA_AUTH_TYPE;
        mobilityProtocol = NBM_PROTOCOL;

        wifiIpv4Address = null;
        wifiIpv6Address = null;


        edpgServerAddress = "";

        certPath = "";
        ikeaAlgo = "";
        espAlgo = "";
    }

    @Override
    public String toString() {
        StringBuffer sb = new StringBuffer();
        String timeStampType;
        sb.append("APN=").append(apnName).append(" IMSI=")
            .append(imsi).append(",").append(mnc).append(mcc);
        sb.append(" type=").append(accessIpType);
        sb.append(" WiFi:").append(wifiInterface).append(" " + wifiIpv4Address)
            .append(" " + wifiIpv6Address);
        sb.append(" ePDG:").append(edpgServerAddress);
        sb.append(" Auth Type:").append(authType);
        sb.append(" SIM Index:").append(simIndex);
        sb.append(" Protocol:").append(mobilityProtocol);
        sb.append(" Cert Path:").append(certPath);
        sb.append(" IKE Algo:").append(ikeaAlgo);
        sb.append(" ESP Algo:").append(espAlgo);

        return sb.toString();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeString(edpgServerAddress);
        out.writeInt(authType);
        out.writeInt(simIndex);
        out.writeInt(mobilityProtocol);
        out.writeString(certPath);
        out.writeString(ikeaAlgo);
        out.writeString(espAlgo);
    }


    public static final Parcelable.Creator<EpdgConfig> CREATOR =
        new Parcelable.Creator<EpdgConfig>() {

            @Override
            public EpdgConfig createFromParcel(Parcel in) {
                EpdgConfig config = new EpdgConfig();
                config.edpgServerAddress = in.readString();
                config.authType = in.readInt();
                config.simIndex = in.readInt();
                config.mobilityProtocol = in.readInt();
                config.certPath = in.readString();
                config.ikeaAlgo = in.readString();
                config.espAlgo = in.readString();
                return config;
            }

            @Override
            public EpdgConfig[] newArray(int size) {
                return new EpdgConfig[size];
            }
        };
}