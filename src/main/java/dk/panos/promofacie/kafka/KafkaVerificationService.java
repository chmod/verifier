package dk.panos.promofacie.kafka;

import dk.panos.promofacie.kafka.model.UtxoTransactionPayload;
import dk.panos.promofacie.kafka.model.VerificationOutcome;
import dk.panos.promofacie.redis.redis_model.Verification;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;

@ApplicationScoped
public class KafkaVerificationService {
    private static final Logger log = LoggerFactory.getLogger(KafkaVerificationService.class);

    @Incoming("cardano-utxo-updates")
    public void consumeUtxo(UtxoTransactionPayload payload) {
        log.info("Received UTXO update: {}", payload);
    }
}