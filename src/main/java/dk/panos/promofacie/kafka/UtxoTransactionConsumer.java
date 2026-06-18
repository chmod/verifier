package dk.panos.promofacie.kafka;

import dk.panos.promofacie.db.UserAssetInventory;
import dk.panos.promofacie.kafka.model.UtxoTransactionPayload;
import dk.panos.promofacie.service.RoleEvaluationService;
import dk.panos.promofacie.service.diff.AssetHolding;
import dk.panos.promofacie.service.diff.DiffResult;
import dk.panos.promofacie.service.diff.EntityToSnapshotMapper;
import dk.panos.promofacie.service.diff.PayloadToSnapshotMapper;
import dk.panos.promofacie.service.diff.WalletInventorySnapshot;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@ApplicationScoped
public class UtxoTransactionConsumer {
    private static final Logger log = LoggerFactory.getLogger(UtxoTransactionConsumer.class);

    @Inject
    dk.panos.promofacie.db.UserAssetInventoryService userAssetInventoryService;

    @Inject
    PayloadToSnapshotMapper payloadMapper;

    @Inject
    EntityToSnapshotMapper entityMapper;

    @Inject
    RoleEvaluationService roleEvaluationService;

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

        try {
            if ("rollback".equalsIgnoreCase(payload.txHash())) {
                log.info("[UtxoConsumer] Rollback transaction detected for stakeAddress: {} with slot {}", 
                        payload.stakeAddress(), payload.slot());
                processSnapshotAndEvaluate(payload);
            } else if (payload.snapshot()) {
                log.info("[UtxoConsumer] Snapshot detected — processing inventory for stakeAddress: {}", payload.stakeAddress());
                processSnapshotAndEvaluate(payload);
            } else {
                log.info("[UtxoConsumer] Regular transaction detected (ignored since producer sends snapshots/rollbacks) for stakeAddress: {} (txHash={})", 
                        payload.stakeAddress(), payload.txHash());
            }
        } catch (Exception e) {
            log.error("[UtxoConsumer] Failed to process UTXO update for stakeAddress: {}", payload.stakeAddress(), e);
        }
    }

    private void processSnapshotAndEvaluate(UtxoTransactionPayload payload) {
        // 1. Fetch current (before) inventory from DB
        List<UserAssetInventory> beforeEntities = UserAssetInventory.list("id.stakeAddress = ?1", payload.stakeAddress());
        WalletInventorySnapshot before = entityMapper.map(beforeEntities);

        // 2. Map incoming payload (after)
        WalletInventorySnapshot after = payloadMapper.map(payload);

        // 3. Diff snapshots
        DiffResult diff = before.diff(after);
        log.info("[UtxoConsumer] Diff result: {} added, {} removed", diff.added().size(), diff.removed().size());

        // 4. Extract changed policies
        Set<String> changedPolicies = new HashSet<>();
        for (AssetHolding h : diff.added()) {
            changedPolicies.add(h.policyId());
        }
        for (AssetHolding h : diff.removed()) {
            changedPolicies.add(h.policyId());
        }

        // 5. Update database inventory
        userAssetInventoryService.handleSnapshot(payload);

        // 6. Evaluate roles if policies have changed
        if (!changedPolicies.isEmpty()) {
            log.info("[UtxoConsumer] Policy changes detected: {}. Triggering role evaluation.", changedPolicies);
            roleEvaluationService.evaluateRoles(payload.stakeAddress(), changedPolicies);
        } else {
            log.info("[UtxoConsumer] No policy changes detected. Skipping role evaluation.");
        }
    }
}