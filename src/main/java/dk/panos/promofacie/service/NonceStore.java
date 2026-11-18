package dk.panos.promofacie.service;

import dk.panos.promofacie.controller.model.NonceEntry;

import java.time.Duration;
import java.util.Optional;

/** Pending wallet-verification challenges. Several may be pending per user; each can be consumed once. */
public interface NonceStore {

    /**
     * Stores the challenge and returns its id, which the client sends back on verify.
     *
     * @throws TooManyChallengesException if the user exceeded the per-window issuance limit
     */
    String issue(String discordId, NonceEntry entry, Duration ttl);

    /**
     * Atomically removes and returns the challenge. With a null/blank {@code nonceId}, falls back to the user's most
     * recently issued challenge (clients that predate nonce ids).
     */
    Optional<NonceEntry> consume(String discordId, String nonceId);
}
