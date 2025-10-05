package dk.panos.promofacie.radix.model;

import java.util.List;

public record GetNonFungibleVaultsResponse(
        List<Item> items

) {
    public record Item(int totalCount) {

    }
}
