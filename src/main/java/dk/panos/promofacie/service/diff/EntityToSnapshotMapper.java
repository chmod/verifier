package dk.panos.promofacie.service.diff;

import dk.panos.promofacie.db.InventoryRow;
import dk.panos.promofacie.db.UserAssetInventory;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class EntityToSnapshotMapper {

    public WalletInventorySnapshot map(List<UserAssetInventory> entities) {
        if (entities == null) {
            return new WalletInventorySnapshot(List.of());
        }
        List<AssetHolding> holdings = entities.stream()
                .map(this::mapEntity)
                .toList();
        return new WalletInventorySnapshot(holdings);
    }

    public AssetHolding mapEntity(UserAssetInventory entity) {
        if (entity == null) {
            return null;
        }
        String policyId = entity.id != null ? entity.id.policyId : null;
        String assetNameHex = entity.id != null ? entity.id.assetNameHex : null;
        long quantity = entity.quantity != null ? entity.quantity : 0L;
        Map<String, String> traits = entity.traits != null ? new LinkedHashMap<>(entity.traits) : null;
        return new AssetHolding(policyId, assetNameHex, quantity, traits);
    }

    public WalletInventorySnapshot mapRows(List<InventoryRow> rows) {
        if (rows == null) {
            return new WalletInventorySnapshot(List.of());
        }
        List<AssetHolding> holdings = rows.stream()
                .map(r -> new AssetHolding(r.policyId(), r.assetNameHex(), r.quantity(), r.traits()))
                .toList();
        return new WalletInventorySnapshot(holdings);
    }
}
