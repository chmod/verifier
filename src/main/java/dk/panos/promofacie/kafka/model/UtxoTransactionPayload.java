package dk.panos.promofacie.kafka.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Kafka message emitted for every transaction (live or snapshot) that touches a tracked address.
 * {@code blockNumber} is omitted for snapshot messages where only slot is available.
 */
public record UtxoTransactionPayload(
        String txHash,
        long slot,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Long blockNumber,
        List<UtxoEntry> createdUtxos,
        List<UtxoEntry> spentUtxos
) {}
