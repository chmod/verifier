package dk.promofacie.wallet_verification.redis.redis_model;

import java.time.ZonedDateTime;

public record Verification(String address, String discordId, ZonedDateTime dateTime, int tries) {
    public Verification(String address, String discordId, ZonedDateTime dateTime) {
        this(address, discordId, dateTime, 0);
    }

    public Verification increaseTries() {
        return new Verification(address, discordId, dateTime, tries + 1);
    }
}


