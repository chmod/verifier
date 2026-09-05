package dk.panos.promofacie.controller.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record VerifyRequest(
        @JsonProperty("stakeAddress")
        String stakeAddress,
        @JsonProperty("address")
        String address,
        String signature,
        String key,
        String chain
) {
    public String getResolvedAddress() {
        if (address != null && !address.isBlank()) {
            return address;
        }
        return stakeAddress;
    }
}