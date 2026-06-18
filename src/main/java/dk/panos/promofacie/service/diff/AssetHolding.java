package dk.panos.promofacie.service.diff;

import java.util.Map;

public record AssetHolding(
        String policyId,
        String assetNameHex,
        long quantity,
        Map<String, String> traits
) {}
