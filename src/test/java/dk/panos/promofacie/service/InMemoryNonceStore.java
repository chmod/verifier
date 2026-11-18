package dk.panos.promofacie.service;

import dk.panos.promofacie.controller.model.NonceEntry;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Test double for {@link RedisNonceStore} with the same semantics, minus TTL. */
public class InMemoryNonceStore implements NonceStore {
    private final Map<String, NonceEntry> entries = new ConcurrentHashMap<>();
    private final Map<String, String> latest = new ConcurrentHashMap<>();

    @Override
    public String issue(String discordId, NonceEntry entry, Duration ttl) {
        String nonceId = UUID.randomUUID().toString();
        entries.put(discordId + ":" + nonceId, entry);
        latest.put(discordId, nonceId);
        return nonceId;
    }

    @Override
    public Optional<NonceEntry> consume(String discordId, String nonceId) {
        String id = (nonceId == null || nonceId.isBlank()) ? latest.remove(discordId) : nonceId;
        if (id == null) return Optional.empty();
        return Optional.ofNullable(entries.remove(discordId + ":" + id));
    }
}
