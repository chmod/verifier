package dk.promofacie.wallet_verification.radix;

import dk.promofacie.wallet_verification.radix.model.AddressStateDetails;
import dk.promofacie.wallet_verification.radix.model.GetAddressDetails;
import dk.promofacie.wallet_verification.radix.model.GetTransactionsStreamRequest;
import dk.promofacie.wallet_verification.radix.model.GetTransactionsStreamResponse;
import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

@RegisterRestClient(configKey="radix-api")
public interface RadixClient {
    @POST
    @Path("/stream/transactions")
    @Produces(MediaType.APPLICATION_JSON)
    Uni<GetTransactionsStreamResponse> getTransactions(GetTransactionsStreamRequest request);

    @POST
    @Path("/state/entity/details")
    Uni<AddressStateDetails> getAddressStateDetails(GetAddressDetails request);
}
