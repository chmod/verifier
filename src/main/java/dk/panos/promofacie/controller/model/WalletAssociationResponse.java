package dk.panos.promofacie.controller.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record WalletAssociationResponse(
        String id,

        @JsonProperty("associationId")
        String associationId,

        @JsonProperty("stakeAddress")
        String stakeAddress,

        @JsonProperty("discordId")
        String discordId,

        String chain
) {}
