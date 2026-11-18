package dk.panos.promofacie.service.opensea;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import dk.panos.promofacie.util.EvmAddress;
import dk.panos.promofacie.util.SolanaAddress;

/**
 * OpenSea API v2 client for account lookups. The API key comes from config property {@code opensea.api-key}
 * (Vault {@code application/config}, or env {@code OPENSEA_API_KEY}); it is sent only in the {@code x-api-key}
 * header and never logged.
 */
@ApplicationScoped
public class OpenSeaHttpClient implements OpenSeaClient {

    private static final Logger log = LoggerFactory.getLogger(OpenSeaHttpClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    @ConfigProperty(name = "opensea.api-key")
    Optional<String> apiKey = Optional.empty();

    @ConfigProperty(name = "opensea.base-url", defaultValue = "https://api.opensea.io")
    String baseUrl = "https://api.opensea.io";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @Override
    public boolean isConfigured() {
        return apiKey.filter(k -> !k.isBlank()).isPresent();
    }

    @Override
    public Optional<OpenSeaAccount> getAccount(String address) {
        if (!isConfigured()) {
            throw new OpenSeaUnavailableException("OpenSea API key is not configured");
        }
        // The path parameter also accepts usernames: only ever send a validated EVM or Solana address.
        if (!EvmAddress.isEvm(address) && !SolanaAddress.isValid(address)) {
            throw new IllegalArgumentException("Not an EVM or Solana address");
        }
        // include=wallets adds the profile's public, signature-proven wallets. Not cached, as OpenSea requires.
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/v2/accounts/" + address + "?include=wallets"))
                .timeout(REQUEST_TIMEOUT)
                .header("x-api-key", apiKey.get().trim())
                .header("accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new OpenSeaUnavailableException("OpenSea request failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OpenSeaUnavailableException("OpenSea request interrupted", e);
        }

        int status = response.statusCode();
        if (status == 404) {
            return Optional.empty();
        }
        if (status == 401 || status == 403) {
            log.error("OpenSea rejected the API key (HTTP {})", status);
            throw new OpenSeaUnavailableException("OpenSea rejected the API key");
        }
        if (status != 200) {
            log.warn("OpenSea account lookup failed with HTTP {}", status);
            throw new OpenSeaUnavailableException("OpenSea returned HTTP " + status);
        }
        try {
            JsonNode body = JSON.readTree(response.body());
            String returnedAddress = body.path("address").asText(null);
            if (returnedAddress == null || returnedAddress.isBlank()) {
                throw new OpenSeaUnavailableException("OpenSea response has no address");
            }
            List<String> wallets = new ArrayList<>();
            body.path("wallets").forEach(w -> {
                if (w.isTextual() && !w.asText().isBlank()) wallets.add(w.asText().trim());
            });
            return Optional.of(new OpenSeaAccount(returnedAddress, body.path("bio").asText(""), wallets));
        } catch (IOException e) {
            throw new OpenSeaUnavailableException("Unreadable OpenSea response", e);
        }
    }
}
