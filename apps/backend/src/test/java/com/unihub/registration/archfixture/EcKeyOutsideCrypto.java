package com.unihub.registration.archfixture;

import com.nimbusds.jose.jwk.ECKey;

/**
 * A Registration class outside the crypto package that holds an EC key: the private key escaping to
 * where it must not be (D47). A plain class, never a bean.
 */
public final class EcKeyOutsideCrypto {
    private final ECKey key;

    public EcKeyOutsideCrypto(ECKey key) {
        this.key = key;
    }

    public boolean hasPrivateMember() {
        return key.isPrivate();
    }
}
