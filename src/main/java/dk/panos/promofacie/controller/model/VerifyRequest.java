package dk.panos.promofacie.controller.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record VerifyRequest(
        @JsonProperty("stakeAddress")
        String stakeAddress, String signature, String key) {}