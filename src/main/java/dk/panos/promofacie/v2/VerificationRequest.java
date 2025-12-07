package dk.panos.promofacie.v2;

public record VerificationRequest(
        String discordId,
        String guildId,
        String address,
        long timestamp
) {}