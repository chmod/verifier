package dk.panos.promofacie.v2;

import java.util.List;

public record ResourceHoldings(
        List<String> nftIds,
        List<NFTMetadata> metadata
) {}