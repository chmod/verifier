package dk.panos.promofacie.controller.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import dk.panos.promofacie.db.Chain;

/**
 * A pending challenge. Expiry is handled by the nonce store's TTL.
 *
 * @param message the exact text the wallet must sign
 * @param chain   the chain the challenge was issued for; null for legacy challenges requested without a chain
 * @param address for EVM (SIWE) challenges, the EIP-55 address embedded in the message; otherwise null
 */
public record NonceEntry(String message, Chain chain, String address) {

    /** True for an EIP-4361 challenge bound to an EVM address: the only kind accepted for EVM verification. */
    @JsonIgnore
    public boolean isSiwe() {
        return chain == Chain.ROBINHOOD && address != null;
    }
}
