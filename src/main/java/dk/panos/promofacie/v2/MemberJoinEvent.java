package dk.panos.promofacie.v2;

public record MemberJoinEvent(
        String discordId,
        String guildId,
        long timestamp
) {}