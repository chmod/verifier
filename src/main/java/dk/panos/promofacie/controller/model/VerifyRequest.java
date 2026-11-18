package dk.panos.promofacie.controller.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record VerifyRequest(
        @JsonProperty("stakeAddress")
        String stakeAddress,
        @JsonProperty("address")
        String address,
        String signature,
        String key,
        String chain,
        // Explicit name: the global ObjectMapper uses snake_case and would otherwise expect "nonce_id"
        @JsonProperty("nonceId")
        String nonceId
) {
    /** Clients that predate nonce ids: the most recently issued challenge is used. */
    public VerifyRequest(String stakeAddress, String address, String signature, String key, String chain) {
        this(stakeAddress, address, signature, key, chain, null);
    }

    public String getResolvedAddress() {
        if (address != null && !address.isBlank()) {
            return address;
        }
        return stakeAddress;
    }
}