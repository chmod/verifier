package dk.panos.promofacie.kafka;

import dk.panos.promofacie.db.InventoryRow;
import dk.panos.promofacie.db.UserAssetInventory;
import dk.panos.promofacie.db.UserAssetInventoryService;
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

import jakarta.transaction.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@ApplicationScoped
public class UtxoTransactionConsumer {
    private static final Logger log = LoggerFactory.getLogger(UtxoTransactionConsumer.class);

    @Inject
    UserAssetInventoryService userAssetInventoryService;

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
                processSnapshotAndEvaluate(payload, true);
            } else if (payload.snapshot()) {
                log.info("[UtxoConsumer] Snapshot detected — processing inventory for stakeAddress: {}", payload.stakeAddress());
                processSnapshotAndEvaluate(payload, false);
            } else {
                log.info("[UtxoConsumer] Regular transaction detected (ignored since producer sends snapshots/rollbacks) for stakeAddress: {} (txHash={})",
                        payload.stakeAddress(), payload.txHash());
            }
        } catch (UserAssetInventoryService.SnapshotValidationException e) {
            // Bad/unprocessable payload data — retrying will never fix this. Safe to log and
            // ack so the consumer moves on, rather than crash-looping on a poison message.
            log.warn("[UtxoConsumer] Skipping unprocessable payload for stakeAddress: {} — {}", payload.stakeAddress(), e.getMessage());
        } catch (Exception e) {
            // Unexpected/transient failure (DB connectivity, lock timeout, mapping bug).
            // Rethrow so the messaging layer's configured retry/backoff or DLQ strategy
            // handles it, instead of silently acking and losing the update. Requires the
            // "cardano-utxo-updates" channel to have an appropriate failure-strategy
            // configured (see application.properties) — without that, this will crash-loop
            // the consumer rather than recover gracefully.
            log.error("[UtxoConsumer] Failed to process UTXO update for stakeAddress: {}", payload.stakeAddress(), e);
            throw new RuntimeException("Failed to process UTXO update for stakeAddress " + payload.stakeAddress(), e);
        }
    }

    @Transactional
    void processSnapshotAndEvaluate(UtxoTransactionPayload payload, boolean isRollback) {
        // 1. Fetch "before" inventory as a read-only projection (including lastUpdatedSlot) in a single query
        List<InventoryRow> beforeRows = UserAssetInventory.getEntityManager()
                .createQuery(
                        "select new dk.panos.promofacie.db.InventoryRow(u.id.policyId, u.id.assetNameHex, u.quantity, u.traits, u.lastUpdatedSlot) " +
                                "from UserAssetInventory u where u.id.stakeAddress = ?1",
                        InventoryRow.class)
                .setParameter(1, payload.stakeAddress())
                .getResultList();
        WalletInventorySnapshot before = entityMapper.mapRows(beforeRows);

        // Derive currentSlot directly in-memory from beforeRows
        Long currentSlot = beforeRows.isEmpty()
                ? null
                : beforeRows.stream().mapToLong(InventoryRow::lastUpdatedSlot).max().stream().boxed().findFirst().orElse(null);

        if (!isRollback && !payload.forcedSync()) {
            if (currentSlot != null && payload.slot() <= currentSlot) {
                log.info("[UtxoConsumer] Incoming snapshot slot {} is not newer than current database slot {} for stakeAddress {} — skipping update",
                        payload.slot(), currentSlot, payload.stakeAddress());
                return;
            }
        }

        if (payload.forcedSync()) {
            log.info("[UtxoConsumer] Forced sync for stakeAddress {} at slot {} — bypassing monotonicity check",
                    payload.stakeAddress(), payload.slot());
        }

        long resolvedSlot = currentSlot != null ? Math.max(payload.slot(), currentSlot) : payload.slot();

        // 2. Map incoming payload (after)
        WalletInventorySnapshot after = payloadMapper.map(payload);

        // 3. Diff snapshots
        DiffResult diff = before.diff(after);
        log.info("[UtxoConsumer] Diff result: {} added, {} removed for stakeAddress {}", diff.added().size(), diff.removed().size(), payload.stakeAddress());

        // 4. Extract changed policies
        Set<String> changedPolicies = new HashSet<>();
        for (AssetHolding h : diff.added()) {
            changedPolicies.add(h.policyId());
        }
        for (AssetHolding h : diff.removed()) {
            changedPolicies.add(h.policyId());
        }

        // 5. Update database inventory with clamped slot.
        userAssetInventoryService.handleSnapshot(payload, resolvedSlot);

        // 6. Enqueue role sync updates if policies have changed, using the resolved slot
        if (!changedPolicies.isEmpty()) {
            log.info("[UtxoConsumer] Policy changes detected: {}. Enqueuing outbox role updates.", changedPolicies);
            roleEvaluationService.enqueueRoleUpdates(payload.stakeAddress(), changedPolicies, resolvedSlot);
        } else {
            log.info("[UtxoConsumer] No policy changes detected. Skipping outbox role updates.");
        }
    }
}