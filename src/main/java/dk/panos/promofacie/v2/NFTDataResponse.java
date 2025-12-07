package dk.panos.promofacie.v2;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

public record NFTDataResponse(
        @JsonProperty("non_fungible_ids") List<NFTData> nonFungibleIds
) {
    record NFTData(
            @JsonProperty("non_fungible_id") String id,
            Map<String, Object> data
    ) {}
}