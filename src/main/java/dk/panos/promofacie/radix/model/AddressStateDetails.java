package dk.panos.promofacie.radix.model;

import java.util.List;

// Define the main record that encompasses the entire JSON structure
public record AddressStateDetails(
        LedgerState ledgerState,
        List<Item> items
) {
    // Define the LedgerState record
    public record LedgerState(
            String network,
            int stateVersion,
            int epoch,
            int round
    ) {}

    // Define the Item record
    public record Item(
            String address,
            FungibleResources fungibleResources,
            NonFungibleResources nonFungibleResources
    ) {}

    // Define the FungibleResources record
    public record FungibleResources(
            List<ResourceItem> items
    ) {}

    public record NonFungibleResources(
            List<ResourceItem> items
    ) {}

    // Define the ResourceItem record
    public record ResourceItem(
            Vaults vaults,
            String aggregationLevel,
            String resourceAddress,
            ExplicitMetadata explicitMetadata
    ) {}

    // Define the Vaults record
    public record Vaults(
            int totalCount,
            List<Vault> items
    ) {}

    // Define the Vault record
    public record Vault(
            String vaultAddress,
            String amount
    ) {}

    // Define the ExplicitMetadata record
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
