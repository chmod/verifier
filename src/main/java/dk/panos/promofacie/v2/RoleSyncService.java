package dk.panos.promofacie.v2;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.StructuredTaskScope;

@ApplicationScoped
public class RoleSyncService {

    @Inject
    RadixService radixService;

    @Inject
    CacheService cacheService;

    @Inject
    EventProducer eventProducer;

    private static final Logger LOG = Logger.getLogger(RoleSyncService.class);

    /**
     * Syncs all users in a guild - fetches current NFT holdings and updates roles
     */
    public Map<String, Object> syncGuild(String guildId) {
        try {
            List<String> allUsers = cacheService.getAllVerifiedUsers(guildId);
            LOG.info("Syncing " + allUsers.size() + " users in guild " + guildId);

            // Get role config to know which resources to check
            RoleConfig config = cacheService.getRoleConfig(guildId);
            if (config == null) {
                LOG.warn("No role config found for guild " + guildId);
                return Map.of("success", false, "error", "No role config");
            }

            // Get all addresses for these users
            Map<String, String> userToAddress = new HashMap<>();
            for (String userId : allUsers) {
                String address = cacheService.getAddress(userId);
                if (address != null) {
                    userToAddress.put(userId, address);
                }
            }

            List<String> addresses = new ArrayList<>(userToAddress.values());

            // Fetch holdings for all resources - Map<resourceAddress, Map<userAddress, List<nftIds>>>
            Map<String, Map<String, List<String>>> holdingsByResourceAndAddress = new HashMap<>();

            for (RoleConfig.ResourceRule resourceRule : config.resources()) {
                Map<String, List<String>> nftsByAddress = radixService.getAllNFTsForAddresses(
                        addresses,
                        resourceRule.resourceAddress()
                );
                holdingsByResourceAndAddress.put(resourceRule.resourceAddress(), nftsByAddress);
            }

            // Process each user with structured concurrency
            try (StructuredTaskScope.ShutdownOnFailure scope = new StructuredTaskScope.ShutdownOnFailure()) {
                List<StructuredTaskScope.Subtask<Void>> futures = userToAddress.entrySet().stream()
                        .map(entry -> scope.<Void>fork(() -> {
                            String userId = entry.getKey();
                            String userAddress = entry.getValue();

                            // Build holdings map for this user
                            Map<String, List<String>> userHoldings = new HashMap<>();
                            Map<String, List<NFTMetadata>> userMetadata = new HashMap<>();

                            for (RoleConfig.ResourceRule resourceRule : config.resources()) {
                                String resourceAddress = resourceRule.resourceAddress();
                                Map<String, List<String>> nftsByAddress = holdingsByResourceAndAddress.get(resourceAddress);
                                List<String> nftIds = nftsByAddress.getOrDefault(userAddress, List.of());
                                userHoldings.put(resourceAddress, nftIds);

                                if (!nftIds.isEmpty()) {
                                    List<NFTMetadata> metadata = radixService.getNFTMetadata(resourceAddress, nftIds);
                                    userMetadata.put(resourceAddress, metadata);
                                }
                            }

                            // Calculate roles
                            List<String> roles = calculateRolesForSync(config, userHoldings, userMetadata);

                            // Emit role update
                            eventProducer.emitRoleUpdate(userId, guildId, roles);

                            return null;
                        }))
                        .toList();

                scope.join();
                scope.throwIfFailed();
            }

            return Map.of(
                    "success", true,
                    "usersChecked", allUsers.size(),
                    "timestamp", System.currentTimeMillis()
            );

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.error("Guild sync interrupted for " + guildId, e);
            return Map.of("success", false, "error", "Interrupted");
        } catch (Exception e) {
            LOG.error("Guild sync failed for " + guildId, e);
            return Map.of("success", false, "error", e.getMessage());
        }
    }

    private List<String> calculateRolesForSync(
            RoleConfig config,
            Map<String, List<String>> holdingsByResource,
            Map<String, List<NFTMetadata>> metadataByResource) {

        Set<String> roles = new HashSet<>();

        // Process each resource
        for (RoleConfig.ResourceRule resourceRule : config.resources()) {
            List<String> nftIds = holdingsByResource.getOrDefault(resourceRule.resourceAddress(), List.of());
            int amount = nftIds.size();

            // Apply holding tiers
            for (RoleConfig.HoldingTier tier : resourceRule.holdingTiers()) {
                boolean matchesMin = tier.minAmount() == null || amount >= tier.minAmount();
                boolean matchesMax = tier.maxAmount() == null || amount <= tier.maxAmount();

                if (matchesMin && matchesMax && amount > 0) {
                    roles.add(tier.roleId());
                }
            }

            // Apply trait rules (only for NFTs)
            if (resourceRule.resourceType() == RoleConfig.ResourceType.NFT) {
                List<NFTMetadata> metadata = metadataByResource.getOrDefault(resourceRule.resourceAddress(), List.of());

                for (RoleConfig.TraitRule traitRule : Optional.ofNullable(resourceRule.traitRules()).orElse(Collections.emptyList())) {
                    boolean hasMatchingTrait = metadata.stream()
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