package dk.panos.promofacie.controller;

import dk.panos.promofacie.controller.model.NonceEntry;
import dk.panos.promofacie.controller.model.OpenSeaChallengeRequest;
import dk.panos.promofacie.controller.model.OpenSeaVerifyRequest;
import dk.panos.promofacie.controller.model.VerifyRequest;
import dk.panos.promofacie.db.Chain;
import dk.panos.promofacie.db.WalletAlreadyLinkedException;
import dk.panos.promofacie.db.WalletPersistenceService;
import dk.panos.promofacie.kafka.model.TrackingCommand;
import dk.panos.promofacie.service.InMemoryNonceStore;
import dk.panos.promofacie.service.RoleEvaluationService;
import dk.panos.promofacie.service.opensea.OpenSeaAccount;
import dk.panos.promofacie.service.opensea.OpenSeaClient;
import dk.panos.promofacie.service.opensea.OpenSeaUnavailableException;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class OpenSeaVerificationResourceTest {

    private static final String ADDRESS = "0x7dB967bcF885C8ae4bBFdB5fFff5D9ebF96AA0Ed";
    private static final String ADDRESS_LC = ADDRESS.toLowerCase();

    /** Fake OpenSea: a configurable profile per address, with a call counter. */
    private static final class FakeOpenSea implements OpenSeaClient {
        boolean configured = true;
        final Map<String, OpenSeaAccount> profiles = new HashMap<>();
        RuntimeException failWith;
        final AtomicInteger calls = new AtomicInteger();

        @Override public boolean isConfigured() { return configured; }

        @Override public Optional<OpenSeaAccount> getAccount(String address) {
            calls.incrementAndGet();
            if (failWith != null) throw failWith;
            return Optional.ofNullable(profiles.get(address.toLowerCase()));
        }
    }

    private static final class MutableClock extends Clock {
        final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-18T22:00:00Z"));
        void advance(Duration d) { now.updateAndGet(i -> i.plus(d)); }
        @Override public Instant instant() { return now.get(); }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
    }

    private OpenSeaVerificationResource resource;
    private FakeOpenSea openSea;
    private InMemoryNonceStore nonceStore;
    private JsonWebToken jwt;
    private WalletPersistenceService persistence;
    private RoleEvaluationService roleEvaluation;
    private Emitter<TrackingCommand> robinhoodEmitter;
    private Emitter<TrackingCommand> solanaEmitter;
    private MutableClock clock;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        resource = new OpenSeaVerificationResource();
        openSea = new FakeOpenSea();
        nonceStore = new InMemoryNonceStore();
        jwt = mock(JsonWebToken.class);
        persistence = mock(WalletPersistenceService.class);
        roleEvaluation = mock(RoleEvaluationService.class);
        robinhoodEmitter = mock(Emitter.class);
        solanaEmitter = mock(Emitter.class);
        when(solanaEmitter.send(any(TrackingCommand.class))).thenReturn(CompletableFuture.completedFuture(null));
        clock = new MutableClock();
        when(robinhoodEmitter.send(any(TrackingCommand.class))).thenReturn(CompletableFuture.completedFuture(null));
        when(jwt.getClaim("discord_id")).thenReturn("user-1");
        when(jwt.getClaim("preferred_username")).thenReturn("holder_42");

        resource.jwt = jwt;
        resource.openSea = openSea;
        resource.nonceStore = nonceStore;
        resource.walletPersistenceService = persistence;
        resource.roleEvaluationService = roleEvaluation;
        resource.robinhoodTrackingEmitter = robinhoodEmitter;
        resource.solanaTrackingEmitter = solanaEmitter;
        resource.clock = clock;
    }

    private Map<?, ?> challenge(String address) {
        Response res = resource.challenge(new OpenSeaChallengeRequest(address));
        assertEquals(200, res.getStatus(), String.valueOf(res.getEntity()));
        return (Map<?, ?>) res.getEntity();
    }

    private Response checkBio(Map<?, ?> challenge) {
        return resource.verify(new OpenSeaVerifyRequest((String) challenge.get("nonceId")));
    }

    private void profileWithBio(String address, String bio) {
        openSea.profiles.put(address.toLowerCase(), new OpenSeaAccount(address, bio));
    }

    private void profile(String primary, String bio, String... wallets) {
        OpenSeaAccount account = new OpenSeaAccount(primary, bio, java.util.List.of(wallets));
        openSea.profiles.put(primary.toLowerCase(), account);
        for (String w : wallets) {
            openSea.profiles.put(w.toLowerCase(), account); // looking up a public linked wallet resolves the same account
        }
    }

    @SuppressWarnings("unchecked")
    private static java.util.List<Map<String, String>> list(Response res, String key) {
        return (java.util.List<Map<String, String>>) ((Map<?, ?>) res.getEntity()).get(key);
    }

    private static String message(Response res) {
        return (String) ((Map<?, ?>) res.getEntity()).get("message");
    }

    @Test
    void statusReportsWhetherConfigured() {
        assertEquals(true, ((Map<?, ?>) resource.status().getEntity()).get("enabled"));
        openSea.configured = false;
        assertEquals(false, ((Map<?, ?>) resource.status().getEntity()).get("enabled"));
    }

    @Test
    void challengeReturnsCodeAndBioTextNamingSiteAndUser() {
        Map<?, ?> body = challenge(ADDRESS);

        String code = (String) body.get("code");
        assertTrue(code.matches("PF-[A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4}"), code);
        assertEquals("Linking this wallet to Discord @holder_42 on associate.promofacie.site: " + code, body.get("bioText"));
        assertNotNull(body.get("nonceId"));
        assertEquals(1800L, body.get("expiresInSeconds"));
    }

    @Test
    void bioTextFallsBackToDiscordIdForUnsafeOrNumericUsernames() {
        when(jwt.getClaim("preferred_username")).thenReturn("evil\nname");
        assertTrue(((String) challenge(ADDRESS).get("bioText")).contains("Discord ID user-1 on"));
        when(jwt.getClaim("preferred_username")).thenReturn("123456789012345678");
        assertTrue(((String) challenge(ADDRESS).get("bioText")).contains("Discord ID user-1 on"));
    }

    @Test
    void codesAreUnique() {
        assertNotEquals(challenge(ADDRESS).get("code"), challenge(ADDRESS).get("code"));
    }

    @Test
    void challengeRejectsInvalidAddress() {
        assertEquals(400, resource.challenge(new OpenSeaChallengeRequest("holder_42")).getStatus());
        assertEquals(400, resource.challenge(new OpenSeaChallengeRequest("bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh")).getStatus());
        assertEquals(400, resource.challenge(new OpenSeaChallengeRequest(null)).getStatus());
        assertEquals(400, resource.challenge(null).getStatus());
    }

    @Test
    void notConfiguredRefusesBothSteps() {
        Map<?, ?> body = challenge(ADDRESS);
        openSea.configured = false;

        assertEquals(503, resource.challenge(new OpenSeaChallengeRequest(ADDRESS)).getStatus());
        assertEquals(503, checkBio(body).getStatus());
        assertEquals(0, openSea.calls.get());
    }

    @Test
    void verifiesWhenPrimaryAddressBioContainsTheCode() {
        Map<?, ?> body = challenge(ADDRESS);
        profileWithBio(ADDRESS, "gm. " + body.get("bioText") + " wagmi");

        Response res = checkBio(body);

        assertEquals(200, res.getStatus(), message(res));
        verify(persistence).persist(ADDRESS_LC, "user-1", Chain.ROBINHOOD);
        verify(roleEvaluation).enqueueUserEvaluationForAllGuilds("user-1");
        ArgumentCaptor<TrackingCommand> cmd = ArgumentCaptor.forClass(TrackingCommand.class);
        verify(robinhoodEmitter).send(cmd.capture());
        assertEquals(TrackingCommand.Action.ADD_ADDRESS, cmd.getValue().action());
        assertEquals(ADDRESS_LC, cmd.getValue().stakeAddress());
    }

    @Test
    void codeAloneInBioIsEnough() {
        Map<?, ?> body = challenge(ADDRESS);
        profileWithBio(ADDRESS, (String) body.get("code"));

        assertEquals(200, checkBio(body).getStatus());
    }

    @Test
    void missingCodeKeepsTheChallengeForAnotherTry() {
        Map<?, ?> body = challenge(ADDRESS);
        profileWithBio(ADDRESS, "old bio");

        Response first = checkBio(body);
        assertEquals(400, first.getStatus());
        assertEquals("BIO_CODE_MISSING", ((Map<?, ?>) first.getEntity()).get("code"));
        verify(persistence, never()).persist(anyString(), anyString(), any());

        clock.advance(Duration.ofSeconds(11));
        profileWithBio(ADDRESS, (String) body.get("bioText"));
        assertEquals(200, checkBio(body).getStatus());
    }

    @Test
    void someoneElsesCodeDoesNotCount() {
        Map<?, ?> mine = challenge(ADDRESS);
        String otherCode = OpenSeaVerificationResource.newCode();
        profileWithBio(ADDRESS, "Linking this wallet to Discord @holder_42 on associate.promofacie.site: " + otherCode);

        assertEquals(400, checkBio(mine).getStatus());
    }

    @Test
    void profileWhosePrimaryAddressDiffersIsRejected() {
        // Lookup by our address resolves to an account whose primary address is another one (e.g. a linked wallet)
        Map<?, ?> body = challenge(ADDRESS);
        openSea.profiles.put(ADDRESS_LC, new OpenSeaAccount("0x1111111111111111111111111111111111111111",
                (String) body.get("bioText")));

        Response res = checkBio(body);

        assertEquals(400, res.getStatus());
        assertTrue(message(res).contains("isn't a public wallet"));
        verify(persistence, never()).persist(anyString(), anyString(), any());
    }

    private static final String SECOND_EVM = "0xAbCdEf0123456789aBcDeF0123456789AbCdEf01";
    private static final String SOLANA = "7Np41oeYqPefeNQEHSv1UDhYrehxin3NStELsSKCT4K2";

    @Test
    void linksEveryPublicWalletOfTheProfileInOneCheck() {
        Map<?, ?> body = challenge(ADDRESS);
        profile(ADDRESS, (String) body.get("bioText"), SECOND_EVM, SOLANA);

        Response res = checkBio(body);

        assertEquals(200, res.getStatus(), message(res));
        assertEquals(3, list(res, "linked").size());
        assertTrue(list(res, "skipped").isEmpty());
        verify(persistence).persist(ADDRESS_LC, "user-1", Chain.ROBINHOOD);
        verify(persistence).persist(SECOND_EVM.toLowerCase(), "user-1", Chain.ROBINHOOD);
        verify(persistence).persist(SOLANA, "user-1", Chain.SOLANA); // Solana is case-sensitive: stored as-is
        verify(robinhoodEmitter, times(2)).send(any(TrackingCommand.class));
        verify(solanaEmitter).send(any(TrackingCommand.class));
        verify(roleEvaluation, times(1)).enqueueUserEvaluationForAllGuilds("user-1");
    }

    @Test
    void anyPublicWalletOfTheProfileCanStartTheCheck() {
        // The member types a linked (non-primary) wallet; OpenSea resolves it to the same profile
        Map<?, ?> body = challenge(SECOND_EVM);
        profile(ADDRESS, (String) body.get("bioText"), SECOND_EVM);

        Response res = checkBio(body);

        assertEquals(200, res.getStatus(), message(res));
        verify(persistence).persist(ADDRESS_LC, "user-1", Chain.ROBINHOOD);
        verify(persistence).persist(SECOND_EVM.toLowerCase(), "user-1", Chain.ROBINHOOD);
    }

    @Test
    void challengeAcceptsASolanaAddress() {
        Map<?, ?> body = challenge(SOLANA);
        profile(ADDRESS, (String) body.get("bioText"), SOLANA);

        assertEquals(200, checkBio(body).getStatus());
        verify(persistence).persist(SOLANA, "user-1", Chain.SOLANA);
    }

    @Test
    void walletsLinkedToAnotherAccountAreSkippedOthersStillLinked() {
        Map<?, ?> body = challenge(ADDRESS);
        profile(ADDRESS, (String) body.get("bioText"), SECOND_EVM);
        String second = SECOND_EVM.toLowerCase();
        doThrow(new WalletAlreadyLinkedException(second)).when(persistence).persist(second, "user-1", Chain.ROBINHOOD);

        Response res = checkBio(body);

        assertEquals(200, res.getStatus());
        assertEquals(java.util.List.of(Map.of("address", ADDRESS_LC, "chain", "ROBINHOOD")), list(res, "linked"));
        assertEquals(java.util.List.of(Map.of("address", second, "chain", "ROBINHOOD", "reason", "LINKED_TO_ANOTHER_ACCOUNT")),
                list(res, "skipped"));
        verify(robinhoodEmitter, times(1)).send(any(TrackingCommand.class));
        verify(roleEvaluation).enqueueUserEvaluationForAllGuilds("user-1");
    }

    @Test
    void unsupportedAndDuplicateWalletsAreIgnored() {
        OpenSeaAccount account = new OpenSeaAccount(ADDRESS, "bio",
                java.util.List.of(ADDRESS_LC, ADDRESS.toUpperCase().replace("0X", "0x"), "bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh", "  ", SOLANA));

        assertEquals(java.util.List.of(ADDRESS_LC, SOLANA), OpenSeaVerificationResource.profileWallets(account));
    }

    @Test
    void walletsPerProfileAreCapped() {
        java.util.List<String> many = new java.util.ArrayList<>();
        for (int i = 0; i < 40; i++) {
            many.add(String.format("0x%040x", i + 1));
        }
        OpenSeaAccount account = new OpenSeaAccount(ADDRESS, "bio", many);

        java.util.List<String> wallets = OpenSeaVerificationResource.profileWallets(account);

        assertEquals(OpenSeaVerificationResource.MAX_WALLETS_PER_PROFILE, wallets.size());
        assertEquals(ADDRESS_LC, wallets.get(0), "the primary address always comes first");
    }

    @Test
    void noProfileReturns404() {
        assertEquals(404, checkBio(challenge(ADDRESS)).getStatus());
    }

    @Test
    void openSeaOutageReturns503AndKeepsTheChallenge() {
        Map<?, ?> body = challenge(ADDRESS);
        openSea.failWith = new OpenSeaUnavailableException("HTTP 429");

        assertEquals(503, checkBio(body).getStatus());

        openSea.failWith = null;
        profileWithBio(ADDRESS, (String) body.get("bioText"));
        clock.advance(Duration.ofSeconds(11));
        assertEquals(200, checkBio(body).getStatus());
    }

    @Test
    void checksAreThrottledPerUser() {
        Map<?, ?> body = challenge(ADDRESS);
        profileWithBio(ADDRESS, "not yet");

        assertEquals(400, checkBio(body).getStatus());
        Response throttled = checkBio(body);
        assertEquals(429, throttled.getStatus());
        assertTrue((Long) ((Map<?, ?>) throttled.getEntity()).get("retryAfterSeconds") > 0);
        assertEquals(1, openSea.calls.get(), "throttled checks must not call OpenSea");

        clock.advance(Duration.ofSeconds(10));
        assertEquals(400, checkBio(body).getStatus());
        assertEquals(2, openSea.calls.get());
    }

    @Test
    void throttleIsPerUser() {
        Map<?, ?> mine = challenge(ADDRESS);
        profileWithBio(ADDRESS, "not yet");
        assertEquals(400, checkBio(mine).getStatus());

        when(jwt.getClaim("discord_id")).thenReturn("user-2");
        Map<?, ?> theirs = challenge(ADDRESS);
        assertEquals(400, checkBio(theirs).getStatus(), "another user's check is not throttled by mine");
    }

    @Test
    void codeIsSingleUse() {
        Map<?, ?> body = challenge(ADDRESS);
        profileWithBio(ADDRESS, (String) body.get("bioText"));

        assertEquals(200, checkBio(body).getStatus());
        clock.advance(Duration.ofSeconds(11));
        assertEquals(400, checkBio(body).getStatus());
        verify(persistence, times(1)).persist(anyString(), anyString(), any());
    }

    @Test
    void challengeIsScopedToTheDiscordUser() {
        Map<?, ?> body = challenge(ADDRESS);
        profileWithBio(ADDRESS, (String) body.get("bioText"));

        when(jwt.getClaim("discord_id")).thenReturn("attacker");
        assertEquals(400, checkBio(body).getStatus());
        verify(persistence, never()).persist(anyString(), anyString(), any());
    }

    @Test
    void walletLinkedToAnotherAccountReturns409() {
        Map<?, ?> body = challenge(ADDRESS);
        profileWithBio(ADDRESS, (String) body.get("bioText"));
        doThrow(new WalletAlreadyLinkedException(ADDRESS_LC)).when(persistence).persist(ADDRESS_LC, "user-1", Chain.ROBINHOOD);

        Response res = checkBio(body);

        assertEquals(409, res.getStatus());
        assertEquals(1, list(res, "skipped").size());
        verify(robinhoodEmitter, never()).send(any(TrackingCommand.class));
        verify(roleEvaluation, never()).enqueueUserEvaluationForAllGuilds(anyString());
    }

    @Test
    void signatureChallengeCannotBeSatisfiedThroughTheBio() {
        String sigNonceId = nonceStore.issue("user-1",
                new NonceEntry("siwe message", Chain.ROBINHOOD, ADDRESS_LC), Duration.ofMinutes(5));
        profileWithBio(ADDRESS, "siwe message");

        assertEquals(400, resource.verify(new OpenSeaVerifyRequest(sigNonceId)).getStatus());
        assertEquals(0, openSea.calls.get());
    }

    @Test
    void bioChallengeCannotBeUsedForSignatureVerification() {
        // A bio code has no domain binding; accepting it as a signed message would reopen the phishing relay.
        Map<?, ?> body = challenge(ADDRESS);
        WalletVerificationResource sigResource = new WalletVerificationResource();
        sigResource.jwt = jwt;
        sigResource.nonceStore = nonceStore;
        sigResource.walletPersistenceService = persistence;

        Response res = sigResource.verify(new VerifyRequest(null, ADDRESS, "0x" + "ab".repeat(65), null, "ROBINHOOD",
                (String) body.get("nonceId")));

        assertEquals(400, res.getStatus());
        verify(persistence, never()).persist(anyString(), anyString(), any());
    }
}
