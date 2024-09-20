package dk.promofacie.wallet_verification.kafka;

import dk.promofacie.wallet_verification.kafka.model.VerificationOutcome;
import dk.promofacie.wallet_verification.redis.redis_model.Verification;
import io.smallrye.mutiny.Uni;
import io.smallrye.reactive.messaging.MutinyEmitter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Channel;

@ApplicationScoped
public class KafkaVerificationService {
    @Inject
    @Channel("verifierout-rdx")
    MutinyEmitter<VerificationOutcome> successEmitter;

    public Uni<Void> sendSuccess(Verification verification) {
        String address = verification.address();
        return successEmitter.send(new VerificationOutcome(address, verification.discordId(), "WALLET_VERIFICATION", true));
    }

    public Uni<Void> sendFailure(Verification verification) {
        String address = verification.address();
        return successEmitter.send(new VerificationOutcome(address, verification.discordId(), "WALLET_VERIFICATION", false));
    }
}
