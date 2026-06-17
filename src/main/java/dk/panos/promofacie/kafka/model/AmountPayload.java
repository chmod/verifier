package dk.panos.promofacie.kafka.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * A single token amount within a UTxO, enriched with CIP-25 traits when available.
 * {@code traits} is omitted from JSON if the asset has no label-721 metadata.
 */
public record AmountPayload(
        @JsonProperty("unit")
        String unit,
        @JsonProperty("policyId")
        String policyId,
        @JsonProperty("assetName")
        String assetName,
        @JsonProperty("quantity")
        String quantity,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @JsonProperty("traits")
        Map<String, Object> traits
) {}

