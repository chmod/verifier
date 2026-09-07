package dk.panos.promofacie.service.diff;

import dk.panos.promofacie.db.InventoryRow;
import dk.panos.promofacie.db.UserAssetInventory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EntityToSnapshotMapperTest {

    private final EntityToSnapshotMapper mapper = new EntityToSnapshotMapper();

    @Test
    void testMapNullEntities() {
        WalletInventorySnapshot snapshot = mapper.map((List<UserAssetInventory>) null);
        assertNotNull(snapshot);
        assertTrue(snapshot.holdings().isEmpty());
    }

    @Test
    void testMapEntities() {
        UserAssetInventory entity = new UserAssetInventory();
        entity.id = new UserAssetInventory.InventoryId();
        entity.id.policyId = "policy123";
        entity.id.assetNameHex = "415254";
        entity.quantity = 5L;
        entity.traits = Map.of("rarity", "legendary");

        WalletInventorySnapshot snapshot = mapper.map(List.of(entity));
        assertNotNull(snapshot);
        assertEquals(1, snapshot.holdings().size());

        AssetHolding holding = snapshot.holdings().iterator().next();
        assertEquals("policy123", holding.policyId());
        assertEquals("415254", holding.assetNameHex());
        assertEquals(5L, holding.quantity());
        assertEquals(Map.of("rarity", "legendary"), holding.traits());
    }

    @Test
    void testMapEntityNull() {
        assertNull(mapper.mapEntity(null));
    }

    @Test
    void testMapRows() {
        WalletInventorySnapshot nullSnapshot = mapper.mapRows(null);
        assertNotNull(nullSnapshot);
        assertTrue(nullSnapshot.holdings().isEmpty());

        InventoryRow row = new InventoryRow("policy123", "415254", 10L, Map.of("tier", "gold"), 12345L);
        WalletInventorySnapshot snapshot = mapper.mapRows(List.of(row));
        assertEquals(1, snapshot.holdings().size());
        AssetHolding holding = snapshot.holdings().iterator().next();
        assertEquals("policy123", holding.policyId());
        assertEquals("415254", holding.assetNameHex());
        assertEquals(10L, holding.quantity());
        assertEquals(Map.of("tier", "gold"), holding.traits());
    }
}
