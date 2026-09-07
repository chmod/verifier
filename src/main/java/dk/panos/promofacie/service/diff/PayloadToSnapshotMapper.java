package dk.panos.promofacie.service.diff;

import dk.panos.promofacie.kafka.model.AmountPayload;
import dk.panos.promofacie.kafka.model.UtxoEntry;
import dk.panos.promofacie.kafka.model.UtxoTransactionPayload;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@ApplicationScoped
public class PayloadToSnapshotMapper {

    public WalletInventorySnapshot map(UtxoTransactionPayload payload) {
        if (payload == null || payload.createdUtxos() == null) {
            return new WalletInventorySnapshot(List.of());
        }
        List<AssetHolding> holdings = new ArrayList<>();
        for (UtxoEntry entry : payload.createdUtxos()) {
            if (entry.amounts() == null) continue;
            for (AmountPayload amt : entry.amounts()) {
                holdings.add(mapAmount(amt));
            }
        }
        return new WalletInventorySnapshot(holdings);
    }

    public AssetHolding mapAmount(AmountPayload amt) {
        if (amt == null) {
            return null;
        }
        return new AssetHolding(
                amt.policyId(),
                amt.assetName(),
                stringToLong(amt.quantity()),
                objectMapToStringMap(amt.traits())
        );
    }

    public long stringToLong(String value) {
        if (value == null) return 0;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public Map<String, String> objectMapToStringMap(Map<String, Object> traits) {
        if (traits == null) return Map.of();
        return traits.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> e.getValue() != null ? e.getValue().toString() : ""
                ));
    }
}
