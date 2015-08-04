package com.mediatek.ims.internal;

/**
 * ImsXuiManager class.
 * To manage XUI
 *
 */
public class ImsXuiManager {
    public String mXui;
    static ImsXuiManager sInstance;

    /**
     * Instance constructor.
     *
     * @return ImsXuiManager instance
     */
    static public ImsXuiManager getInstance() {
        if (sInstance == null) {
            sInstance = new ImsXuiManager();
            sInstance.loadXui();
        }
        return sInstance;
    }

    public String getXui() {
        return mXui;
    }

    /**
     * Clear XUI.
     * Should be called if SIM card changed
     *
     */
    public void clearStoredXui() {
        mXui = null;
        // Todo: Clear the NV storage that XUI belongs to.
    }

    /**
     * Update from IMSA.
     *
     * @param xui  XUI
     */
    public void setXui(String xui) {
        mXui = xui;
        // Todo: Save XUI to a NV storage
    }

    private void loadXui() {
        // Todo: load XUI from a NV storage
    }
}
