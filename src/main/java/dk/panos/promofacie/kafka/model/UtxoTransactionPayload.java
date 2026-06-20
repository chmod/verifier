package dk.panos.promofacie.kafka.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Kafka message emitted for every transaction (live or snapshot) that touches a tracked address.
 * {@code blockNumber} is omitted for snapshot messages where only slot is available.
 */
public record UtxoTransactionPayload(
        @JsonProperty("stakeAddress")
        String stakeAddress,
        @JsonProperty("txHash")
        String txHash,
        @JsonProperty("slot")
        long slot,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @JsonProperty("blockNumber")
        Long blockNumber,
        @JsonProperty("createdUtxos")
        List<UtxoEntry> createdUtxos,
        @JsonProperty("spentUtxos")
        List<UtxoEntry> spentUtxos,
        @JsonProperty("snapshot")
        boolean snapshot,
        @JsonProperty("forcedSync")
        boolean forcedSync
) {}
