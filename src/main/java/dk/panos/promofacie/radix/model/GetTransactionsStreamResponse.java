package dk.panos.promofacie.radix.model;

import java.util.List;

// Define the top-level record
public record GetTransactionsStreamResponse(
        LedgerState ledgerState,
        List<Item> items
) {
    // Define the nested record for 'ledger_state'
    public record LedgerState(
            String network,
            int stateVersion,
            String proposerRoundTimestamp,
            int epoch,
            int round
    ) {
    }

    // Define the nested record for 'items'
    public record Item(
            String transactionStatus,
            String errorMessage,
            int stateVersion,
            String payloadHash,
            Message message,
            Receipt receipt,
            List<String> affectedGlobalEntities
    ) {
    }

    public record MessageContent(String type, String value){}
    public record Message(String type, MessageContent content){}

    // Define the nested record for 'receipt' (only the 'status' is kept)
    public record Receipt(
            String status
    ) {
    }
}