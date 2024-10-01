package dk.panos.promofacie.radix.model;

import java.util.List;

public record GetAddressDetails(
        List<String> addresses
        ) {
    public static final String AGGREGATION_LEVEL = "Vault";
    public static final OptIns OPT_INS = new OptIns(
            true, // ancestor_identities
            true, // component_royalty_config
            true, // component_royalty_vault_balance
            true, // package_royalty_vault_balance
            true, // non_fungible_include_nfids
            List.of("name", "description") // explicit_metadata
    );

    public String getAggregationLevel(){ return AGGREGATION_LEVEL; }
    public OptIns getOptIns() { return OPT_INS; }

    public record OptIns(
            boolean ancestorIdentities,
            boolean componentRoyaltyConfig,
            boolean componentRoyaltyVaultBalance,
            boolean packageRoyaltyVaultBalance,
            boolean nonFungibleIncludeNfids,
            List<String> explicitMetadata
    ) {
    }
}
