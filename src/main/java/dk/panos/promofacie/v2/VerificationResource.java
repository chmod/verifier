package dk.panos.promofacie.v2;

import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.StructuredTaskScope;
import java.util.stream.Collectors;

@Path("/api/verification")
@ApplicationScoped
public class VerificationResource {

    @Inject
    CacheService cacheService;

    @Inject
    VerificationService verificationService;

    @Inject
    RoleSyncService roleSyncService;

    @POST
    @Path("/config/{guildId}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, String> configureRoles(
            @PathParam("guildId") String guildId,
            RoleConfig config
    ) {
        cacheService.cacheRoleConfig(guildId, config);
        return Map.of("status", "configured");
    }

    @GET
    @Path("/status/{discordId}")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> getVerificationStatus(
            @PathParam("discordId") String discordId
    ) {
        String address = cacheService.getAddress(discordId);
        if (address == null) {
            return Map.of("verified", false);
        }

        List<NFTMetadata> nfts = cacheService.getUserNFTs(discordId);
        return Map.of(
                "verified", true,
                "address", address,
                "nftCount", nfts != null ? nfts.size() : 0
        );
    }

    @POST
    @Path("/verify")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public VerificationResult verify(VerificationRequest request) {
        return verificationService.verifyUser(request);
    }

    @POST
    @Path("/batch-verify")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public List<VerificationResult> batchVerify(List<VerificationRequest> requests) {
        // Use structured concurrency to verify multiple users in parallel
        try (StructuredTaskScope.ShutdownOnFailure scope = new StructuredTaskScope.ShutdownOnFailure()) {
            List<StructuredTaskScope.Subtask<VerificationResult>> futures = requests.stream()
                    .map(req -> scope.fork(() -> verificationService.verifyUser(req)))
                    .toList();

            scope.join();
            scope.throwIfFailed();

            return futures.stream()
                    .map(StructuredTaskScope.Subtask::get)
                    .collect(Collectors.toList());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Batch verification interrupted", e);
        } catch (ExecutionException e) {
            throw new RuntimeException("Batch verification failed", e.getCause());
        }
    }

    @POST
    @Path("/sync/{guildId}")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> syncGuild(@PathParam("guildId") String guildId) {
        return roleSyncService.syncGuild(guildId);
    }
}