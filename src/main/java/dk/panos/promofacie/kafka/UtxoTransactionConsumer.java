package dk.panos.promofacie.kafka;

import dk.panos.promofacie.kafka.model.UtxoTransactionPayload;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class UtxoTransactionConsumer {
    private static final Logger log = LoggerFactory.getLogger(UtxoTransactionConsumer.class);

    @Incoming("cardano-utxo-updates")
    public void consumeUtxo(UtxoTransactionPayload payload) {
        log.info("Received UTXO update: {}", payload);
    }
}