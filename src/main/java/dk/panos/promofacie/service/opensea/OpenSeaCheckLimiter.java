package dk.panos.promofacie.service.opensea;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Limits OpenSea account lookups, which all share one API key and its quota. Three limits, checked in order:
 * <ol>
 *     <li>a minimum interval between two checks of the same user ({@code opensea.min-check-interval});</li>
 *     <li>a per-user cap per hour ({@code opensea.max-checks-per-user-per-hour}), so one member can't use up the
 *     shared quota by requesting fresh codes and checking every few seconds;</li>
 *     <li>a global cap per hour ({@code opensea.max-checks-per-hour}), sized to the API key's quota, so the key is
 *     never driven into OpenSea's own rate limiting and the number of blocked worker threads stays bounded.</li>
 * </ol>
 * A denied attempt records nothing, so being refused by the global cap doesn't use up a user's own allowance.
 * In memory, like the nonce store: single replica only.
 */
@ApplicationScoped
public class OpenSeaCheckLimiter {

    static final Duration WINDOW = Duration.ofHours(1);

    public enum Limit { NONE, USER_INTERVAL, USER_HOURLY, GLOBAL_HOURLY }

    /**
     * {@code limit == NONE} means allowed; otherwise {@code retryAfterSeconds} (at least 1) until it would be, and
     * {@code cap} the configured count of the hourly limit that refused it (0 for the interval). Callers must use
     * {@code cap} rather than reading this bean's fields: through the CDI client proxy those return the defaults.
     */
    public record Decision(Limit limit, long retryAfterSeconds, int cap) {
        static final Decision ALLOWED = new Decision(Limit.NONE, 0, 0);

        public boolean allowed() {
            return limit == Limit.NONE;
        }
    }

    @ConfigProperty(name = "opensea.min-check-interval", defaultValue = "10s")
    public Duration minCheckInterval = Duration.ofSeconds(10);

    @ConfigProperty(name = "opensea.max-checks-per-user-per-hour", defaultValue = "10")
    public int maxChecksPerUserPerHour = 10;

    /**
     * The issued key is on OpenSea's self-serve tier: 120 read requests per minute (about 7,200 per hour), shared by
     * every API key on the OpenSea account. 3,000 per hour leaves room for other keys and for bursts, which OpenSea
     * limits per minute; an OpenSea 429 still surfaces as a retryable 503.
     */
    @ConfigProperty(name = "opensea.max-checks-per-hour", defaultValue = "3000")
    public int maxChecksPerHour = 3000;

    public Clock clock = Clock.systemUTC();

    // Sliding-window logs of allowed checks within the last WINDOW. Guarded by "this": volume is a few checks a
    // minute at most, and one lock makes "check all three limits, then record" atomic.
    private final Map<String, Deque<Instant>> byUser = new HashMap<>();
    private final Deque<Instant> global = new ArrayDeque<>();

    /** Records the check and returns {@link Decision#ALLOWED}, or returns which limit refused it, recording nothing. */
    public synchronized Decision tryAcquire(String discordId) {
        Instant now = clock.instant();
        Instant windowStart = now.minus(WINDOW);
        prune(global, windowStart);
        Deque<Instant> mine = byUser.computeIfAbsent(discordId, id -> new ArrayDeque<>());
        prune(mine, windowStart);

        if (!mine.isEmpty() && now.isBefore(mine.peekLast().plus(minCheckInterval))) {
            return denied(Limit.USER_INTERVAL, now, mine.peekLast().plus(minCheckInterval), 0);
        }
        if (mine.size() >= maxChecksPerUserPerHour) {
            return denied(Limit.USER_HOURLY, now, mine.peekFirst().plus(WINDOW), maxChecksPerUserPerHour);
        }
        if (global.size() >= maxChecksPerHour) {
            if (mine.isEmpty()) byUser.remove(discordId);
            return denied(Limit.GLOBAL_HOURLY, now, global.peekFirst().plus(WINDOW), maxChecksPerHour);
        }
        mine.addLast(now);
        global.addLast(now);
        return Decision.ALLOWED;
    }

    /** Drops users with no check inside the window. */
    @Scheduled(every = "5m", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    synchronized void purge() {
        Instant windowStart = clock.instant().minus(WINDOW);
        prune(global, windowStart);
        byUser.values().removeIf(log -> {
            prune(log, windowStart);
            return log.isEmpty();
        });
    }

    /** Number of users currently tracked (for tests). */
    synchronized int trackedUsers() {
        return byUser.size();
    }

    private static void prune(Deque<Instant> log, Instant windowStart) {
        while (!log.isEmpty() && !log.peekFirst().isAfter(windowStart)) {
            log.pollFirst();
        }
    }

    private static Decision denied(Limit limit, Instant now, Instant allowedAt, int cap) {
        long millis = Duration.between(now, allowedAt).toMillis();
        return new Decision(limit, Math.max(1, (millis + 999) / 1000), cap);
    }
}
