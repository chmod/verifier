package dk.panos.promofacie.db;

import java.util.Map;

/**
 * Read-only projection of UserAssetInventory used for diffing snapshots. Deliberately not
 * an entity — queries that produce this never populate the persistence context's identity
 * map, so they can't collide with handleSnapshot()'s bulk delete/persist that runs later in
 * the same transaction. Always fetch via a JPQL constructor expression or native query,
 * never via entity loading, or this protection is lost.
 */
public record InventoryRow(String policyId, String assetNameHex, long quantity, Map<String, String> traits, long lastUpdatedSlot) {
}