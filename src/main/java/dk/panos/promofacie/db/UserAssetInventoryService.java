package dk.panos.promofacie.db;

import dk.panos.promofacie.kafka.model.AmountPayload;
import dk.panos.promofacie.kafka.model.UtxoEntry;
import dk.panos.promofacie.kafka.model.UtxoTransactionPayload;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

@ApplicationScoped
public class UserAssetInventoryService {

    private static final Logger log = LoggerFactory.getLogger(UserAssetInventoryService.class);

    /**
     * Replaces all inventory rows for the given stake address with the contents of the
     * snapshot payload. Must be called within a transaction that already holds a
     * pessimistic write lock on this stake address's rows (see
     * UtxoTransactionConsumer#processSnapshotAndEvaluate) — this method does not acquire
     * its own lock and relies on TxType.REQUIRED propagation to join that transaction. If
     * this annotation is ever changed to REQUIRES_NEW, the caller's lock stops protecting
     * this write and the slot-ordering guarantee silently breaks.
     */
    @Transactional
    public void handleSnapshot(UtxoTransactionPayload payload, long resolvedSlot) {
        String stakeAddress = payload.stakeAddress();
        log.info("[Inventory] Clearing existing inventory for stakeAddress: {}", stakeAddress);

        // Delete existing inventory for this stake address
        UserAssetInventory.delete("id.stakeAddress = ?1", stakeAddress);
        if (payload.createdUtxos() == null || payload.createdUtxos().isEmpty()) {
            log.info("[Inventory] Snapshot is empty for stakeAddress: {}", stakeAddress);
            return;
        }

        log.info("[Inventory] Loading snapshot UTXOs for stakeAddress: {}", stakeAddress);

        Map<UserAssetInventory.InventoryId, UserAssetInventory> aggregated = new HashMap<>();

        for (UtxoEntry entry : payload.createdUtxos()) {
            if (entry.amounts() == null) continue;
            for (AmountPayload amt : entry.amounts()) {
                long qty;
                try {
                    qty = Long.parseLong(amt.quantity());
                } catch (NumberFormatException | NullPointerException e) {
                    // Malformed quantity in this entry. Distinguish as a data problem, not a
                    // transient infra failure, so the consumer can skip-and-ack this poison
                    // message rather than retry it forever.
                    throw new SnapshotValidationException(
                            "Invalid quantity '%s' for policyId=%s assetName=%s stakeAddress=%s"
                                     .formatted(amt.quantity(), amt.policyId(), amt.assetName(), stakeAddress),
                            e);
                }

                UserAssetInventory.InventoryId id = new UserAssetInventory.InventoryId(
                        stakeAddress, amt.policyId(), amt.assetName());

                UserAssetInventory item = aggregated.get(id);
                if (item == null) {
                    item = new UserAssetInventory();
                    item.id = id;
                    item.quantity = qty;
                    item.traits = convertTraits(amt.traits());
                    item.lastUpdatedSlot = resolvedSlot;
                    aggregated.put(id, item);
                } else {
                    item.quantity += qty;
                }
            }
        }

        for (UserAssetInventory item : aggregated.values()) {
            item.persist();
        }

        log.info("[Inventory] Successfully updated inventory for stakeAddress: {} with {} unique assets",
                stakeAddress, aggregated.size());
    }

    private Map<String, String> convertTraits(Map<String, Object> traits) {
        if (traits == null) return Map.of();
        return traits.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> e.getValue() != null ? e.getValue().toString() : ""
                ));
    }

    /**
     * Thrown for snapshot payloads with malformed data that no amount of retrying will fix.
     * Kept distinct from plain RuntimeException so the consumer's catch blocks can route
     * this to skip-and-ack while letting genuine transient failures (DB connectivity, lock
     * timeouts) propagate and trigger Kafka redelivery/DLQ instead.
     */
    public static class SnapshotValidationException extends RuntimeException {
        public SnapshotValidationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}