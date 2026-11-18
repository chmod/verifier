package dk.panos.promofacie.service;

import dk.panos.promofacie.controller.model.NonceEntry;
import dk.panos.promofacie.db.Chain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryNonceStoreTest {

    private static final Duration TTL = Duration.ofMinutes(5);

    /** A clock the test can move forward. */
    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-18T21:00:00Z"));
        void advance(Duration d) { now.updateAndGet(i -> i.plus(d)); }
        @Override public Instant instant() { return now.get(); }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
    }

    private MutableClock clock;
    private InMemoryNonceStore store;

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
        store = new InMemoryNonceStore(clock);
    }

    private static NonceEntry entry(String message) {
        return new NonceEntry(message, Chain.CARDANO, null);
    }

    @Test
    void consumeRoundTripsEntryExactlyOnce() {
        NonceEntry e = new NonceEntry("line1\nline2", Chain.ROBINHOOD, "0xAbC");
        String id = store.issue("user-1", e, TTL);

        assertEquals(Optional.of(e), store.consume("user-1", id));
        assertEquals(Optional.empty(), store.consume("user-1", id));
    }

    @Test
    void allSlotsStayValidUpToTheLimit() {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < InMemoryNonceStore.MAX_PENDING_PER_USER; i++) {
            ids.add(store.issue("user-1", entry("m" + i), TTL));
        }
        // Consume in reverse to show order doesn't matter
        for (int i = ids.size() - 1; i >= 0; i--) {
            assertEquals("m" + i, store.consume("user-1", ids.get(i)).orElseThrow().message());
        }
    }

    @Test
    void issuingBeyondTheLimitEvictsTheOldest() {
        String oldest = store.issue("user-1", entry("oldest"), TTL);
        String second = store.issue("user-1", entry("second"), TTL);
        for (int i = 2; i < InMemoryNonceStore.MAX_PENDING_PER_USER; i++) {
            store.issue("user-1", entry("m" + i), TTL);
        }
        String newest = store.issue("user-1", entry("newest"), TTL);

        assertEquals(Optional.empty(), store.consume("user-1", oldest));
        assertTrue(store.consume("user-1", second).isPresent());
        assertTrue(store.consume("user-1", newest).isPresent());
    }

    @Test
    void spammingOnlyEvictsTheSpammersOwnChallenges() {
        String victimId = store.issue("user-1", entry("mine"), TTL);
        for (int i = 0; i < 1000; i++) {
            store.issue("spammer", entry("x" + i), TTL);
        }

        assertTrue(store.consume("user-1", victimId).isPresent());
    }

    @Test
    void challengesExpireAfterTheTtl() {
        String id = store.issue("user-1", entry("m"), TTL);
        clock.advance(TTL);

        assertEquals(Optional.empty(), store.consume("user-1", id));
    }

    @Test
    void challengeIsValidJustBeforeExpiry() {
        String id = store.issue("user-1", entry("m"), TTL);
        clock.advance(TTL.minusSeconds(1));

        assertTrue(store.consume("user-1", id).isPresent());
    }

    @Test
    void challengesAreScopedToTheDiscordUser() {
        String id = store.issue("user-1", entry("mine"), TTL);

        assertEquals(Optional.empty(), store.consume("user-2", id));
        assertTrue(store.consume("user-1", id).isPresent());
    }

    @Test
    void consumeWithoutIdUsesLatest() {
        store.issue("user-1", entry("first"), TTL);
        store.issue("user-1", entry("second"), TTL);

        assertEquals("second", store.consume("user-1", null).orElseThrow().message());
        assertEquals("first", store.consume("user-1", " ").orElseThrow().message());
        assertEquals(Optional.empty(), store.consume("user-1", null));
    }

    @Test
    void peekDoesNotConsume() {
        String id = store.issue("user-1", entry("m"), TTL);

        assertEquals("m", store.peek("user-1", id).orElseThrow().message());
        assertEquals("m", store.peek("user-1", id).orElseThrow().message());
        assertTrue(store.consume("user-1", id).isPresent());
        assertEquals(Optional.empty(), store.peek("user-1", id));
    }

    @Test
    void peekRequiresAnIdAndRespectsExpiryAndUser() {
        String id = store.issue("user-1", entry("m"), TTL);

        assertEquals(Optional.empty(), store.peek("user-1", null));
        assertEquals(Optional.empty(), store.peek("user-1", " "));
        assertEquals(Optional.empty(), store.peek("user-2", id));
        clock.advance(TTL);
        assertEquals(Optional.empty(), store.peek("user-1", id));
    }

    @Test
    void latestFallbackSkipsNonSignatureChallenges() {
        // An old client's verify call (no nonceId) must not pop, and burn, a pending OpenSea bio code
        store.issue("user-1", entry("signature"), TTL);
        String bioId = store.issue("user-1",
                new NonceEntry("PF-CODE", Chain.ROBINHOOD, "0xabc", NonceEntry.ProofMethod.OPENSEA_BIO), TTL);

        assertEquals("signature", store.consume("user-1", null).orElseThrow().message());
        assertEquals(Optional.empty(), store.consume("user-1", null));
        assertTrue(store.peek("user-1", bioId).isPresent());
    }

    @Test
    void unknownNonceIdReturnsEmpty() {
        store.issue("user-1", entry("m"), TTL);
        assertEquals(Optional.empty(), store.consume("user-1", "not-an-id"));
        assertEquals(Optional.empty(), store.consume("nobody", "not-an-id"));
    }

    @Test
    void purgeDropsUsersWhoseChallengesAllExpired() {
        store.issue("user-1", entry("old"), TTL);
        clock.advance(Duration.ofMinutes(3));
        store.issue("user-2", entry("fresh"), TTL);
        clock.advance(Duration.ofMinutes(3));

        store.purgeExpired();

        assertEquals(1, store.usersWithPendingChallenges());
    }

    @Test
    void concurrentConsumeSucceedsOnlyOnce() throws Exception {
        String id = store.issue("user-1", entry("m"), TTL);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Optional<NonceEntry>>> results = new ArrayList<>();
            for (int i = 0; i < 32; i++) {
                results.add(pool.submit(() -> store.consume("user-1", id)));
            }
            long successes = 0;
            for (Future<Optional<NonceEntry>> f : results) {
                if (f.get().isPresent()) successes++;
            }
            assertEquals(1, successes);
        } finally {
            pool.shutdownNow();
        }
    }
}
