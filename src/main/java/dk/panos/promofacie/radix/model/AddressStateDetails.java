package dk.panos.promofacie.radix.model;

import java.util.List;

public record AddressStateDetails(
        LedgerState ledgerState,
        List<Item> items
) {
    public record LedgerState(
            String network,
            int stateVersion,
            int epoch,
            int round
    ) {}

    public record Item(
            String address,
            FungibleResources fungibleResources,
            NonFungibleResources nonFungibleResources
    ) {}

    public record FungibleResources(
            List<ResourceItem> items
    ) {}

    public record NonFungibleResources(
            List<ResourceItem> items
    ) {}

    public record ResourceItem(
            Vaults vaults,
            String aggregationLevel,
            String resourceAddress,
            ExplicitMetadata explicitMetadata
    ) {}

    public record Vaults(
            int totalCount,
            List<Vault> items
    ) {}

    public record Vault(
            String vaultAddress,
            String amount
    ) {}

    public record ExplicitMetadata(
            int totalCount,
            List<MetadataItem> items
    ) {}

    // Define the MetadataItem record
    public record MetadataItem(
            String key,
            MetadataValue value
    ) {}

    // Define the MetadataValue record
    public record MetadataValue(
            Typed typed
    ) {}

    // Define the Typed record
    public record Typed(
            String value,
            String type
    ) {}
}
