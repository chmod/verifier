package dk.panos.promofacie.service.diff;

import java.util.*;

public record WalletInventorySnapshot(List<AssetHolding> holdings) implements Diffable<WalletInventorySnapshot> {

    @Override
    public DiffResult diff(WalletInventorySnapshot after) {
        Set<AssetHolding> added = new HashSet<>();
        Set<AssetHolding> removed = new HashSet<>();

        Map<AssetKey, AssetHolding> beforeMap = toMap(this.holdings);
        Map<AssetKey, AssetHolding> afterMap = toMap(after.holdings);

        // Process all assets in after (to see what was added or increased)
        for (Map.Entry<AssetKey, AssetHolding> entry : afterMap.entrySet()) {
            AssetKey key = entry.getKey();
            AssetHolding afterHolding = entry.getValue();
            AssetHolding beforeHolding = beforeMap.get(key);

            if (beforeHolding == null) {
                // Completely new asset added
                added.add(afterHolding);
            } else {
                // Asset existed before, check quantity and traits
                long qtyDiff = afterHolding.quantity() - beforeHolding.quantity();
                boolean traitsChanged = !Objects.equals(afterHolding.traits(), beforeHolding.traits());

                if (qtyDiff > 0 || traitsChanged) {
                    if (traitsChanged) {
                        added.add(afterHolding);
                        removed.add(beforeHolding);
                    } else {
                        added.add(new AssetHolding(
                                key.policyId(),
                                key.assetNameHex(),
                                qtyDiff,
                                afterHolding.traits()
                        ));
                    }
                }
            }
        }

        // Process all assets in before (to see what was removed or decreased)
        for (Map.Entry<AssetKey, AssetHolding> entry : beforeMap.entrySet()) {
            AssetKey key = entry.getKey();
            AssetHolding beforeHolding = entry.getValue();
            AssetHolding afterHolding = afterMap.get(key);

            if (afterHolding == null) {
                // Completely removed
                removed.add(beforeHolding);
            } else {
                long qtyDiff = beforeHolding.quantity() - afterHolding.quantity();
                boolean traitsChanged = !Objects.equals(afterHolding.traits(), beforeHolding.traits());

                if (qtyDiff > 0 && !traitsChanged) {
                    removed.add(new AssetHolding(
                            key.policyId(),
                            key.assetNameHex(),
                            qtyDiff,
                            beforeHolding.traits()
                    ));
                }
            }
        }

        return new DiffResult(added, removed);
    }

    private Map<AssetKey, AssetHolding> toMap(List<AssetHolding> list) {
        if (list == null) return Map.of();
        Map<AssetKey, AssetHolding> map = new HashMap<>();
        for (AssetHolding h : list) {
            AssetKey key = new AssetKey(h.policyId(), h.assetNameHex());
            AssetHolding existing = map.get(key);
            if (existing == null) {
                map.put(key, h);
            } else {
                map.put(key, new AssetHolding(
                        h.policyId(),
                        h.assetNameHex(),
                        existing.quantity() + h.quantity(),
                        h.traits()
                ));
            }
        }
        return map;
    }

    private record AssetKey(String policyId, String assetNameHex) {}
}
