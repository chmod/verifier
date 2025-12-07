package dk.panos.promofacie.radix;

import dk.panos.promofacie.radix.model.*;
import dk.panos.promofacie.v2.EntityDetailsRequest;
import dk.panos.promofacie.v2.EntityDetailsResponse;
import dk.panos.promofacie.v2.NFTDataRequest;
import dk.panos.promofacie.v2.NFTDataResponse;
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

    @POST
    @Path("/state/entity/details")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    EntityDetailsResponse getEntityDetails(EntityDetailsRequest request);

    @POST
    @Path("/state/non-fungible/data")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    NFTDataResponse getNFTData(NFTDataRequest request);
}
