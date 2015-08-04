package com.android.ims.mo;

import android.os.Parcel;
import android.os.Parcelable;

/**
 *
 * @hide
 */

public class ImsIcsi implements Parcelable {
    private String mIcsi;
    private boolean mIsAllocated;

    /**
     * @hide
     */
    public ImsIcsi() {
        mIcsi = "";
        mIsAllocated = false;
    }

    /**
     * @param icsi A communication services identifier
     * @param isAllocated indicates whether UE initiates resource
     * allocation for the media controlled by IM CN subsystem
     * when a certain ICSI is used
     * @hide
     */
    public ImsIcsi(String icsi, boolean isAllocated) {
        mIcsi = icsi;
        mIsAllocated = isAllocated;
    }

    /**
     * @return A communication services identifier
     * @hide
     */
    public String getIcsi() {
        return mIcsi;
    }

    /**
     * @return {@code true} UE initiates resource
     * allocation for the media controlled by IM CN subsystem
     * when a certain ICSI is used
     * @hide
     */
    public boolean getIsAllocated() {
        return mIsAllocated;
    }

    /**
     * @param icsi A communication services identifier
     * @hide
     */
    public void setIcsi(String icsi) {
        mIcsi = icsi;
    }

    /**
     * @param isAllocated indicates whether UE initiates resource
     * allocation for the media controlled by IM CN subsystem
     * when a certain ICSI is used
     * @hide
     */
    public void setIsAllocated(boolean isAllocated) {
        mIsAllocated = isAllocated;
    }

    /**
     * @return The string value of this class
     * @hide
     */
    @Override
    public String toString() {
        synchronized (this) {
            StringBuilder builder = new StringBuilder("ImsIsci: ");
            builder.append("ICSI: ").append(mIcsi).append(", isAllocated: ").append(mIsAllocated);
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
            dest.writeString(mIcsi);
            dest.writeInt(mIsAllocated ? 1 : 0);
        }
    }

    /**
     * Implement the Parcelable interface.
     * @hide
     */
    public static final Creator<ImsIcsi> CREATOR =
        new Creator<ImsIcsi>() {
            public ImsIcsi createFromParcel(Parcel in) {
                String icsi = in.readString();
                boolean isAllocated = in.readInt() != 0;
                ImsIcsi imsIcsi = new ImsIcsi(icsi, isAllocated);
                return imsIcsi;
            }

            public ImsIcsi[] newArray(int size) {
                return new ImsIcsi[size];
            }
        };

}

