package dk.panos.promofacie.service;

import dk.panos.promofacie.service.model.PolicyInfo;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import java.util.Map;

@Path("/api")
@RegisterRestClient(configKey = "dashboard-api")
public interface DashboardApiClient {

    @GET
    @Path("/fetch-policies")
    Map<String, PolicyInfo> fetchPolicies(@HeaderParam("Authorization") String authorization);
}
