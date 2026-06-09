package dk.panos.promofacie.kafka.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * A single UTxO in a transaction payload.
 * {@code spentInTxHash} is present only for spent UTxOs; omitted for created ones.
 */
public record UtxoEntry(
        String txHash,
        int outputIndex,
        String address,
        List<AmountPayload> amounts,
        @JsonInclude(JsonInclude.Include.NON_NULL)
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
