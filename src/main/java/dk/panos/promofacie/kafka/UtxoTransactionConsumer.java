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

    @Inject
    dk.panos.promofacie.db.UserAssetInventoryService userAssetInventoryService;

    @Incoming("cardano-utxo-updates")
    public void consumeUtxo(UtxoTransactionPayload payload) {
        if (payload == null || payload.stakeAddress() == null) {
            log.warn("[UtxoConsumer] Received null or incomplete UtxoTransactionPayload — skipping");
            return;
        }
        
        log.info("[UtxoConsumer] Received UTXO update from Kafka: stakeAddress={} txHash={} createdUtxosCount={} spentUtxosCount={} snapshot={}", 
                payload.stakeAddress(), 
                payload.txHash(), 
                payload.createdUtxos() != null ? payload.createdUtxos().size() : 0, 
                payload.spentUtxos() != null ? payload.spentUtxos().size() : 0,
                payload.snapshot());

        try {
            if (payload.snapshot()) {
                log.info("[UtxoConsumer] Snapshot detected — updating database inventory for stakeAddress: {}", payload.stakeAddress());
                userAssetInventoryService.handleSnapshot(payload);
            }

            log.info("[UtxoConsumer] Invoking reactive role check for stakeAddress: {}", payload.stakeAddress());
//            cardanoRoleService.checkAndUpdateRoles(payload.stakeAddress());
            log.info("[UtxoConsumer] Successfully processed roles check for stakeAddress: {}", payload.stakeAddress());
        } catch (Exception e) {
            log.error("[UtxoConsumer] Failed to process UTXO update for stakeAddress: {}", payload.stakeAddress(), e);
        }
    }
}