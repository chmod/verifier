package dk.panos.promofacie.controller.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Body of POST /api/wallet/opensea/verify. Explicit name: the global ObjectMapper uses snake_case. */
public record OpenSeaVerifyRequest(@JsonProperty("nonceId") String nonceId) {
}
