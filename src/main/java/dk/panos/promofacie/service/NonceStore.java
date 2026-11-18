package dk.panos.promofacie.service;

import dk.panos.promofacie.controller.model.NonceEntry;

import java.time.Duration;
import java.util.Optional;

/** Pending wallet-verification challenges. Several may be pending per user; each can be consumed once. */
public interface NonceStore {

    /** Stores the challenge and returns its id, which the client sends back on verify. */
    String issue(String discordId, NonceEntry entry, Duration ttl);

    /**
     * Atomically removes and returns the challenge. With a null/blank {@code nonceId}, falls back to the user's most
     * recently issued <em>signature</em> challenge (clients that predate nonce ids); other kinds are never popped
     * that way.
     */
    Optional<NonceEntry> consume(String discordId, String nonceId);

    /**
     * Returns the challenge without consuming it, for flows that may need several checks before succeeding (an
     * OpenSea bio edit can take a while to show up). {@code nonceId} is required: there is no latest fallback.
     */
    Optional<NonceEntry> peek(String discordId, String nonceId);
}
