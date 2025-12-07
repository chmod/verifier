package dk.panos.promofacie.v2;

import java.util.List;

public record RoleUpdateEvent(
        String discordId,
        String guildId,
        List<String> roles,
        long timestamp
) {
}