package com.unihub.registration.api.archfixture;

import com.nimbusds.jose.jwk.ECKey;

/**
 * A class in {@code registration.api} that hands out a JOSE key type: the boundary leaking the
 * library (D47). A plain class, never a bean.
 */
public final class JoseInApi {

    public ECKey current(ECKey key) {
        return key;
    }
}
