package dk.panos.promofacie.kafka.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * A single UTxO in a transaction payload.
 * {@code spentInTxHash} is present only for spent UTxOs; omitted for created ones.
 */
public record UtxoEntry(
        @JsonProperty("txHash")
        String txHash,
        @JsonProperty("outputIndex")
        int outputIndex,
        @JsonProperty("address")
        String address,
        @JsonProperty("amounts")
        List<AmountPayload> amounts,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @JsonProperty("spentInTxHash")
        String spentInTxHash
) {
    /** Convenience factory for created UTxOs (no spentInTxHash). */
    public static UtxoEntry created(String txHash, int outputIndex, String address, List<AmountPayload> amounts) {
        return new UtxoEntry(txHash, outputIndex, address, amounts, null);
    }

    /** Convenience factory for spent UTxOs. */
    public static UtxoEntry spent(String txHash, int outputIndex, String address, List<AmountPayload> amounts, String spentInTxHash) {
        return new UtxoEntry(txHash, outputIndex, address, amounts, spentInTxHash);
    }
}
