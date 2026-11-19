package dk.panos.promofacie.service.opensea;

import java.util.List;

/**
 * The fields of OpenSea's {@code GET /api/v2/accounts/{address}?include=wallets} response that verification relies on.
 *
 * @param address the profile's primary address
 * @param bio     the profile bio ("" when empty)
 * @param wallets  public registered wallets of the profile. OpenSea only registers a wallet after a SIWX signature from
 *                 it; private and watched (unproven) wallets are never returned.
 * @param username the profile's public username, or null. Display only: never used for any decision.
 */
public record OpenSeaAccount(String address, String bio, List<String> wallets, String username) {

    public OpenSeaAccount {
        wallets = wallets == null ? List.of() : List.copyOf(wallets);
    }

    public OpenSeaAccount(String address, String bio, List<String> wallets) {
        this(address, bio, wallets, null);
    }

    public OpenSeaAccount(String address, String bio) {
        this(address, bio, List.of(), null);
    }
}
