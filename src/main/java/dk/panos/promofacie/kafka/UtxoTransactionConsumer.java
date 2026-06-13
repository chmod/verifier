package dk.panos.promofacie.kafka;

import dk.panos.promofacie.kafka.model.UtxoTransactionPayload;
import dk.panos.promofacie.service.CardanoRoleService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class UtxoTransactionConsumer {
    private static final Logger log = LoggerFactory.getLogger(UtxoTransactionConsumer.class);

    @Inject
    CardanoRoleService cardanoRoleService;

    @Incoming("cardano-utxo-updates")
    public void consumeUtxo(UtxoTransactionPayload payload) {
        if (payload == null || payload.stakeAddress() == null) {
            log.warn("Received null or incomplete UtxoTransactionPayload — skipping");
            return;
        }
        log.info("Received UTXO update for stakeAddress: {}", payload.stakeAddress());
        try {
            cardanoRoleService.checkAndUpdateRoles(payload.stakeAddress());
        } catch (Exception e) {
            log.error("Failed to reactively check roles for stakeAddress: {}", payload.stakeAddress(), e);
        }
    }
}