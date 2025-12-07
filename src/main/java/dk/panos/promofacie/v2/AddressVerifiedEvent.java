package dk.panos.promofacie.v2;

public record AddressVerifiedEvent(
        String discordId,
        String address,
        long timestamp
) {}