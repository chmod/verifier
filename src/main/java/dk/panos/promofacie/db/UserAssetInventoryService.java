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

    @Transactional
    public void handleSnapshot(UtxoTransactionPayload payload) {
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
                UserAssetInventory.InventoryId id = new UserAssetInventory.InventoryId(
                        stakeAddress, amt.policyId(), amt.assetName());

                UserAssetInventory item = aggregated.get(id);
                long qty = Long.parseLong(amt.quantity());
                if (item == null) {
                    item = new UserAssetInventory();
                    item.id = id;
                    item.quantity = qty;
                    item.traits = convertTraits(amt.traits());
                    item.lastUpdatedSlot = payload.slot();
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
}
