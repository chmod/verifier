package dk.panos.promofacie.v2;

import java.util.List;

public record VerificationResult(
        boolean success,
        String message,
        int nftCount,
        List<String> roles
) {}
