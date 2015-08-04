package com.mediatek.gba;

import com.mediatek.gba.NafSessionKey;

/**
 * @hide
 */
interface IGbaService {
    int getGbaSupported();
    boolean isGbaKeyExpired(String nafFqdn, in byte[] nafSecurProtocolId);
    NafSessionKey runGbaAuthentication(in String nafFqdn, in byte[] nafSecurProtocolId, boolean forceRun);
}