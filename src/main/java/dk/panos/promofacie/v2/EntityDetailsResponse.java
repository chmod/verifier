package dk.panos.promofacie.v2;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record EntityDetailsResponse(
        List<EntityItem> items,
        @JsonProperty("next_cursor") String nextCursor,
        @JsonProperty("total_count") Integer totalCount
) {
    record EntityItem(
            String address,
            @JsonProperty("non_fungible_resources") NonFungibleResources nonFungibleResources
    ) {}

    record NonFungibleResources(
            List<NonFungibleResource> items
    ) {}

    record NonFungibleResource(
            @JsonProperty("resource_address") String resourceAddress,
            Vaults vaults
    ) {}

    record Vaults(
            List<VaultItem> items
    ) {}

    record VaultItem(
            @JsonProperty("total_count") int totalCount,
            List<String> items
    ) {}
}