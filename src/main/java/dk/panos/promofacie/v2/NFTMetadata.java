package dk.panos.promofacie.v2;

import java.util.Map;

public record NFTMetadata(
        String id,
        Map<String, String> traits,
        String name
) {}