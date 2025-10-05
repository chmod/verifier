package dk.panos.promofacie.radix;

import dk.panos.promofacie.radix.model.*;
import jakarta.ws.rs.Consumes;
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
    GetTransactionsStreamResponse getTransactions(GetTransactionsStreamRequest request);

    @POST
    @Path("/state/entity/page/non-fungible-vaults")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    GetNonFungibleVaultsResponse nonFungibleVaults(GetNonFungibleVaultsRequest request);
}
