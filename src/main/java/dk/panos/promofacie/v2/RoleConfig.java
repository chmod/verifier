package dk.panos.promofacie.v2;

import java.util.List;

public record RoleConfig(
        List<ResourceRule> resources
) {
    /**
     * Configuration for a single resource (FT or NFT)
     */
    public record ResourceRule(
            String resourceAddress,
            ResourceType resourceType,
            List<HoldingTier> holdingTiers,
            List<TraitRule> traitRules
    ) {}

    /**
     * Holding-based role assignment (for both FT and NFT)
     * Example: 1-500 tokens → Bronze, 501-1000 → Silver, 1001+ → Gold
     */
    public record HoldingTier(
            Integer minAmount,
            Integer maxAmount,
            String roleId
    ) {}

    /**
     * Trait-based role assignment (only for NFTs)
     * Example: Any NFT with "Background: Blue" → Blue role
     */
    public record TraitRule(
            String traitName,
            String traitValue,
            String roleId
    ) {}

    public enum ResourceType {
        NFT,
        FT
    }
}