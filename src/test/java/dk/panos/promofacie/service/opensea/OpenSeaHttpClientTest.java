package dk.panos.promofacie.service.opensea;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Runs the real HTTP client against a local server returning canned OpenSea responses. */
class OpenSeaHttpClientTest {

    private static final String ADDRESS = "0x7db967bcf885c8ae4bbfdb5ffff5d9ebf96aa0ed";

    private HttpServer server;
    private final AtomicReference<String> requestPath = new AtomicReference<>();
    private final AtomicReference<String> requestKey = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String body = "";
    private OpenSeaHttpClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestPath.set(exchange.getRequestURI().toString());
            requestKey.set(exchange.getRequestHeaders().getFirst("x-api-key"));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        client = new OpenSeaHttpClient();
        client.apiKey = Optional.of("test-key");
        client.baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void parsesAddressAndBioAndSendsTheKey() {
        body = """
                {"address":"0x7dB967bcF885C8ae4bBFdB5fFff5D9ebF96AA0Ed","username":"holder","bio":"hello PF-AAAA",
                 "follower_count":1,"following_count":2,"is_agent":false,"is_verified":false,
                 "joined_date":"2026-01-01","social_media_accounts":[],
                 "wallets":["0x7db967bcf885c8ae4bbfdb5ffff5d9ebf96aa0ed","7Np41oeYqPefeNQEHSv1UDhYrehxin3NStELsSKCT4K2",""]}""";

        OpenSeaAccount account = client.getAccount(ADDRESS).orElseThrow();

        assertEquals("0x7dB967bcF885C8ae4bBFdB5fFff5D9ebF96AA0Ed", account.address());
        assertEquals("hello PF-AAAA", account.bio());
        assertEquals("holder", account.username());
        assertEquals(java.util.List.of("0x7db967bcf885c8ae4bbfdb5ffff5d9ebf96aa0ed", "7Np41oeYqPefeNQEHSv1UDhYrehxin3NStELsSKCT4K2"),
                account.wallets(), "blank entries dropped");
        assertEquals("/api/v2/accounts/" + ADDRESS + "?include=wallets", requestPath.get());
        assertEquals("test-key", requestKey.get());
    }

    @Test
    void missingBioAndWalletsAreEmpty() {
        body = """
                {"address":"0x7db967bcf885c8ae4bbfdb5ffff5d9ebf96aa0ed"}""";
        OpenSeaAccount account = client.getAccount(ADDRESS).orElseThrow();
        assertEquals("", account.bio());
        assertTrue(account.wallets().isEmpty());
    }

    @Test
    void solanaAddressesCanBeLookedUp() {
        body = """
                {"address":"0x7db967bcf885c8ae4bbfdb5ffff5d9ebf96aa0ed","bio":""}""";
        client.getAccount("7Np41oeYqPefeNQEHSv1UDhYrehxin3NStELsSKCT4K2");
        assertEquals("/api/v2/accounts/7Np41oeYqPefeNQEHSv1UDhYrehxin3NStELsSKCT4K2?include=wallets", requestPath.get());
    }

    @Test
    void notFoundIsEmpty() {
        status = 404;
        body = """
                {"errors":["not found"]}""";
        assertEquals(Optional.empty(), client.getAccount(ADDRESS));
    }

    @Test
    void rateLimitServerErrorAndRejectedKeyAreUnavailable() {
        for (int s : new int[]{429, 500, 503, 401, 403}) {
            status = s;
            body = "{}";
            assertThrows(OpenSeaUnavailableException.class, () -> client.getAccount(ADDRESS), "HTTP " + s);
        }
    }

    @Test
    void responseWithoutAddressIsUnavailable() {
        body = """
                {"bio":"x"}""";
        assertThrows(OpenSeaUnavailableException.class, () -> client.getAccount(ADDRESS));
    }

    @Test
    void garbageIsUnavailable() {
        body = "<html>oops</html>";
        assertThrows(OpenSeaUnavailableException.class, () -> client.getAccount(ADDRESS));
    }

    @Test
    void onlyValidatedAddressesAreSent() {
        // The OpenSea path parameter also accepts usernames; never let one through
        assertThrows(IllegalArgumentException.class, () -> client.getAccount("holder_42"));
        assertThrows(IllegalArgumentException.class, () -> client.getAccount("0x1234/../../admin"));
        assertNull(requestPath.get());
    }

    @Test
    void unconfiguredClientReportsItAndRefuses() {
        client.apiKey = Optional.empty();
        assertFalse(client.isConfigured());
        client.apiKey = Optional.of("  ");
        assertFalse(client.isConfigured());
        assertThrows(OpenSeaUnavailableException.class, () -> client.getAccount(ADDRESS));
    }

    @Test
    void unreachableServerIsUnavailable() {
        server.stop(0);
        assertThrows(OpenSeaUnavailableException.class, () -> client.getAccount(ADDRESS));
    }
}
