package dk.panos.promofacie.kafka.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TransactionMessage(
        @JsonProperty("quantity")
        String quantity,
        @JsonProperty("name")
        String name,
        @JsonProperty("url")
        String url,
        @JsonProperty("policyId")
        String policyId,
        @JsonProperty("price")
        String price,
        @JsonProperty("type")
        String type,
        @JsonProperty("txHash")
        String txHash,
        @JsonProperty("offerType")
        String offerType,
        @JsonProperty("requestedAssetNameHex")
        String requestedAssetNameHex,
        @JsonProperty("bundledMultiPolicy")
        Boolean bundledMultiPolicy
) {}
