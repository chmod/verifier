package dk.promofacie.wallet_verification.kafka;

import dk.promofacie.wallet_verification.db.Wallet;
import dk.promofacie.wallet_verification.kafka.model.VerificationOutcome;
import dk.promofacie.wallet_verification.redis.RedisVerificationService;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.quarkus.panache.common.Parameters;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
@WithTransaction
public class VerificationConsumer {
    @Inject
    RedisVerificationService redisVerificationService;
    private static Logger log = LoggerFactory.getLogger(VerificationConsumer.class);

    @Incoming("verifier-rdx")
    public Uni<Void> handleVerificationOutcome(VerificationOutcome verificationOutcome) {
        if (verificationOutcome.getPurpose().equals("WALLET_VERIFICATION") && verificationOutcome.isOutcome()) {
            return Wallet.<Wallet>find("discordId = :discord_id", Parameters.with("discord_id", verificationOutcome.getUserId()))
                    .firstResult()
                    .onItem().ifNull().continueWith(() -> {
                        Wallet wallet = new Wallet();
                        wallet.setDiscordId(verificationOutcome.getUserId());
                        return wallet;
                    })
                    .onItem().transformToUni(wallet -> {
                        wallet.setAddress(verificationOutcome.getAddress());
                        return wallet.persist();
                    }).replaceWithVoid();
        }
        return Uni.createFrom().voidItem();
    }
}
