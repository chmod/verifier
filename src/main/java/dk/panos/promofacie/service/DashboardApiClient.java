package dk.panos.promofacie.service;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import java.util.List;
import java.util.Map;

@Path("/api")
@RegisterRestClient(configKey = "dashboard-api")
public interface DashboardApiClient {

    @GET
    @Path("/fetch-policies")
    List<Map<String, String>> fetchPolicies(@HeaderParam("Authorization") String authorization);
}
