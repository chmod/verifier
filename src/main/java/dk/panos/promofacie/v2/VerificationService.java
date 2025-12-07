package dk.panos.promofacie.v2;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;

import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.StructuredTaskScope;

@ApplicationScoped
class VerificationService {

    @Inject
    RadixService radixService;

    @Inject
    CacheService cacheService;

    @Inject
    EventProducer eventProducer;

    public VerificationResult verifyUser(VerificationRequest request) {
        try {
            // Use structured concurrency to parallelize operations
            try (StructuredTaskScope.ShutdownOnFailure scope = new StructuredTaskScope.ShutdownOnFailure()) {

                // Fork: Get role config to know which resources to check
                StructuredTaskScope.Subtask<RoleConfig> configFuture = scope.fork(() ->
                        cacheService.getRoleConfig(request.guildId())
                );

                // Fork: Link user to guild
                scope.fork(() -> {
                    cacheService.linkUserToGuild(request.discordId(), request.guildId());
                    return null;
                });

                scope.join();
                scope.throwIfFailed();

                RoleConfig config = configFuture.get();
                if (config == null) {
                    return new VerificationResult(false, "Guild not configured", 0, List.of());
                }

                // Fetch holdings for all resources in parallel
                Map<String, ResourceHoldings> holdingsByResource = new HashMap<>();

                try (StructuredTaskScope.ShutdownOnFailure fetchScope = new StructuredTaskScope.ShutdownOnFailure()) {

                    Map<String, StructuredTaskScope.Subtask<ResourceHoldings>> futures = new HashMap<>();

                    for (RoleConfig.ResourceRule resourceRule : config.resources()) {
                        StructuredTaskScope.Subtask<ResourceHoldings> future = fetchScope.fork(() ->
                                fetchResourceHoldings(request.address(), resourceRule.resourceAddress())
                        );
                        futures.put(resourceRule.resourceAddress(), future);
                    }

                    fetchScope.join();
                    fetchScope.throwIfFailed();

                    // Collect results
                    for (Map.Entry<String, StructuredTaskScope.Subtask<ResourceHoldings>> entry : futures.entrySet()) {
                        holdingsByResource.put(entry.getKey(), entry.getValue().get());
                    }
                }

                // Calculate roles based on holdings and traits
                List<String> roles = calculateRoles(config, holdingsByResource);

                // Emit role update event
                eventProducer.emitRoleUpdate(request.discordId(), request.guildId(), roles);

                // Calculate total NFT count for response
                int totalNfts = holdingsByResource.values().stream()
                        .mapToInt(h -> h.nftIds().size())
                        .sum();

                return new VerificationResult(
                        true,
                        "Verification successful",
                        totalNfts,
                        roles
                );

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new VerificationResult(false, "Verification interrupted", 0, List.of());
            } catch (ExecutionException e) {
                return new VerificationResult(false, "Error: " + e.getCause().getMessage(), 0, List.of());
            }

        } catch (Exception e) {
            return new VerificationResult(false, "Error: " + e.getMessage(), 0, List.of());
        }
    }

    private ResourceHoldings fetchResourceHoldings(String address, String resourceAddress) {
        List<String> nftIds = radixService.getAllNFTsForAddress(address, resourceAddress);

        if (nftIds.isEmpty()) {
            return new ResourceHoldings(nftIds, List.of());
        }

        // Fetch metadata for NFTs
        List<NFTMetadata> metadata = radixService.getNFTMetadata(resourceAddress, nftIds);

        return new ResourceHoldings(nftIds, metadata);
    }

    private List<String> calculateRoles(RoleConfig config, Map<String, ResourceHoldings> holdingsByResource) {
        Set<String> roles = new HashSet<>();

        // Process each resource
        for (RoleConfig.ResourceRule resourceRule : config.resources()) {
            ResourceHoldings holdings = holdingsByResource.get(resourceRule.resourceAddress());
            if (holdings == null) continue;

            int amount = holdings.nftIds().size();

            // Apply holding tiers for this resource
            for (RoleConfig.HoldingTier tier : resourceRule.holdingTiers()) {
                boolean matchesMin = tier.minAmount() == null || amount >= tier.minAmount();
                boolean matchesMax = tier.maxAmount() == null || amount <= tier.maxAmount();

                if (matchesMin && matchesMax && amount > 0) {
                    roles.add(tier.roleId());
                }
            }

            // Apply trait rules for this resource (only for NFTs)
            if (resourceRule.resourceType() == RoleConfig.ResourceType.NFT) {
                for (RoleConfig.TraitRule traitRule : Optional.ofNullable(resourceRule.traitRules()).orElse(Collections.emptyList())) {
                    boolean hasMatchingTrait = holdings.metadata().stream()
                            .anyMatch(nft -> {
                                String traitValue = nft.traits().get(traitRule.traitName());
                                return traitValue != null && traitValue.equals(traitRule.traitValue());
                            });

                    if (hasMatchingTrait) {
                        roles.add(traitRule.roleId());
                    }
                }
            }
        }

        return new ArrayList<>(roles);
    }

}
