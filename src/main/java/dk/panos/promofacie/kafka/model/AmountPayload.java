package dk.panos.promofacie.kafka.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * A single token amount within a UTxO, enriched with CIP-25 traits when available.
 * {@code traits} is omitted from JSON if the asset has no label-721 metadata.
 */
public record AmountPayload(
        String unit,
        String policyId,
        String assetName,
        String quantity,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Map<String, Object> traits
) {}
