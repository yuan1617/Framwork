package com.android.ims.mo;

import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

/**
 *
 * @hide
 */

public class ImsPhoneCtx implements Parcelable {
    private String mPhoneCtx;
    private String[] mPhoneCtxIpuis;

    /**
     * @hide
     */
    public ImsPhoneCtx() {
        mPhoneCtx = "";
    }

    /**
     * @param phoneCtx the value of the phone-context parameter
     * @param phoneCtxIpuis public user identities to which
     * the phone-context parameter value is associated.
     * @hide
     */
    public ImsPhoneCtx(String phoneCtx, String[] phoneCtxIpuis) {
        mPhoneCtx = phoneCtx;
        mPhoneCtxIpuis = phoneCtxIpuis;
    }

    /**
     * @return the value of the phone-context parameter
     * @hide
     */
    public String getPhoneCtx() {
        return mPhoneCtx;
    }

    /**
     * @return public user identities to which
     * the phone-context parameter value is associated.
     * @hide     
     */
    public String[] getPhoneCtxIpuis() {
        return mPhoneCtxIpuis;
    }

    /**
     * @param phoneCtx the value of the phone-context parameter
     * @hide
     */
    public void setPhoneCtx(String phoneCtx) {
        mPhoneCtx = phoneCtx;
    }

    /**
     * @param phoneCtxIpuis public user identities to which
     * the phone-context parameter value is associated.
     * @hide
     */
    public void setPhoneCtxIpuis(String[] phoneCtxIpuis) {
        mPhoneCtxIpuis = phoneCtxIpuis;
    }

    /**
     * @return The string value of this class
     * @hide
     */
    @Override
    public String toString() {
        synchronized (this) {
            StringBuilder builder = new StringBuilder("ImsPhoneCtx: ");
            builder.append("Phone Context: ").append(mPhoneCtx).append(", Address Type: ");
            if (mPhoneCtxIpuis != null) {
                for (String ipuis:mPhoneCtxIpuis) {
                    builder.append("-").append(ipuis);
                }
            }
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
            dest.writeString(mPhoneCtx);
            dest.writeStringArray(mPhoneCtxIpuis);
        }
    }

    /**
     * Implement the Parcelable interface.
     * @hide
     */
    public static final Creator<ImsPhoneCtx> CREATOR =
        new Creator<ImsPhoneCtx>() {
            public ImsPhoneCtx createFromParcel(Parcel in) {
                String phoneCtx = in.readString();
                String[] phoneCtxIpuis = in.createStringArray();
                ImsPhoneCtx imsPhoneCtx = new ImsPhoneCtx(phoneCtx, phoneCtxIpuis);
                Log.i("ImsPhoneCtx", "imsPhoneCtx:" + imsPhoneCtx);
                return imsPhoneCtx;
            }

            public ImsPhoneCtx[] newArray(int size) {
                return new ImsPhoneCtx[size];
            }
        };

}

