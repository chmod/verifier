package dk.panos.promofacie.kafka;

import dk.panos.promofacie.db.InventoryRow;
import dk.panos.promofacie.db.UserAssetInventoryService;
import dk.panos.promofacie.kafka.model.UtxoTransactionPayload;
import dk.panos.promofacie.service.RoleEvaluationService;
import dk.panos.promofacie.service.diff.AssetHolding;
import dk.panos.promofacie.service.diff.EntityToSnapshotMapper;
import dk.panos.promofacie.service.diff.PayloadToSnapshotMapper;
import dk.panos.promofacie.service.diff.WalletInventorySnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UtxoTransactionConsumerTest {

    private UtxoTransactionConsumer consumer;
    private UserAssetInventoryService userAssetInventoryServiceMock;
    private PayloadToSnapshotMapper payloadMapperMock;
    private EntityToSnapshotMapper entityMapperMock;
    private RoleEvaluationService roleEvaluationServiceMock;

    @BeforeEach
    void setUp() {
        consumer = spy(new UtxoTransactionConsumer());
        userAssetInventoryServiceMock = mock(UserAssetInventoryService.class);
        payloadMapperMock = mock(PayloadToSnapshotMapper.class);
        entityMapperMock = mock(EntityToSnapshotMapper.class);
        roleEvaluationServiceMock = mock(RoleEvaluationService.class);

        consumer.userAssetInventoryService = userAssetInventoryServiceMock;
        consumer.payloadMapper = payloadMapperMock;
        consumer.entityMapper = entityMapperMock;
        consumer.roleEvaluationService = roleEvaluationServiceMock;
    }

    @Test
    void testSnapshotWithZeroDiffEvaluatesHoldings() {
        String stakeAddress = "stake1uxcye8ea89hnkd2dkkfl7hafyurllyvkmkcamguv6c78d5gn6n5pf";
        String policyId = "d620aef3de3244cdba016cb9616a6516679c0a1fe560ffd9152452b5";

        // DB inventory already has 1M qsnek
        InventoryRow row = new InventoryRow(policyId, "qsnek", 1000000L, Map.of(), 100L);
        doReturn(List.of(row)).when(consumer).fetchInventoryRows(stakeAddress);

        AssetHolding holding = new AssetHolding(policyId, "qsnek", 1000000L, Map.of());
        WalletInventorySnapshot before = new WalletInventorySnapshot(List.of(holding));
        WalletInventorySnapshot after = new WalletInventorySnapshot(List.of(holding));

        when(entityMapperMock.mapRows(anyList())).thenReturn(before);

        UtxoTransactionPayload payload = new UtxoTransactionPayload(
                stakeAddress,
                "snapshot",
                105L,
                10L,
                Collections.emptyList(),
                Collections.emptyList(),
                true, // snapshot
                false // forcedSync
        );
        when(payloadMapperMock.map(payload)).thenReturn(after);

        consumer.consumeUtxo(payload);

        // Verify inventory update was invoked
        verify(userAssetInventoryServiceMock, times(1)).handleSnapshot(eq(payload), eq(105L));

        // Crucial: role updates should be enqueued for the holdings policy even though diff was 0!
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Set<String>> policyCaptor = ArgumentCaptor.forClass(Set.class);
        verify(roleEvaluationServiceMock, times(1)).enqueueRoleUpdates(eq(stakeAddress), policyCaptor.capture(), eq(105L));

        Set<String> evaluatedPolicies = policyCaptor.getValue();
        assertEquals(1, evaluatedPolicies.size());
        assertTrue(evaluatedPolicies.contains(policyId));
    }

    @Test
    void testSnapshotEmptyHoldingsSkipsRoleUpdates() {
        String stakeAddress = "stake1empty";

        doReturn(List.of()).when(consumer).fetchInventoryRows(stakeAddress);

        WalletInventorySnapshot emptySnapshot = new WalletInventorySnapshot(List.of());
        when(entityMapperMock.mapRows(anyList())).thenReturn(emptySnapshot);

        UtxoTransactionPayload payload = new UtxoTransactionPayload(
                stakeAddress,
                "snapshot",
                105L,
                10L,
                Collections.emptyList(),
                Collections.emptyList(),
                true,
                false
        );
        when(payloadMapperMock.map(payload)).thenReturn(emptySnapshot);

        consumer.consumeUtxo(payload);

        verify(userAssetInventoryServiceMock, times(1)).handleSnapshot(eq(payload), eq(105L));
        verify(roleEvaluationServiceMock, never()).enqueueRoleUpdates(anyString(), anySet(), anyLong());
    }
}
