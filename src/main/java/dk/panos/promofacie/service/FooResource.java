package dk.panos.promofacie.service;

import dk.panos.promofacie.db.Wallet;
import dk.panos.promofacie.radix.RadixClient;
import dk.panos.promofacie.radix.model.GetTransactionsStreamResponse;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.util.List;

@Path("/")
@ApplicationScoped
@WithTransaction
public class FooResource {
    @RestClient
    RadixClient radixClient;

    @GET
    public Uni<Void> foo() {
        Uni<GetTransactionsStreamResponse> transactions = radixClient.getTransactionsForAddress(new GetTransactionsStreamRequestNoLimit(List.of("account_rdx129wl7trqd7ttwfd2nx9tf3qgag6n2e5tjs3t9cfgslpp3yxh526e2u")));
        return transactions.onItem().transformToMulti(tx -> Multi.createFrom().iterable(tx.items()))
                .onItem()
                .transformToUni(item -> {
                    String sourceAddr = item.affectedGlobalEntities().stream().filter(str -> str.startsWith("account_rdx"))
                            .filter(str -> !str.equalsIgnoreCase("account_rdx129wl7trqd7ttwfd2nx9tf3qgag6n2e5tjs3t9cfgslpp3yxh526e2u"))
                            .findFirst().get();
                    if(item.message()==null) {
                        return Uni.createFrom().voidItem();
                    }
                    String discordId = item.message().content().value();

                    Wallet wallet = new Wallet();
                    wallet.setDiscordId(discordId);
                    wallet.setAddress(sourceAddr);
                    return wallet.persist().replaceWithVoid();
                }).concatenate().collect().asList()
                .replaceWithVoid();
    }

    public record GetTransactionsStreamRequestNoLimit(
            List<String> manifestAccountsDepositedIntoFilter

    ) {
        public static final OptIns OPT_INS = new OptIns(
                true
        );

        public OptIns getOptIns() {
            return OPT_INS;
        }
    }

    public record OptIns(
            boolean affected_global_entities
    ) {
    }
}
