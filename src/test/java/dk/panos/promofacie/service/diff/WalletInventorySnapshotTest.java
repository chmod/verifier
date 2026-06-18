package dk.panos.promofacie.service.diff;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WalletInventorySnapshotTest {

    @Test
    void testAssetAddition() {
        AssetHolding h1 = new AssetHolding("policy1", "asset1", 1L, Map.of("color", "red"));
        
        WalletInventorySnapshot before = new WalletInventorySnapshot(List.of());
        WalletInventorySnapshot after = new WalletInventorySnapshot(List.of(h1));

        DiffResult result = before.diff(after);

        assertEquals(1, result.added().size());
        assertTrue(result.removed().isEmpty());
        
        AssetHolding added = result.added().iterator().next();
        assertEquals("policy1", added.policyId());
        assertEquals("asset1", added.assetNameHex());
        assertEquals(1L, added.quantity());
        assertEquals("red", added.traits().get("color"));
    }

    @Test
    void testAssetRemoval() {
        AssetHolding h1 = new AssetHolding("policy1", "asset1", 1L, Map.of("color", "red"));
        
        WalletInventorySnapshot before = new WalletInventorySnapshot(List.of(h1));
        WalletInventorySnapshot after = new WalletInventorySnapshot(List.of());

        DiffResult result = before.diff(after);

        assertTrue(result.added().isEmpty());
        assertEquals(1, result.removed().size());

        AssetHolding removed = result.removed().iterator().next();
        assertEquals("policy1", removed.policyId());
        assertEquals("asset1", removed.assetNameHex());
        assertEquals(1L, removed.quantity());
    }

    @Test
    void testQuantityIncrease() {
        AssetHolding beforeHolding = new AssetHolding("policy1", "asset1", 2L, Map.of("color", "red"));
        AssetHolding afterHolding = new AssetHolding("policy1", "asset1", 5L, Map.of("color", "red"));

        WalletInventorySnapshot before = new WalletInventorySnapshot(List.of(beforeHolding));
        WalletInventorySnapshot after = new WalletInventorySnapshot(List.of(afterHolding));

        DiffResult result = before.diff(after);

        assertEquals(1, result.added().size());
        assertTrue(result.removed().isEmpty());

        AssetHolding added = result.added().iterator().next();
        assertEquals("policy1", added.policyId());
        assertEquals("asset1", added.assetNameHex());
        assertEquals(3L, added.quantity());
        assertEquals("red", added.traits().get("color"));
    }

    @Test
    void testQuantityDecrease() {
        AssetHolding beforeHolding = new AssetHolding("policy1", "asset1", 5L, Map.of("color", "red"));
        AssetHolding afterHolding = new AssetHolding("policy1", "asset1", 2L, Map.of("color", "red"));

        WalletInventorySnapshot before = new WalletInventorySnapshot(List.of(beforeHolding));
        WalletInventorySnapshot after = new WalletInventorySnapshot(List.of(afterHolding));

        DiffResult result = before.diff(after);

        assertTrue(result.added().isEmpty());
        assertEquals(1, result.removed().size());

        AssetHolding removed = result.removed().iterator().next();
        assertEquals("policy1", removed.policyId());
        assertEquals("asset1", removed.assetNameHex());
        assertEquals(3L, removed.quantity());
        assertEquals("red", removed.traits().get("color"));
    }

    @Test
    void testTraitChange() {
        AssetHolding beforeHolding = new AssetHolding("policy1", "asset1", 1L, Map.of("class", "bronze"));
        AssetHolding afterHolding = new AssetHolding("policy1", "asset1", 1L, Map.of("class", "silver"));

        WalletInventorySnapshot before = new WalletInventorySnapshot(List.of(beforeHolding));
        WalletInventorySnapshot after = new WalletInventorySnapshot(List.of(afterHolding));

        DiffResult result = before.diff(after);

        assertEquals(1, result.added().size());
        assertEquals(1, result.removed().size());

        AssetHolding added = result.added().iterator().next();
        assertEquals("silver", added.traits().get("class"));

        AssetHolding removed = result.removed().iterator().next();
        assertEquals("bronze", removed.traits().get("class"));
    }
}
