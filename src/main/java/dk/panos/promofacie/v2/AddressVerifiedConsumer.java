package dk.panos.promofacie.v2;

import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.StructuredTaskScope;

@ApplicationScoped
public class AddressVerifiedConsumer {

    @Inject
    CacheService cacheService;

    @Inject
    VerificationService verificationService;

    @Inject
    JDA discordAPI;

    private static final Logger LOG = Logger.getLogger(AddressVerifiedConsumer.class);

    /**
     * When an address is verified, check all guilds the user is in
     * and assign NFT roles accordingly
     */
    @Incoming("address-verified")
    @RunOnVirtualThread
    public void handleAddressVerified(AddressVerifiedEvent event) {
        String discordId = event.discordId();
        String address = event.address();

        LOG.info("Processing address verification for user " + discordId);


        // Get all guilds where bot is present AND guild is configured
        List<String> configuredGuilds = cacheService.getAllGuilds();

        List<VerificationRequest> verificationRequests = new ArrayList<>();

        // Check each configured guild synchronously
        for (String guildId : configuredGuilds) {
            Guild guild = discordAPI.getGuildById(guildId);
            if (guild == null) {
                LOG.debug("Guild " + guildId + " not found");
                continue;
            }

            try {
                // Synchronously check if user is member (blocks on virtual thread - OK)
                Member member = guild.retrieveMemberById(discordId).complete();

                if (member != null) {
                    LOG.info("User " + discordId + " is member of guild " + guildId + ", will check NFTs");

                    VerificationRequest request = new VerificationRequest(
                            discordId,
                            guildId,
                            address,
                            System.currentTimeMillis()
                    );

                    verificationRequests.add(request);
                }
            } catch (Exception e) {
                // User not in guild or error retrieving
                LOG.debug("User " + discordId + " not in guild " + guildId + " or error: " + e.getMessage());
            }
        }

        if (verificationRequests.isEmpty()) {
            LOG.info("User " + discordId + " is not in any configured guilds");
            return;
        }

        // Now verify NFTs for all guilds in parallel using structured concurrency
        LOG.info("Verifying NFTs for user " + discordId + " in " + verificationRequests.size() + " guild(s)");

        try (StructuredTaskScope.ShutdownOnFailure scope = new StructuredTaskScope.ShutdownOnFailure()) {

            List<StructuredTaskScope.Subtask<VerificationResult>> futures = verificationRequests.stream()
                    .map(req -> scope.fork(() -> verificationService.verifyUser(req)))
                    .toList();

            scope.join();
            scope.throwIfFailed();

            // Log results
            for (int i = 0; i < futures.size(); i++) {
                VerificationResult result = futures.get(i).get();
                String guildId = verificationRequests.get(i).guildId();

                if (result.success()) {
                    LOG.info("User " + discordId + " verified in guild " + guildId +
                            " with " + result.nftCount() + " NFTs, " + result.roles().size() + " roles");
                } else {
                    LOG.info("User " + discordId + " has no NFTs in guild " + guildId + ": " + result.message());
                }
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.error("Address verification processing interrupted for user " + discordId, e);
        } catch (Exception e) {
            LOG.error("Failed to process address verification for user " + discordId, e);
        }
    }
}