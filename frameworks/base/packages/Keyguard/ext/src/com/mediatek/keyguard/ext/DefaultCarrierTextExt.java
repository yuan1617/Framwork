package com.mediatek.keyguard.ext;

import com.mediatek.common.PluginImpl ;

/**
 * Default plugin implementation.
 */
@PluginImpl(interfaceName="com.mediatek.keyguard.ext.ICarrierTextExt")
public class DefaultCarrierTextExt implements ICarrierTextExt {

    @Override
    public CharSequence customizeCarrierTextCapital(CharSequence carrierText) {
        if (carrierText != null) {
            return carrierText.toString().toUpperCase();
        }
        return null;
    }

    @Override
    public CharSequence customizeCarrierText(CharSequence carrierText, CharSequence simMessage, int simId) {
        return carrierText;
    }

    @Override
    public boolean showCarrierTextWhenSimMissing(boolean isSimMissing, int simId) {
        return isSimMissing;
    }
}
