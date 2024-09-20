package dk.promofacie.wallet_verification.service;

import dk.promofacie.wallet_verification.radix.RadixClient;
import dk.promofacie.wallet_verification.radix.model.GetTransactionsStreamRequest;
import dk.promofacie.wallet_verification.radix.model.GetTransactionsStreamResponse;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.time.ZonedDateTime;
import java.util.List;

@ApplicationScoped
public class RadixVerificationService implements BlockchainVerificationService {
    private final String depositAddress = "account_rdx129wl7trqd7ttwfd2nx9tf3qgag6n2e5tjs3t9cfgslpp3yxh526e2u";
    @RestClient
    RadixClient radixClient;

    @Override
    public Uni<Boolean> transactionExists(String address, String userId, ZonedDateTime zdt) {

        Uni<GetTransactionsStreamResponse> transactions = radixClient.getTransactions(new GetTransactionsStreamRequest(10, List.of(address), List.of(depositAddress), new GetTransactionsStreamRequest.FromLedgerState(zdt)));

        return transactions
                .onItem().transformToUni(response -> {
                    return Multi.createFrom().iterable(response.items())
                            .select()
                            .where(item -> item.transactionStatus().equalsIgnoreCase("CommittedSuccess"))
                            .select()
                            .where(item -> item.message().content().value().equals(userId))
                            .toUni();
                }).onItem()
                .transform(item -> {
                    if (item != null) {
                        return true;
                    }
                    return false;
                }).onFailure().recoverWithItem(false);
    }
}
