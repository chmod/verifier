package dk.panos.promofacie.service;

import dk.panos.promofacie.controller.model.NonceEntry;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Pending challenges in memory: lost on restart (accepted), single replica only.
 * <p>
 * Each user has up to {@link #MAX_PENDING_PER_USER} slots; issuing beyond that drops the oldest, so memory stays
 * bounded per user and a client spamming challenges only evicts its own. Every per-user map is only touched inside
 * {@link ConcurrentHashMap#compute}, which makes consumption atomic: a challenge is usable at most once.
 */
@ApplicationScoped
public class InMemoryNonceStore implements NonceStore {

    /** Enough for retries and mobile page reloads while the user is in the wallet app. */
    static final int MAX_PENDING_PER_USER = 5;

    private record Pending(NonceEntry entry, Instant expiresAt) {
        boolean isExpired(Instant now) {
            return !now.isBefore(expiresAt);
        }
    }

    private final Map<String, LinkedHashMap<String, Pending>> byUser = new ConcurrentHashMap<>();
    private final Clock clock;

    public InMemoryNonceStore() {
        this(Clock.systemUTC());
    }

    InMemoryNonceStore(Clock clock) {
        this.clock = clock;
    }

    @Override
    public String issue(String discordId, NonceEntry entry, Duration ttl) {
        String nonceId = UUID.randomUUID().toString();
        Instant now = clock.instant();
        byUser.compute(discordId, (id, slots) -> {
            LinkedHashMap<String, Pending> s = slots != null ? slots : new LinkedHashMap<>();
            s.values().removeIf(p -> p.isExpired(now));
            s.put(nonceId, new Pending(entry, now.plus(ttl)));
            while (s.size() > MAX_PENDING_PER_USER) {
                s.pollFirstEntry(); // oldest first (insertion order)
            }
            return s;
        });
        return nonceId;
    }

    @Override
    public Optional<NonceEntry> consume(String discordId, String nonceId) {
        Instant now = clock.instant();
        AtomicReference<Pending> taken = new AtomicReference<>();
        byUser.computeIfPresent(discordId, (id, slots) -> {
            slots.values().removeIf(p -> p.isExpired(now));
            if (nonceId == null || nonceId.isBlank()) {
                // Clients that predate nonce ids: the most recently issued signature challenge. Skipping other kinds
                // keeps a pending OpenSea bio code from being burned by an old client's verify call.
                slots.sequencedEntrySet().reversed().stream()
                        .filter(e -> e.getValue().entry().isSignature())
                        .map(Map.Entry::getKey)
                        .findFirst()
                        .ifPresent(key -> taken.set(slots.remove(key)));
            } else {
                taken.set(slots.remove(nonceId));
            }
            return slots.isEmpty() ? null : slots;
        });
        return Optional.ofNullable(taken.get()).map(Pending::entry);
    }

    @Override
    public Optional<NonceEntry> peek(String discordId, String nonceId) {
        if (nonceId == null || nonceId.isBlank()) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        AtomicReference<Pending> found = new AtomicReference<>();
        byUser.computeIfPresent(discordId, (id, slots) -> {
            slots.values().removeIf(p -> p.isExpired(now));
            found.set(slots.get(nonceId));
            return slots.isEmpty() ? null : slots;
        });
        return Optional.ofNullable(found.get()).map(Pending::entry);
    }

    /** Drops expired challenges of users who never came back to verify. */
    @Scheduled(every = "1m", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void purgeExpired() {
        Instant now = clock.instant();
        for (String discordId : byUser.keySet()) {
            byUser.computeIfPresent(discordId, (id, slots) -> {
                slots.values().removeIf(p -> p.isExpired(now));
                return slots.isEmpty() ? null : slots;
            });
        }
    }

    /** Number of users with at least one pending challenge (for tests). */
    int usersWithPendingChallenges() {
        return byUser.size();
    }
}
