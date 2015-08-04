package com.mediatek.systemui.ext;

import android.view.ViewGroup;

/**
 * M: the interface for Plug-in definition of QuickSettings.
 */
public interface IQuickSettingsPlugin {
    /**
     * Customize tile view.
     * @param parent The parent container.
     */
    void customizeTileViews(ViewGroup parent);

    /**
     * Customize create the data usage tile.
     * @param isDisplay The default value.
     * @return if true create data usage; If false not create.
     */
    boolean customizeDisplayDataUsage(boolean isDisplay);

    /**
     * Update resources.
     */
    void updateResources();

}
