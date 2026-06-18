package dk.panos.promofacie.service.diff;

import dk.panos.promofacie.kafka.model.AmountPayload;
import dk.panos.promofacie.kafka.model.UtxoEntry;
import dk.panos.promofacie.kafka.model.UtxoTransactionPayload;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Mapper(componentModel = "cdi")
public interface PayloadToSnapshotMapper {

    default WalletInventorySnapshot map(UtxoTransactionPayload payload) {
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

    @Mapping(target = "assetNameHex", source = "assetName")
    @Mapping(target = "quantity", source = "quantity", qualifiedByName = "stringToLong")
    @Mapping(target = "traits", source = "traits", qualifiedByName = "objectMapToStringMap")
    AssetHolding mapAmount(AmountPayload amt);

    @Named("stringToLong")
    default long stringToLong(String value) {
        if (value == null) return 0;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @Named("objectMapToStringMap")
    default Map<String, String> objectMapToStringMap(Map<String, Object> traits) {
        if (traits == null) return Map.of();
        return traits.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> e.getValue() != null ? e.getValue().toString() : ""
                ));
    }
}
