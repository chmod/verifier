package dk.panos.promofacie.service.diff;

import java.util.Set;

public record DiffResult(
        Set<AssetHolding> added,
        Set<AssetHolding> removed
) {}
