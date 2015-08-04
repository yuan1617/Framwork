package com.mediatek.keyguard.ext;

/**
 * Carrier text related interface
 */
public interface ICarrierTextExt {

    /**
     * Convert the carrier string to upper case.
     *
     * @param CarrierText The carrier String.
     * @return to upper case Carrier String.
     */
    CharSequence customizeCarrierTextCapital(CharSequence CarrierText);

    /**
     * For CU, display "No SIM CARD" without "NO SERVICE" when
     * there is no sim card in device and carrier's service is
     * ready.
     *
     * @param simMessage
     *          the first part of common carrier text
     * @param original
     *          common carrier text
     * @param simId
     *          current sim id
     *
     * @return ture if sim is in service
     */
    CharSequence customizeCarrierText(CharSequence CarrierText, CharSequence simMessage, int simId);

    /**
     * The carrier texts are displayed or not depending on the SIM card inserted status.
     * The default rule is to hide the carrier text if the SIM card is missing.
     * Operator plugins may override this function to customize their rules.
     *
     * For CT customization.
     * Don't hide carrier text even there is no sim card inserted.
     *
     * @param isMissing true if there is no sim card inserted.
     * @param simId  sim card id.
     *
     * @return whether hide carrier text
     *
     *
     * For CU 5.0 feature when simNum==2,one Servicestate is not in service,just show sim one infomation
     *
     * @param isSimMissing: simMissing(not insert simcard)
     *  return ture if one sim is out of service && no sim card is pin locked && two sim is not missing
     */
    boolean showCarrierTextWhenSimMissing(boolean isSimMissing, int simId);
}
