package dk.panos.promofacie.v2;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record EntityDetailsRequest(
        List<String> addresses,
        @JsonProperty("aggregation_level") String aggregationLevel,
        @JsonProperty("opt_ins") OptIns optIns,
        String cursor
) {
    record OptIns(
            @JsonProperty("non_fungible_include_nfids") boolean nonFungibleIncludeNfts
    ) {}
}
