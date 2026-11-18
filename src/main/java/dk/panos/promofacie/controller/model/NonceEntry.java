package dk.panos.promofacie.controller.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import dk.panos.promofacie.db.Chain;

/**
 * A pending challenge. Expiry is handled by the nonce store's TTL.
 *
 * @param message the exact text the wallet must sign, or for {@link ProofMethod#OPENSEA_BIO} the code that must
 *                appear in the OpenSea bio
 * @param chain   the chain the challenge was issued for; null for legacy challenges requested without a chain
 * @param address for sign-in challenges (EVM: SIWE, Solana: SIWS) and OpenSea bio challenges, the address the
 *                challenge is bound to; otherwise null
 * @param method  how ownership is proven. Kept strictly apart: a bio code must never be accepted as a signed message
 *                (it has no domain binding) and a signature challenge must never be satisfied through a bio.
 */
public record NonceEntry(String message, Chain chain, String address, ProofMethod method) {

    public enum ProofMethod { SIGNATURE, OPENSEA_BIO }

    public NonceEntry {
        if (method == null) {
            method = ProofMethod.SIGNATURE;
        }
    }

    public NonceEntry(String message, Chain chain, String address) {
        this(message, chain, address, ProofMethod.SIGNATURE);
    }

    @JsonIgnore
    public boolean isSignature() {
        return method == ProofMethod.SIGNATURE;
    }

    /**
     * True for a sign-in challenge (EIP-4361 for EVM, SIWS for Solana) issued for {@code expectedChain} and bound to
     * an address: the only kind accepted when verifying those chains by signature.
     */
    @JsonIgnore
    public boolean isSignInFor(Chain expectedChain) {
        return isSignature() && chain == expectedChain && address != null;
    }
}
