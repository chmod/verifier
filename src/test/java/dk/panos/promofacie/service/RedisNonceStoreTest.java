package dk.panos.promofacie.service;

import dk.panos.promofacie.controller.model.NonceEntry;
import dk.panos.promofacie.db.Chain;
import io.quarkus.redis.datasource.value.SetArgs;
import io.quarkus.redis.datasource.value.ValueCommands;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class RedisNonceStoreTest {

    private RedisNonceStore store;
    private ValueCommands<String, String> values;
    private final Map<String, String> redis = new HashMap<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        values = mock(ValueCommands.class);
        when(values.getdel(anyString())).thenAnswer(inv -> redis.remove((String) inv.getArgument(0)));
        // SET ... NX only writes a missing key; INCR increments in place
        doAnswer(inv -> {
            SetArgs args = inv.getArgument(2);
            boolean nx = args.toArgs().stream().anyMatch(a -> "NX".equalsIgnoreCase(String.valueOf(a)));
            if (nx) redis.putIfAbsent(inv.getArgument(0), inv.getArgument(1));
            else redis.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(values).set(anyString(), anyString(), any(SetArgs.class));
        when(values.incr(anyString())).thenAnswer(inv -> {
            long next = Long.parseLong(redis.getOrDefault((String) inv.getArgument(0), "0")) + 1;
            redis.put(inv.getArgument(0), Long.toString(next));
            return next;
        });
        store = new RedisNonceStore();
        store.values = values;
    }

    @Test
    void issueStoresEntryAndLatestPointerWithTtl() {
        NonceEntry entry = new NonceEntry("line1\nline2", Chain.ROBINHOOD, "0xAbC");

        String id = store.issue("user-1", entry, Duration.ofMinutes(5));

        assertTrue(redis.containsKey("wallet-verification:nonce:user-1:" + id));
        assertEquals(id, redis.get("wallet-verification:nonce-latest:user-1"));
        verify(values, times(3)).set(anyString(), anyString(), any(SetArgs.class));
    }

    @Test
    void issuanceIsCappedPerWindow() {
        for (int i = 0; i < RedisNonceStore.MAX_CHALLENGES_PER_WINDOW; i++) {
            store.issue("user-1", new NonceEntry("m" + i, null, null), Duration.ofMinutes(5));
        }

        assertThrows(TooManyChallengesException.class,
                () -> store.issue("user-1", new NonceEntry("over", null, null), Duration.ofMinutes(5)));
        // Other users are unaffected
        assertNotNull(store.issue("user-2", new NonceEntry("m", null, null), Duration.ofMinutes(5)));
    }

    @Test
    void consumeRoundTripsEntryExactlyOnce() {
        NonceEntry entry = new NonceEntry("line1\nline2", Chain.ROBINHOOD, "0xAbC");
        String id = store.issue("user-1", entry, Duration.ofMinutes(5));

        assertEquals(Optional.of(entry), store.consume("user-1", id));
        assertEquals(Optional.empty(), store.consume("user-1", id));
    }

    @Test
    void severalChallengesCanBePendingPerUser() {
        String first = store.issue("user-1", new NonceEntry("first", null, null), Duration.ofMinutes(5));
        String second = store.issue("user-1", new NonceEntry("second", null, null), Duration.ofMinutes(5));

        assertEquals("first", store.consume("user-1", first).orElseThrow().message());
        assertEquals("second", store.consume("user-1", second).orElseThrow().message());
    }

    @Test
    void consumeWithoutIdUsesLatest() {
        store.issue("user-1", new NonceEntry("first", null, null), Duration.ofMinutes(5));
        store.issue("user-1", new NonceEntry("second", null, null), Duration.ofMinutes(5));

        assertEquals("second", store.consume("user-1", null).orElseThrow().message());
        assertEquals(Optional.empty(), store.consume("user-1", null));
    }

    @Test
    void challengesAreScopedToTheDiscordUser() {
        String id = store.issue("user-1", new NonceEntry("mine", null, null), Duration.ofMinutes(5));

        assertEquals(Optional.empty(), store.consume("user-2", id));
        assertTrue(store.consume("user-1", id).isPresent());
    }

    @Test
    void malformedNonceIdNeverReachesRedis() {
        assertEquals(Optional.empty(), store.consume("user-1", "../other:key"));
        verify(values, never()).getdel(anyString());
    }

    @Test
    void ttlIsPassedToRedis() {
        ArgumentCaptor<SetArgs> args = ArgumentCaptor.forClass(SetArgs.class);
        store.issue("user-1", new NonceEntry("m", null, null), Duration.ofMinutes(5));
        verify(values, times(3)).set(anyString(), anyString(), args.capture());
        // SetArgs has no getters; its argument list must carry EX 300 (entry, latest pointer and window counter)
        assertTrue(args.getAllValues().stream().allMatch(a -> a.toArgs().toString().contains("300")),
                "expected EX 300 on every SET");
    }
}
