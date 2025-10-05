package dk.panos.promofacie.kafka;

import dk.panos.promofacie.db.Wallet;
import dk.panos.promofacie.kafka.model.VerificationOutcome;
import io.quarkus.panache.common.Parameters;
import jakarta.transaction.Transactional;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class VerificationConsumer {
    private static Logger log = LoggerFactory.getLogger(VerificationConsumer.class);

    @Incoming("verifier-rdx")
    @Transactional
    public void handleVerificationOutcome(VerificationOutcome verificationOutcome) {
        if ("WALLET_VERIFICATION".equals(verificationOutcome.getPurpose()) && verificationOutcome.isOutcome()) {
            Wallet wallet = Wallet.find("discordId = :discord_id", Parameters.with("discord_id", verificationOutcome.getUserId()))
                    .firstResult();
            if (wallet == null) {
                wallet = new Wallet();
                wallet.setDiscordId(verificationOutcome.getUserId());
            }
            wallet.setAddress(verificationOutcome.getAddress());
            wallet.persist();
        }
    }
}
