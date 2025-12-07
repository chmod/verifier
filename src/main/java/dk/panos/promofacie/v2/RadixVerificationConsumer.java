package dk.panos.promofacie.v2;

import dk.panos.promofacie.kafka.model.VerificationOutcome;
import io.quarkus.virtual.threads.VirtualThreads;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;

@ApplicationScoped
public class RadixVerificationConsumer {

    @Inject
    CacheService cacheService;

    @Inject
    EventProducer eventProducer;

    private static final Logger LOG = Logger.getLogger(RadixVerificationConsumer.class);

    /**
     * Listens to your existing verifier-rdx topic
     * When address is verified via transaction, store it and trigger NFT checks
     */
    @Incoming("verifier-rdx")
    @RunOnVirtualThread
    public void handleVerificationOutcome(VerificationOutcome verificationOutcome) {
        if (!"WALLET_VERIFICATION".equals(verificationOutcome.getPurpose()) || !verificationOutcome.isOutcome()) {
            return;
        }

        String discordId = verificationOutcome.getUserId();
        String address = verificationOutcome.getAddress();

        LOG.info("Address verified for user " + discordId + ": " + address);

        // Store address in Redis (no DB needed)
        cacheService.linkAddress(discordId, address);

        // Emit event that address is verified - other consumers will handle NFT checks
        eventProducer.emitAddressVerified(discordId, address);

        LOG.info("Address verification completed for user " + discordId);
    }
}