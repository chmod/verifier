package dk.panos.promofacie.radix.model;

import java.util.List;

public record GetNonFungibleVaultsRequest(
        String address,
        String resourceAddress
) {
}
