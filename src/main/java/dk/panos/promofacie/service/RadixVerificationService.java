package dk.panos.promofacie.service;

import dk.panos.promofacie.radix.RadixClient;
import dk.panos.promofacie.radix.model.GetTransactionsStreamRequest;
import dk.panos.promofacie.radix.model.GetTransactionsStreamResponse;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.time.ZonedDateTime;
import java.util.List;

@ApplicationScoped
public class RadixVerificationService implements BlockchainVerificationService {
    private final String depositAddress = "account_rdx129ljz7j2u3fln7tmkpxldtdelvyl26qaxv2emsucuq38f40tkp3ess";
    @RestClient
    RadixClient radixClient;

    @Override
    public boolean transactionExists(String address, String userId, ZonedDateTime zdt) {
        GetTransactionsStreamResponse response = radixClient.getTransactions(
                new GetTransactionsStreamRequest(100, List.of(address), List.of(depositAddress), new GetTransactionsStreamRequest.FromLedgerState(zdt))
        );
        return true;
    }
}
