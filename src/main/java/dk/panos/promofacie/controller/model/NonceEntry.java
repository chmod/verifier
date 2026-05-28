package dk.panos.promofacie.controller.model;

import java.time.Instant;

public record NonceEntry(String nonce, Instant expiresAt) {
    public boolean isExpired() { return Instant.now().isAfter(expiresAt); }
}