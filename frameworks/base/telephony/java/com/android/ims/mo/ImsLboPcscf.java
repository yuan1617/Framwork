package com.android.ims.mo;

import android.os.Parcel;
import android.os.Parcelable;

/**
 *
 * @hide
 */

public class ImsLboPcscf implements Parcelable {
    private String mLboPcscfAddress;
    private String mLboPcscfAddressType;

    /**
     * @hide
     */
    public ImsLboPcscf() {
        mLboPcscfAddress = "";
        mLboPcscfAddressType = "";
    }

    /**
     * @param lboPcscfAddress the address of LBO P-CSCF
     * @param lboPcscfAddressType the address type of LBO P-CSCF
     * @hide
     */
    public ImsLboPcscf(String lboPcscfAddress, String lboPcscfAddressType) {
        mLboPcscfAddress = lboPcscfAddress;
        mLboPcscfAddressType = lboPcscfAddressType;
    }

    /**
     * @return the address of LBO P-CSCF
     * @hide
     */
    public String getLboPcscfAddress() {
        return mLboPcscfAddress;
    }

    /**
     * @return the address type of LBO P-CSCF
     * @hide
     */
    public String getLboPcscfAddressType() {
        return mLboPcscfAddressType;
    }

    /**
     * @param lboPcscfAddress the address of LBO P-CSCF
     * @hide
     */
    public void setLboPcscfAddress(String lboPcscfAddress) {
        mLboPcscfAddress = lboPcscfAddress;
    }

    /**
     * @param lboPcscfAddressType the address type of LBO P-CSCF
     * @hide
     */
    public void setLboPcscfAddressType(String lboPcscfAddressType) {
        mLboPcscfAddressType = lboPcscfAddressType;
    }

    /**
     * @return The string value of this class
     * @hide
     */
    @Override
    public String toString() {
        synchronized (this) {
            StringBuilder builder = new StringBuilder("ImsLboPcscf: ");
            builder.append("LBO PCSCF Address: ").append(mLboPcscfAddress).
            append(", Address Type: ").append(mLboPcscfAddressType);
            return builder.toString();
        }
    }

    /**
     * Implement the Parcelable interface.
     * @return a bitmask indicating the set of special object types marshalled
     * by the Parcelable.
     * @hide
     */
    public int describeContents() {
        return 0;
    }

    /**
     * Implement the Parcelable interface.
     * @param dest The Parcel in which the object should be written.
     * @param flags Additional flags about how the object should be written.
     * May be 0 or {@link #PARCELABLE_WRITE_RETURN_VALUE}.
     * @hide
     */
    public void writeToParcel(Parcel dest, int flags) {
         synchronized (this) {
            dest.writeString(mLboPcscfAddress);
            dest.writeString(mLboPcscfAddressType);
        }
    }

    /**
     * Implement the Parcelable interface.
     * @hide
     */
    public static final Creator<ImsLboPcscf> CREATOR =
        new Creator<ImsLboPcscf>() {
            public ImsLboPcscf createFromParcel(Parcel in) {
                String lboPcscfAddress = in.readString();
                String lboPcscfAddressType = in.readString();
                ImsLboPcscf imsLboPcscf = new ImsLboPcscf(lboPcscfAddress, lboPcscfAddressType);
                return imsLboPcscf;
            }

            public ImsLboPcscf[] newArray(int size) {
                return new ImsLboPcscf[size];
            }
        };

}

