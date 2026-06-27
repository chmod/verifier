package dk.panos.promofacie.kafka.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TransactionMessage(
        @JsonProperty("quantity")
        String quantity,
        @JsonProperty("name")
        String name,
        @JsonProperty("url")
        String url
) {}
