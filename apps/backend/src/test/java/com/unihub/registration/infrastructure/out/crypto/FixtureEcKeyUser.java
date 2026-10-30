package com.unihub.registration.infrastructure.out.crypto;

import com.nimbusds.jose.jwk.ECKey;

/**
 * Holds an EC key inside the crypto package, which is allowed (D47). A plain class, never a bean.
 */
public final class FixtureEcKeyUser {
    private final ECKey key;

    public FixtureEcKeyUser(ECKey key) {
        this.key = key;
    }

    public boolean hasPrivateMember() {
        return key.isPrivate();
    }
}
