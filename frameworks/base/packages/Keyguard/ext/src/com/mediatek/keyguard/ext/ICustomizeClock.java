package com.mediatek.keyguard.ext;

import android.content.Context;
import android.view.ViewGroup;

/**
 * Interface that used for dual clock plug in feature {@hide}
 */
public interface ICustomizeClock {
    /**
     * add customized clock to container
     */
    void addCustomizeClock(Context context, ViewGroup clockContainer, ViewGroup statusArea);

    /**
     * Reset the relative registers etc.
     */
    void reset();

    /**
     * The domestic clock view's last line text need to align to the phone
     * setting clock time whose text is large and baseline is big. Update the
     * domestic clock's layout to align.
     */
    void updateClockLayout();
}
