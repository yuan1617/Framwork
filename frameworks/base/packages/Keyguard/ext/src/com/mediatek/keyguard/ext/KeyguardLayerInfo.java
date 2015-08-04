
package com.mediatek.keyguard.ext;

import android.content.Intent;

public class KeyguardLayerInfo {
    /**
     * The package name of the layer, used as identity it would be persisted in system settings
     * provider if the layer is selected as the current.
     */
    public String layerPackage;

    /**
     * The string resource id of the layer name, used in LockScreen Settings.
     */
    public int nameResId;

    /**
     * The string resource id of the layer description, used in LockScreen Settings.
     */
    public int descResId;

    /**
     * The resource id of the preview image of the layer, used in LockScreen Settings.
     */
    public int previewResId;

    /**
     * The configure intent, may be null if no configuration needed for this layer.
     */
    public Intent configIntent;

    /**
     * The menu intent, may be null if no menu needed for this layer.
     */
    public Intent menuIntent;

    /**
     * The parameter for used
     */
    public String parameter;

    /**
     * The extern info array, for multi styles
     * if externKeyguardLayerInfo != NULL => The original fields shall be ignored
     */
    public KeyguardLayerInfo[] externKeyguardLayerInfo;
}
