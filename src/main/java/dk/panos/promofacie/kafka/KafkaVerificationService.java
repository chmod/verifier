package dk.panos.promofacie.kafka;

import dk.panos.promofacie.kafka.model.VerificationOutcome;
import dk.panos.promofacie.redis.redis_model.Verification;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;

@ApplicationScoped
public class KafkaVerificationService {
    @Inject
    @Channel("verifierout-rdx")
    Emitter<VerificationOutcome> successEmitter;


    private static final Logger log = LoggerFactory.getLogger(KafkaVerificationService.class);

    public void sendSuccess(Verification verification) {
        String address = verification.address();
        successEmitter.send(new VerificationOutcome(address, verification.discordId(), "WALLET_VERIFICATION", true))
                .whenComplete((result, throwable) -> {
                    if (throwable != null) {
                        log.error("Failed to send success verification for address: {}", address, throwable);
                    } else {
                        log.debug("Successfully sent success verification for address: {}", address);
                    }
                });
    }

    public void sendFailure(Verification verification) {
        String address = verification.address();
        successEmitter.send(new VerificationOutcome(address, verification.discordId(), "WALLET_VERIFICATION", false))
                .whenComplete((result, throwable) -> {
                    if (throwable != null) {
                        log.error("Failed to send failure verification for address: {}", address, throwable);
                    } else {
                        log.debug("Successfully sent failure verification for address: {}", address);
                    }
                });
    }
}