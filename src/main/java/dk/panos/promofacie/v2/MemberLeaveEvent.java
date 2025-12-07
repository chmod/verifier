package dk.panos.promofacie.v2;

public record MemberLeaveEvent(
        String discordId,
        String guildId,
        long timestamp
) {}