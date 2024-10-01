package dk.panos.promofacie.radix;

import dk.panos.promofacie.radix.model.AddressStateDetails;
import dk.panos.promofacie.radix.model.GetAddressDetails;
import dk.panos.promofacie.radix.model.GetTransactionsStreamRequest;
import dk.panos.promofacie.radix.model.GetTransactionsStreamResponse;
import dk.panos.promofacie.service.FooResource;
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
    @Path("/stream/transactions")
    @Produces(MediaType.APPLICATION_JSON)
    Uni<GetTransactionsStreamResponse> getTransactionsForAddress(FooResource.GetTransactionsStreamRequestNoLimit request);

    @POST
    @Path("/state/entity/details")
    Uni<AddressStateDetails> getAddressStateDetails(GetAddressDetails request);
}
