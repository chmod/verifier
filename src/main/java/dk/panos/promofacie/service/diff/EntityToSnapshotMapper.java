package dk.panos.promofacie.service.diff;

import dk.panos.promofacie.db.InventoryRow;
import dk.panos.promofacie.db.UserAssetInventory;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

@Mapper(componentModel = "cdi")
public interface EntityToSnapshotMapper {

    default WalletInventorySnapshot map(List<UserAssetInventory> entities) {
        if (entities == null) {
            return new WalletInventorySnapshot(List.of());
        }
        List<AssetHolding> holdings = entities.stream()
                .map(this::mapEntity)
                .toList();
        return new WalletInventorySnapshot(holdings);
    }

    @Mapping(target = "policyId", source = "id.policyId")
    @Mapping(target = "assetNameHex", source = "id.assetNameHex")
    @Mapping(target = "quantity", source = "quantity")
    @Mapping(target = "traits", source = "traits")
    AssetHolding mapEntity(UserAssetInventory entity);

    default WalletInventorySnapshot mapRows(List<InventoryRow> rows) {
        if (rows == null) {
            return new WalletInventorySnapshot(List.of());
        }
        List<AssetHolding> holdings = rows.stream()
                .map(r -> new AssetHolding(r.policyId(), r.assetNameHex(), r.quantity(), r.traits()))
                .toList();
        return new WalletInventorySnapshot(holdings);
    }
}
