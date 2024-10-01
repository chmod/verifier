package dk.panos.promofacie.radix.model;

import java.time.ZonedDateTime;
import java.util.List;

public record GetTransactionsStreamRequest(
        int limitPerPage,
        List<String> manifestAccountsWithdrawnFromFilter,
        List<String> manifestAccountsDepositedIntoFilter,
        FromLedgerState fromLedgerState
) {
    public static final String KIND_FILTER = "User";

    public record FromLedgerState(
            ZonedDateTime timestamp
    ) {
    }

    public String getKindFilter() {
        return KIND_FILTER;
    }
}
