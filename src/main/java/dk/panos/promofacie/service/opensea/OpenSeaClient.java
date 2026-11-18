package dk.panos.promofacie.service.opensea;

import java.util.Optional;

public interface OpenSeaClient {

    /** False until an API key is configured; the OpenSea verification option is hidden and refused meanwhile. */
    boolean isConfigured();

    /**
     * Looks up the OpenSea account for an EVM address.
     *
     * @return empty if OpenSea has no profile for the address (404)
     * @throws OpenSeaUnavailableException on timeouts, rate limiting, server errors or a rejected API key
     */
    Optional<OpenSeaAccount> getAccount(String address);
}
