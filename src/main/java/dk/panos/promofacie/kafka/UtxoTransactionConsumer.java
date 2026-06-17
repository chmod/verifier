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
            log.warn("[UtxoConsumer] Received null or incomplete UtxoTransactionPayload — skipping {}", payload);
            return;
        }
        
        log.info("[UtxoConsumer] Received UTXO update from Kafka: stakeAddress={} txHash={} createdUtxosCount={} spentUtxosCount={} snapshot={}", 
                payload.stakeAddress(), 
                payload.txHash(), 
                payload.createdUtxos() != null ? payload.createdUtxos().size() : 0, 
                payload.spentUtxos() != null ? payload.spentUtxos().size() : 0,
                payload.snapshot());
        log.info("[UtxoConsumer] Details created={} spent={}",
                payload.createdUtxos(),
                payload.spentUtxos());

        try {
            if ("rollback".equalsIgnoreCase(payload.txHash())) {
                log.info("[UtxoConsumer] Rollback transaction detected for stakeAddress: {} with slot {}", 
                        payload.stakeAddress(), payload.slot());
                // TODO: Handle rollback inventory update locally (e.g. process rolled back spent/created UTXOs)
                // TODO: Placeholder for role check:
                // cardanoRoleService.checkAndUpdateRoles(payload.stakeAddress());
                
            } else if (payload.snapshot()) {
                log.info("[UtxoConsumer] Snapshot detected — updating database inventory for stakeAddress: {}", payload.stakeAddress());
                userAssetInventoryService.handleSnapshot(payload);
                // TODO: Placeholder for role check:
                // cardanoRoleService.checkAndUpdateRoles(payload.stakeAddress());
                
            } else {
                log.info("[UtxoConsumer] Regular transaction detected for stakeAddress: {} (txHash={})", 
                        payload.stakeAddress(), payload.txHash());
                // TODO: Handle regular incremental transaction updates (created/spent UTXOs) locally
                // TODO: Placeholder for role check:
                // cardanoRoleService.checkAndUpdateRoles(payload.stakeAddress());
            }
        } catch (Exception e) {
            log.error("[UtxoConsumer] Failed to process UTXO update for stakeAddress: {}", payload.stakeAddress(), e);
        }
    }
}