package dk.panos.promofacie.service.diff;

import dk.panos.promofacie.kafka.model.AmountPayload;
import dk.panos.promofacie.kafka.model.UtxoEntry;
import dk.panos.promofacie.kafka.model.UtxoTransactionPayload;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PayloadToSnapshotMapperTest {

    private final PayloadToSnapshotMapper mapper = new PayloadToSnapshotMapper();

    @Test
    void testMapNullPayload() {
        WalletInventorySnapshot snapshot = mapper.map(null);
        assertNotNull(snapshot);
        assertTrue(snapshot.holdings().isEmpty());
    }

    @Test
    void testMapPayload() {
        AmountPayload amt1 = new AmountPayload("unit1", "policyA", "assetA", "10", Map.of("power", 100));
        UtxoEntry entry1 = UtxoEntry.created("tx#0", 0, "addr1", List.of(amt1));
        UtxoTransactionPayload payload = new UtxoTransactionPayload(
                "stakeAddr1",
                "tx123",
                1000L,
                1000L,
                List.of(entry1),
                List.of(),
                false,
                false
        );

        WalletInventorySnapshot snapshot = mapper.map(payload);
        assertNotNull(snapshot);
        assertEquals(1, snapshot.holdings().size());

        AssetHolding holding = snapshot.holdings().iterator().next();
        assertEquals("policyA", holding.policyId());
        assertEquals("assetA", holding.assetNameHex());
        assertEquals(10L, holding.quantity());
        assertEquals("100", holding.traits().get("power"));
    }

    @Test
    void testMapAmountNull() {
        assertNull(mapper.mapAmount(null));
    }

    @Test
    void testStringToLong() {
        assertEquals(12345L, mapper.stringToLong("12345"));
        assertEquals(0L, mapper.stringToLong(null));
        assertEquals(0L, mapper.stringToLong("invalid"));
    }

    @Test
    void testObjectMapToStringMap() {
        Map<String, Object> input = Map.of("key1", "val1", "key2", 42);
        Map<String, String> output = mapper.objectMapToStringMap(input);
        assertEquals("val1", output.get("key1"));
        assertEquals("42", output.get("key2"));
        assertTrue(mapper.objectMapToStringMap(null).isEmpty());
    }
}
