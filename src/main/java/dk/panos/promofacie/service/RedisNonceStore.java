package dk.panos.promofacie.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.panos.promofacie.controller.model.NonceEntry;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.value.SetArgs;
import io.quarkus.redis.datasource.value.ValueCommands;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Challenges in Redis, so they survive restarts and work across replicas. Expiry is the key TTL; consumption is
 * GETDEL, so a challenge can be used at most once even under concurrent verify calls.
 */
@ApplicationScoped
public class RedisNonceStore implements NonceStore {

    private static final Logger log = LoggerFactory.getLogger(RedisNonceStore.class);
    static final String PREFIX = "wallet-verification:nonce:";
    static final String LATEST_PREFIX = "wallet-verification:nonce-latest:";
    static final String COUNT_PREFIX = "wallet-verification:nonce-count:";
    /** Challenges a user may request per TTL window: enough for retries and page reloads, bounded for Redis memory. */
    static final int MAX_CHALLENGES_PER_WINDOW = 10;
    private static final Pattern NONCE_ID = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Inject
    RedisDataSource redis;

    /** Set directly in tests; otherwise created from {@link #redis} on first use. */
    ValueCommands<String, String> values;

    private ValueCommands<String, String> values() {
        if (values == null) {
            values = redis.value(String.class);
        }
        return values;
    }

    static String entryKey(String discordId, String nonceId) {
        return PREFIX + discordId + ":" + nonceId;
    }

    static String latestKey(String discordId) {
        return LATEST_PREFIX + discordId;
    }

    static String countKey(String discordId) {
        return COUNT_PREFIX + discordId;
    }

    @Override
    public String issue(String discordId, NonceEntry entry, Duration ttl) {
        // Fixed window: the counter is created with the TTL (NX keeps an existing window) and INCR preserves it
        values().set(countKey(discordId), "0", new SetArgs().nx().ex(ttl));
        if (values().incr(countKey(discordId)) > MAX_CHALLENGES_PER_WINDOW) {
            throw new TooManyChallengesException();
        }
        String nonceId = UUID.randomUUID().toString();
        String json;
        try {
            json = JSON.writeValueAsString(entry);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize challenge", e);
        }
        values().set(entryKey(discordId, nonceId), json, new SetArgs().ex(ttl));
        values().set(latestKey(discordId), nonceId, new SetArgs().ex(ttl));
        return nonceId;
    }

    @Override
    public Optional<NonceEntry> consume(String discordId, String nonceId) {
        String id = nonceId;
        if (id == null || id.isBlank()) {
            id = values().getdel(latestKey(discordId));
            if (id == null) {
                return Optional.empty();
            }
        }
        if (!NONCE_ID.matcher(id).matches()) {
            return Optional.empty();
        }
        String json = values().getdel(entryKey(discordId, id));
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(JSON.readValue(json, NonceEntry.class));
        } catch (JsonProcessingException e) {
            log.warn("Discarding unreadable challenge for discordId={}", discordId, e);
            return Optional.empty();
        }
    }
}
