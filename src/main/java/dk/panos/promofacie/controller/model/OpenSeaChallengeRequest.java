package dk.panos.promofacie.controller.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Body of POST /api/wallet/opensea/challenge. */
public record OpenSeaChallengeRequest(@JsonProperty("address") String address) {
}
