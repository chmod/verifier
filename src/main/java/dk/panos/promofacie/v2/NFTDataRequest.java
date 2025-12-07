package dk.panos.promofacie.v2;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record NFTDataRequest(
        @JsonProperty("resource_address") String resourceAddress,
        @JsonProperty("non_fungible_ids") List<String> nonFungibleIds
) {}