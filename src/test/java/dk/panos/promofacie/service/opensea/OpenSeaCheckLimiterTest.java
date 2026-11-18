package dk.panos.promofacie.service.opensea;

import dk.panos.promofacie.service.opensea.OpenSeaCheckLimiter.Decision;
import dk.panos.promofacie.service.opensea.OpenSeaCheckLimiter.Limit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class OpenSeaCheckLimiterTest {

    private static final class MutableClock extends Clock {
        final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-19T10:00:00Z"));
        void advance(Duration d) { now.updateAndGet(i -> i.plus(d)); }
        @Override public Instant instant() { return now.get(); }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }

    private MutableClock clock;
    private OpenSeaCheckLimiter limiter;

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
        limiter = new OpenSeaCheckLimiter();
        limiter.clock = clock;
        limiter.minCheckInterval = Duration.ofSeconds(10);
        limiter.maxChecksPerUserPerHour = 3;
        limiter.maxChecksPerHour = 5;
    }

    @Test
    void enforcesTheMinimumIntervalPerUser() {
        assertTrue(limiter.tryAcquire("a").allowed());
        clock.advance(Duration.ofSeconds(4));
        Decision d = limiter.tryAcquire("a");
        assertEquals(Limit.USER_INTERVAL, d.limit());
        assertEquals(6, d.retryAfterSeconds());

        assertTrue(limiter.tryAcquire("b").allowed(), "the interval is per user");
        clock.advance(Duration.ofSeconds(6));
        assertTrue(limiter.tryAcquire("a").allowed());
    }

    @Test
    void capsChecksPerUserPerHourAndRecoversAsTheWindowSlides() {
        for (int i = 0; i < 3; i++) {
            assertTrue(limiter.tryAcquire("a").allowed());
            clock.advance(Duration.ofMinutes(10));
        }
        // Checks at 0, 10 and 20 minutes; now it's 30 minutes. The first one leaves the window at 60 minutes.
        Decision d = limiter.tryAcquire("a");
        assertEquals(Limit.USER_HOURLY, d.limit());
        assertEquals(Duration.ofMinutes(30).toSeconds(), d.retryAfterSeconds());
        assertEquals(3, d.cap(), "reports the configured cap");

        clock.advance(Duration.ofMinutes(30));
        assertTrue(limiter.tryAcquire("a").allowed(), "the oldest check has left the window");
        clock.advance(Duration.ofSeconds(10));
        assertEquals(Limit.USER_HOURLY, limiter.tryAcquire("a").limit(), "only one slot freed");
    }

    @Test
    void capsChecksGloballyAcrossUsers() {
        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.tryAcquire("user-" + i).allowed());
        }
        clock.advance(Duration.ofMinutes(15));
        Decision d = limiter.tryAcquire("newcomer");
        assertEquals(Limit.GLOBAL_HOURLY, d.limit());
        assertEquals(Duration.ofMinutes(45).toSeconds(), d.retryAfterSeconds());
        assertEquals(5, d.cap(), "reports the configured cap");

        clock.advance(Duration.ofMinutes(45));
        assertTrue(limiter.tryAcquire("newcomer").allowed());
    }

    @Test
    void refusedAttemptsRecordNothing() {
        limiter.maxChecksPerHour = 1;
        assertTrue(limiter.tryAcquire("a").allowed());
        for (int i = 0; i < 10; i++) {
            clock.advance(Duration.ofSeconds(11));
            assertEquals(Limit.GLOBAL_HOURLY, limiter.tryAcquire("b").limit());
        }
        assertEquals(1, limiter.trackedUsers(), "a user refused before any allowed check isn't tracked");

        // Ten refusals by the global cap must not count against b's own allowance of 3
        limiter.maxChecksPerHour = 100;
        for (int i = 0; i < 3; i++) {
            clock.advance(Duration.ofSeconds(11));
            assertTrue(limiter.tryAcquire("b").allowed(), "check " + (i + 1));
        }
        clock.advance(Duration.ofSeconds(11));
        assertEquals(Limit.USER_HOURLY, limiter.tryAcquire("b").limit());
    }

    @Test
    void retryAfterIsRoundedUpAndAtLeastOneSecond() {
        assertTrue(limiter.tryAcquire("a").allowed());
        clock.advance(Duration.ofMillis(9_500));
        assertEquals(1, limiter.tryAcquire("a").retryAfterSeconds());
        clock.advance(Duration.ofMillis(100));
        assertEquals(1, limiter.tryAcquire("a").retryAfterSeconds());
    }

    @Test
    void purgeDropsIdleUsers() {
        limiter.tryAcquire("a");
        limiter.tryAcquire("b");
        assertEquals(2, limiter.trackedUsers());
        clock.advance(Duration.ofMinutes(30));
        limiter.purge();
        assertEquals(2, limiter.trackedUsers(), "still inside the window");
        clock.advance(Duration.ofMinutes(31));
        limiter.purge();
        assertEquals(0, limiter.trackedUsers());
    }

    @Test
    void globalCapHoldsUnderConcurrency() throws Exception {
        limiter.maxChecksPerHour = 7;
        limiter.maxChecksPerUserPerHour = 100;
        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger allowed = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            String user = "user-" + i;
            pool.submit(() -> {
                start.await();
                if (limiter.tryAcquire(user).allowed()) allowed.incrementAndGet();
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        assertEquals(7, allowed.get());
    }
}
