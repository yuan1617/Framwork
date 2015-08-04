package com.mediatek.telecom;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * @hide
 */
public final class ConferenceXmlData implements Parcelable {
    private static final String Log_TAG = "ConferenceXmlData";
    private List<User> mUsers = new ArrayList<ConferenceXmlData.User>();

    public ConferenceXmlData(List<User> users) {
        mUsers = users;
    }

    public static final Parcelable.Creator<ConferenceXmlData> CREATOR 
        = new Parcelable.Creator<ConferenceXmlData>() {
        @Override
        public ConferenceXmlData createFromParcel(Parcel source) {
            ClassLoader classLoader = User.class.getClassLoader();
            List<User> users = new ArrayList<ConferenceXmlData.User>();
            source.readList(users, classLoader);
            return new ConferenceXmlData(users);
        }
        @Override
        public ConferenceXmlData[] newArray(int size) {
            return new ConferenceXmlData[size];
        }
    };

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeList(mUsers);
    }

    @Override
    public int describeContents() {
        // TODO Auto-generated method stub
        return 0;
    }

    public List<User> getUsers() {
        return mUsers;
    }

    public String toString() {
        StringBuffer buffer = new StringBuffer();
        int size = 0;
        if (mUsers != null) {
            size = mUsers.size();
        }
        buffer.append("size: ").append(size).append(".\n");
        for (User user : mUsers) {
            buffer.append("User: ").append(user.toString()).append(".\n");
        }
        return buffer.toString();
    }

    /**
     * This function will ensure member's sequence.
     * If set of member is equal, but sequence is not same, we think it as not equal.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ConferenceXmlData)) {
            return false;
        }
        ConferenceXmlData xmlData = (ConferenceXmlData) o;
        return Objects.equals(this.getUsers(), xmlData.getUsers());
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(getUsers());
    }

    /**
     * @hide
     */
    public final static class User implements Parcelable {

        public static final String STATUS_CONNECTED = "connected";
        public static final String STATUS_ON_HOLD = "on-hold";
        public static final String STATUS_DISCONNECTED = "disconnected";

        private String mAddress;
        private String mStatus;
        private String mIndex;

        public User(String address, String status, String index) {
            mAddress = address;
            mStatus = status;
            mIndex = index;
        }

        public static final Parcelable.Creator<User> CREATOR = new Parcelable.Creator<User>() {
            @Override
            public User createFromParcel(Parcel source) {
                String address = source.readString();
                String status = source.readString();
                String index = source.readString();
                return new User(address, status, index);
            }
            @Override
            public User[] newArray(int size) {
                return new User[size];
            }
        };

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeString(mAddress);
            dest.writeString(mStatus);
            dest.writeString(mIndex);
        }

        @Override
        public int describeContents() {
            // TODO Auto-generated method stub
            return 0;
        }

        public String getAddress() {
            return mAddress;
        }

        public String getStatus() {
            return mStatus;
        }

        public String getIndex() {
            return mIndex;
        }

        @Override
        public boolean equals(Object o) {
            boolean result = false;
            if (o instanceof User) {
                User user = (User) o;
                result = Objects.equals(mAddress, user.mAddress) &&
                        Objects.equals(mStatus, user.mStatus) &&
                        Objects.equals(mIndex, user.mIndex);
            }
            return result;
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(mAddress) +
                    Objects.hashCode(mStatus) +
                    Objects.hashCode(mIndex);
        }

        public String toString() {
            return (new StringBuffer())
                        .append("mAddress: ")
                        .append(mAddress)
                        .append("; mStatus: ")
                        .append(mStatus)
                        .append("; mIndex: ")
                        .append(mIndex)
                        .append("! ")
                        .toString();
        }
    }

}
