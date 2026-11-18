package dk.panos.promofacie.controller;

import dk.panos.promofacie.controller.model.ChallengeRequest;
import dk.panos.promofacie.controller.model.VerifyRequest;
import dk.panos.promofacie.controller.model.WalletAssociationResponse;
import dk.panos.promofacie.db.Chain;
import dk.panos.promofacie.db.Wallet;
import dk.panos.promofacie.db.WalletAlreadyLinkedException;
import dk.panos.promofacie.db.WalletPersistenceService;
import dk.panos.promofacie.service.InMemoryNonceStore;
import dk.panos.promofacie.kafka.model.TrackingCommand;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class WalletVerificationResourceTest {

    private WalletVerificationResource resource;
    private JsonWebToken jwtMock;
    private Emitter<TrackingCommand> emitterMock;
    private Emitter<TrackingCommand> solanaEmitterMock;
    private Emitter<TrackingCommand> robinhoodEmitterMock;
    private WalletPersistenceService persistenceServiceMock;
    private dk.panos.promofacie.service.RoleEvaluationService roleEvaluationServiceMock;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        resource = new WalletVerificationResource();
        jwtMock = mock(JsonWebToken.class);
        emitterMock = mock(Emitter.class);
        solanaEmitterMock = mock(Emitter.class);
        robinhoodEmitterMock = mock(Emitter.class);
        persistenceServiceMock = mock(WalletPersistenceService.class);
        roleEvaluationServiceMock = mock(dk.panos.promofacie.service.RoleEvaluationService.class);

        resource.jwt = jwtMock;
        resource.walletTrackingEmitter = emitterMock;
        resource.solanaTrackingEmitter = solanaEmitterMock;
        resource.robinhoodTrackingEmitter = robinhoodEmitterMock;
        resource.nonceStore = new InMemoryNonceStore();
        resource.walletPersistenceService = persistenceServiceMock;
        resource.roleEvaluationService = roleEvaluationServiceMock;

        when(emitterMock.send(any(TrackingCommand.class))).thenReturn(CompletableFuture.completedFuture(null));
        when(solanaEmitterMock.send(any(TrackingCommand.class))).thenReturn(CompletableFuture.completedFuture(null));
        when(robinhoodEmitterMock.send(any(TrackingCommand.class))).thenReturn(CompletableFuture.completedFuture(null));
    }

    // ---- EVM / Sign-In with Ethereum (EIP-4361) ----

    private static final ECKeyPair EVM_KEY = ECKeyPair.create(BigInteger.valueOf(123456789L));
    private static final ECKeyPair OTHER_EVM_KEY = ECKeyPair.create(BigInteger.valueOf(987654321L));

    private static String evmAddress(ECKeyPair kp) {
        return "0x" + Keys.getAddress(kp); // lowercase, like most wallets return it
    }

    /** personal_sign: EIP-191 prefix + UTF-8 bytes, 65-byte r||s||v hex like wallets return. */
    private static String personalSign(String message, ECKeyPair kp) {
        Sign.SignatureData sd = Sign.signPrefixedMessage(message.getBytes(StandardCharsets.UTF_8), kp);
        HexFormat hex = HexFormat.of();
        return "0x" + hex.formatHex(sd.getR()) + hex.formatHex(sd.getS()) + hex.formatHex(sd.getV());
    }

    private Map<?, ?> evmChallenge(String address) {
        Response res = resource.challenge(new ChallengeRequest("ROBINHOOD", address));
        assertEquals(200, res.getStatus());
        return (Map<?, ?>) res.getEntity();
    }

    @Test
    void testEvmChallengeIsSiweBoundToDomainAndAddress() {
        when(jwtMock.getClaim("discord_id")).thenReturn("123456789012345678");
        String address = evmAddress(EVM_KEY);

        Map<?, ?> body = evmChallenge(address);
        String message = (String) body.get("nonce");
        String[] lines = message.split("\n", -1);

        assertNotNull(body.get("nonceId"));
        assertEquals("associate.promofacie.site wants you to sign in with your Ethereum account:", lines[0]);
        assertEquals(Keys.toChecksumAddress(address), lines[1]);
        assertNotEquals(address, lines[1], "address must be EIP-55 checksummed");
        assertEquals("", lines[2]);
        assertEquals("Link this wallet to Discord ID 123456789012345678 for Promofacie role verification.", lines[3]);
        assertEquals("", lines[4]);
        assertEquals("URI: https://associate.promofacie.site", lines[5]);
        assertEquals("Version: 1", lines[6]);
        assertEquals("Chain ID: 4663", lines[7]);
        assertTrue(lines[8].matches("Nonce: [0-9a-f]{32}"), lines[8]);
        assertTrue(lines[9].matches("Issued At: \\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z"), lines[9]);
        assertTrue(lines[10].matches("Expiration Time: \\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z"), lines[10]);
        assertEquals(11, lines.length, "no trailing newline or extra fields");
    }

    @Test
    void testEvmChallengeUsesConfiguredDomain() {
        when(jwtMock.getClaim("discord_id")).thenReturn("123");
        resource.siweDomain = "verify.example.org";
        resource.siweUri = "https://verify.example.org";

        String message = (String) evmChallenge(evmAddress(EVM_KEY)).get("nonce");

        assertTrue(message.startsWith("verify.example.org wants you to sign in"));
        assertTrue(message.contains("\nURI: https://verify.example.org\n"));
    }

    @Test
    void testEvmChallengeRequiresValidAddress() {
        when(jwtMock.getClaim("discord_id")).thenReturn("123");
        assertEquals(400, resource.challenge(new ChallengeRequest("ROBINHOOD", null)).getStatus());
        assertEquals(400, resource.challenge(new ChallengeRequest("ROBINHOOD", "0x1234")).getStatus());
        assertEquals(400, resource.challenge(new ChallengeRequest("ROBINHOOD", "not-an-address")).getStatus());
    }

    @Test
    void testVerifyEvmSiweEndToEnd() {
        when(jwtMock.getClaim("discord_id")).thenReturn("evm-user-1");
        String address = evmAddress(EVM_KEY);
        Map<?, ?> body = evmChallenge(address);

        Response res = resource.verify(new VerifyRequest(null, address,
                personalSign((String) body.get("nonce"), EVM_KEY), null, "ROBINHOOD", (String) body.get("nonceId")));

        assertEquals(200, res.getStatus());
        verify(persistenceServiceMock).persist(address, "evm-user-1", Chain.ROBINHOOD);
        verify(robinhoodEmitterMock).send(any(TrackingCommand.class));
    }

    @Test
    void testVerifyEvmRejectsLegacyChallenge() {
        // An attacker requesting a challenge without a chain must not get an EVM-usable message without domain binding
        when(jwtMock.getClaim("discord_id")).thenReturn("evm-user-1");
        String address = evmAddress(EVM_KEY);
        Map<?, ?> body = (Map<?, ?>) resource.challenge().getEntity();

        Response res = resource.verify(new VerifyRequest(null, address,
                personalSign((String) body.get("nonce"), EVM_KEY), null, "ROBINHOOD", (String) body.get("nonceId")));

        assertEquals(400, res.getStatus());
        verify(persistenceServiceMock, never()).persist(anyString(), anyString(), any());
    }

    @Test
    void testVerifyEvmRejectsChallengeIssuedForAnotherAddress() {
        when(jwtMock.getClaim("discord_id")).thenReturn("evm-user-1");
        Map<?, ?> body = evmChallenge(evmAddress(EVM_KEY));
        String other = evmAddress(OTHER_EVM_KEY);

        Response res = resource.verify(new VerifyRequest(null, other,
                personalSign((String) body.get("nonce"), OTHER_EVM_KEY), null, "ROBINHOOD", (String) body.get("nonceId")));

        assertEquals(400, res.getStatus());
        verify(persistenceServiceMock, never()).persist(anyString(), anyString(), any());
    }

    @Test
    void testVerifyEvmRejectsSignatureFromAnotherKey() {
        when(jwtMock.getClaim("discord_id")).thenReturn("evm-user-1");
        String address = evmAddress(EVM_KEY);
        Map<?, ?> body = evmChallenge(address);

        Response res = resource.verify(new VerifyRequest(null, address,
                personalSign((String) body.get("nonce"), OTHER_EVM_KEY), null, "ROBINHOOD", (String) body.get("nonceId")));

        assertEquals(400, res.getStatus());
        verify(persistenceServiceMock, never()).persist(anyString(), anyString(), any());
    }

    @Test
    void testVerifyEvmChecksummedAddressMatchesLowercaseChallenge() {
        when(jwtMock.getClaim("discord_id")).thenReturn("evm-user-1");
        String address = evmAddress(EVM_KEY);
        Map<?, ?> body = evmChallenge(address);
        String checksummed = Keys.toChecksumAddress(address);

        Response res = resource.verify(new VerifyRequest(null, checksummed,
                personalSign((String) body.get("nonce"), EVM_KEY), null, "ROBINHOOD", (String) body.get("nonceId")));

        assertEquals(200, res.getStatus());
    }

    @Test
    void testNonceIsSingleUse() {
        when(jwtMock.getClaim("discord_id")).thenReturn("evm-user-1");
        String address = evmAddress(EVM_KEY);
        Map<?, ?> body = evmChallenge(address);
        VerifyRequest req = new VerifyRequest(null, address,
                personalSign((String) body.get("nonce"), EVM_KEY), null, "ROBINHOOD", (String) body.get("nonceId"));

        assertEquals(200, resource.verify(req).getStatus());
        assertEquals(400, resource.verify(req).getStatus());
    }

    @Test
    void testOlderPendingChallengeStillVerifies() {
        // Mobile: the page reloads while the user is in the wallet app and requests a second challenge;
        // approving the first (older) request must still work.
        when(jwtMock.getClaim("discord_id")).thenReturn("evm-user-1");
        String address = evmAddress(EVM_KEY);
        Map<?, ?> first = evmChallenge(address);
        evmChallenge(address);

        Response res = resource.verify(new VerifyRequest(null, address,
                personalSign((String) first.get("nonce"), EVM_KEY), null, "ROBINHOOD", (String) first.get("nonceId")));

        assertEquals(200, res.getStatus());
    }

    @Test
    void testVerifyRejectsChallengeIssuedForAnotherChain() {
        when(jwtMock.getClaim("discord_id")).thenReturn("user-1");
        Map<?, ?> body = (Map<?, ?>) resource.challenge(new ChallengeRequest("CARDANO", null)).getEntity();

        Response res = resource.verify(new VerifyRequest("SolAddr", null, "sig", null, "SOLANA", (String) body.get("nonceId")));

        assertEquals(400, res.getStatus());
    }

    // ---- Solana / Sign In With Solana (SIWS) ----

    private static java.security.KeyPair solanaKeyPair() throws Exception {
        return java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    private static String solanaAddress(java.security.KeyPair kp) {
        return dk.panos.promofacie.util.Base58.encode(java.util.Arrays.copyOfRange(kp.getPublic().getEncoded(), 12, 44));
    }

    /** Phantom/Solflare signMessage: raw Ed25519 over the UTF-8 bytes, returned here as Base58. */
    private static String solanaSign(String message, java.security.KeyPair kp) throws Exception {
        java.security.Signature signer = java.security.Signature.getInstance("Ed25519");
        signer.initSign(kp.getPrivate());
        signer.update(message.getBytes(StandardCharsets.UTF_8));
        return dk.panos.promofacie.util.Base58.encode(signer.sign());
    }

    @Test
    void testSolanaChallengeIsSiwsBoundToDomainAndAddress() throws Exception {
        when(jwtMock.getClaim("discord_id")).thenReturn("123456789012345678");
        String address = solanaAddress(solanaKeyPair());

        Map<?, ?> body = (Map<?, ?>) resource.challenge(new ChallengeRequest("SOLANA", address)).getEntity();
        String[] lines = ((String) body.get("nonce")).split("\n", -1);

        assertNotNull(body.get("nonceId"));
        assertEquals("associate.promofacie.site wants you to sign in with your Solana account:", lines[0]);
        assertEquals(address, lines[1]);
        assertEquals("", lines[2]);
        assertEquals("Link this wallet to Discord ID 123456789012345678 for Promofacie role verification.", lines[3]);
        assertEquals("", lines[4]);
        assertEquals("URI: https://associate.promofacie.site", lines[5]);
        assertEquals("Version: 1", lines[6]);
        assertEquals("Chain ID: mainnet", lines[7]);
        assertTrue(lines[8].matches("Nonce: [0-9a-f]{32}"), lines[8]);
        assertTrue(lines[9].startsWith("Issued At: "), lines[9]);
        assertTrue(lines[10].startsWith("Expiration Time: "), lines[10]);
        assertEquals(11, lines.length, "no trailing newline or extra fields");
    }

    @Test
    void testSolanaChallengeRequiresValidAddress() {
        when(jwtMock.getClaim("discord_id")).thenReturn("123");
        assertEquals(400, resource.challenge(new ChallengeRequest("SOLANA", null)).getStatus());
        assertEquals(400, resource.challenge(new ChallengeRequest("SOLANA", "0x7dB967bcF885C8ae4bBFdB5fFff5D9ebF96AA0Ed")).getStatus());
        assertEquals(400, resource.challenge(new ChallengeRequest("SOLANA", "abc")).getStatus());
        // Base58 but not 32 bytes
        assertEquals(400, resource.challenge(new ChallengeRequest("SOLANA",
                dk.panos.promofacie.util.Base58.encode(new byte[20]))).getStatus());
    }

    @Test
    void testVerifySolanaRejectsLegacyChallenge() throws Exception {
        // An attacker requesting a challenge without a chain must not get a Solana-usable message without domain binding
        when(jwtMock.getClaim("discord_id")).thenReturn("solana-user-456");
        java.security.KeyPair kp = solanaKeyPair();
        Map<?, ?> body = (Map<?, ?>) resource.challenge().getEntity();

        Response res = resource.verify(new VerifyRequest(solanaAddress(kp), null,
                solanaSign((String) body.get("nonce"), kp), null, "SOLANA", (String) body.get("nonceId")));

        assertEquals(400, res.getStatus());
        verify(persistenceServiceMock, never()).persist(anyString(), anyString(), any());
    }

    @Test
    void testVerifySolanaRejectsChallengeIssuedForAnotherAddress() throws Exception {
        when(jwtMock.getClaim("discord_id")).thenReturn("solana-user-456");
        java.security.KeyPair issuedFor = solanaKeyPair();
        java.security.KeyPair signer = solanaKeyPair();
        Map<?, ?> body = (Map<?, ?>) resource.challenge(new ChallengeRequest("SOLANA", solanaAddress(issuedFor))).getEntity();

        Response res = resource.verify(new VerifyRequest(solanaAddress(signer), null,
                solanaSign((String) body.get("nonce"), signer), null, "SOLANA", (String) body.get("nonceId")));

        assertEquals(400, res.getStatus());
        verify(persistenceServiceMock, never()).persist(anyString(), anyString(), any());
    }

    @Test
    void testVerifySolanaRejectsSignatureFromAnotherKey() throws Exception {
        when(jwtMock.getClaim("discord_id")).thenReturn("solana-user-456");
        java.security.KeyPair kp = solanaKeyPair();
        String address = solanaAddress(kp);
        Map<?, ?> body = (Map<?, ?>) resource.challenge(new ChallengeRequest("SOLANA", address)).getEntity();

        Response res = resource.verify(new VerifyRequest(address, null,
                solanaSign((String) body.get("nonce"), solanaKeyPair()), null, "SOLANA", (String) body.get("nonceId")));

        assertEquals(400, res.getStatus());
        verify(persistenceServiceMock, never()).persist(anyString(), anyString(), any());
    }

    @Test
    void testVerifyCardanoRejectsSolanaChallenge() {
        when(jwtMock.getClaim("discord_id")).thenReturn("user-1");
        Map<?, ?> body = (Map<?, ?>) resource.challenge(new ChallengeRequest("SOLANA",
                dk.panos.promofacie.util.Base58.encode(new byte[32]))).getEntity();

        Response res = resource.verify(new VerifyRequest("stake1u", null, "84a4", "a401", null, (String) body.get("nonceId")));

        assertEquals(400, res.getStatus());
    }

    @Test
    void testVerifyWalletLinkedToAnotherAccountReturns409() {
        when(jwtMock.getClaim("discord_id")).thenReturn("attacker-1");
        String address = evmAddress(EVM_KEY);
        Map<?, ?> body = evmChallenge(address);
        doThrow(new WalletAlreadyLinkedException(address))
                .when(persistenceServiceMock).persist(address, "attacker-1", Chain.ROBINHOOD);

        Response res = resource.verify(new VerifyRequest(null, address,
                personalSign((String) body.get("nonce"), EVM_KEY), null, "ROBINHOOD", (String) body.get("nonceId")));

        assertEquals(409, res.getStatus());
        String message = (String) ((Map<?, ?>) res.getEntity()).get("message");
        assertTrue(message.contains("already linked to another Discord account"));
        verify(robinhoodEmitterMock, never()).send(any(TrackingCommand.class));
        verify(roleEvaluationServiceMock, never()).enqueueUserEvaluationForAllGuilds(anyString());
    }

    @Test
    void testChallenge() {
        when(jwtMock.getClaim("discord_id")).thenReturn("user-discord-123");

        Response response = resource.challenge();

        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        Map<?, ?> entity = (Map<?, ?>) response.getEntity();
        assertNotNull(entity.get("nonce"));
        String challenge = (String) entity.get("nonce");
        assertTrue(challenge.contains("Promofacie Wallet Verification"));
        assertTrue(challenge.contains("Discord ID: user-discord-123"));
    }

    @Test
    void testChallengeIsLedgerSafeAscii() {
        // Ledger Cardano app (str_isUnambiguousAscii) only accepts printable ASCII 32..126,
        // no leading/trailing spaces and no double spaces; ~160 bytes are displayed in full.
        when(jwtMock.getClaim("discord_id")).thenReturn("123456789012345678");

        String challenge = (String) ((Map<?, ?>) resource.challenge().getEntity()).get("nonce");
        byte[] bytes = challenge.getBytes(java.nio.charset.StandardCharsets.UTF_8);

        for (byte b : bytes) {
            assertTrue(b >= 32 && b <= 126, "non-printable byte in challenge: " + b);
        }
        assertFalse(challenge.startsWith(" "));
        assertFalse(challenge.endsWith(" "));
        assertFalse(challenge.contains("  "));
        assertTrue(bytes.length <= 160, "challenge too long for Ledger display: " + bytes.length);
    }

    @Test
    void testCip8PayloadMatchesRawNonce() {
        String nonce = "Promofacie Wallet Verification | Nonce: abc";
        byte[] raw = nonce.getBytes(java.nio.charset.StandardCharsets.UTF_8);

        assertTrue(WalletVerificationResource.cip8PayloadMatchesNonce(false, raw, nonce));
        assertFalse(WalletVerificationResource.cip8PayloadMatchesNonce(false, raw, nonce + "x"));
        assertFalse(WalletVerificationResource.cip8PayloadMatchesNonce(false, null, nonce));
    }

    @Test
    void testCip8PayloadMatchesHashedNonce() {
        String nonce = "Promofacie Wallet Verification | Nonce: abc";
        byte[] hashed = WalletVerificationResource.blake2b224(nonce.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] raw = nonce.getBytes(java.nio.charset.StandardCharsets.UTF_8);

        assertTrue(WalletVerificationResource.cip8PayloadMatchesNonce(true, hashed, nonce));
        // A hashed signature must not be accepted as raw and vice versa
        assertFalse(WalletVerificationResource.cip8PayloadMatchesNonce(false, hashed, nonce));
        assertFalse(WalletVerificationResource.cip8PayloadMatchesNonce(true, raw, nonce));
    }

    /** Builds a real CIP-30 DataSignature (COSE_Sign1 + COSE_Key) like a wallet would, signed with the stake key. */
    private static String[] signCip30(java.security.KeyPair kp, String message, boolean hashed) throws Exception {
        byte[] rawPubKey = java.util.Arrays.copyOfRange(kp.getPublic().getEncoded(), 12, 44);
        byte[] rewardAddress = new byte[29];
        rewardAddress[0] = (byte) 0xe1; // mainnet reward address, key-hash credential
        System.arraycopy(WalletVerificationResource.blake2b224(rawPubKey), 0, rewardAddress, 1, 28);

        var protectedHeader = new shaded.co.nstant.in.cbor.model.Map();
        protectedHeader.put(new shaded.co.nstant.in.cbor.model.UnsignedInteger(1), new shaded.co.nstant.in.cbor.model.NegativeInteger(-8));
        protectedHeader.put(new shaded.co.nstant.in.cbor.model.UnicodeString("address"), new shaded.co.nstant.in.cbor.model.ByteString(rewardAddress));
        byte[] protectedBytes = cbor(protectedHeader);

        byte[] messageBytes = message.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] payload = hashed ? WalletVerificationResource.blake2b224(messageBytes) : messageBytes;

        var sigStructure = new shaded.co.nstant.in.cbor.model.Array();
        sigStructure.add(new shaded.co.nstant.in.cbor.model.UnicodeString("Signature1"));
        sigStructure.add(new shaded.co.nstant.in.cbor.model.ByteString(protectedBytes));
        sigStructure.add(new shaded.co.nstant.in.cbor.model.ByteString(new byte[0]));
        sigStructure.add(new shaded.co.nstant.in.cbor.model.ByteString(payload));
        java.security.Signature signer = java.security.Signature.getInstance("Ed25519");
        signer.initSign(kp.getPrivate());
        signer.update(cbor(sigStructure));
        byte[] signature = signer.sign();

        var unprotectedHeader = new shaded.co.nstant.in.cbor.model.Map();
        unprotectedHeader.put(new shaded.co.nstant.in.cbor.model.UnicodeString("hashed"),
                hashed ? shaded.co.nstant.in.cbor.model.SimpleValue.TRUE : shaded.co.nstant.in.cbor.model.SimpleValue.FALSE);

        var coseSign1 = new shaded.co.nstant.in.cbor.model.Array();
        coseSign1.add(new shaded.co.nstant.in.cbor.model.ByteString(protectedBytes));
        coseSign1.add(unprotectedHeader);
        coseSign1.add(new shaded.co.nstant.in.cbor.model.ByteString(payload));
        coseSign1.add(new shaded.co.nstant.in.cbor.model.ByteString(signature));

        var coseKey = new shaded.co.nstant.in.cbor.model.Map();
        coseKey.put(new shaded.co.nstant.in.cbor.model.UnsignedInteger(1), new shaded.co.nstant.in.cbor.model.UnsignedInteger(1));
        coseKey.put(new shaded.co.nstant.in.cbor.model.UnsignedInteger(3), new shaded.co.nstant.in.cbor.model.NegativeInteger(-8));
        coseKey.put(new shaded.co.nstant.in.cbor.model.NegativeInteger(-1), new shaded.co.nstant.in.cbor.model.UnsignedInteger(6));
        coseKey.put(new shaded.co.nstant.in.cbor.model.NegativeInteger(-2), new shaded.co.nstant.in.cbor.model.ByteString(rawPubKey));

        java.util.HexFormat hex = java.util.HexFormat.of();
        return new String[]{hex.formatHex(cbor(coseSign1)), hex.formatHex(cbor(coseKey))};
    }

    private static byte[] cbor(shaded.co.nstant.in.cbor.model.DataItem item) throws Exception {
        var out = new java.io.ByteArrayOutputStream();
        new shaded.co.nstant.in.cbor.CborEncoder(out).encode(item);
        return out.toByteArray();
    }

    private Response verifyCardano(boolean hashed, boolean tamper) throws Exception {
        java.security.KeyPair kp = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        when(jwtMock.getClaim("discord_id")).thenReturn("cardano-user-789");

        String nonce = (String) ((Map<?, ?>) resource.challenge().getEntity()).get("nonce");
        String[] dataSignature = signCip30(kp, tamper ? nonce + "x" : nonce, hashed);
        String stakeAddress = new org.cardanofoundation.cip30.CIP30Verifier(dataSignature[0], dataSignature[1])
                .verify().getAddress(org.cardanofoundation.cip30.AddressFormat.TEXT).orElseThrow();
        assertTrue(stakeAddress.startsWith("stake1"));

        return resource.verify(new dk.panos.promofacie.controller.model.VerifyRequest(
                stakeAddress, null, dataSignature[0], dataSignature[1], null));
    }

    @Test
    void testVerifyCardanoRawPayloadEndToEnd() throws Exception {
        assertEquals(Response.Status.OK.getStatusCode(), verifyCardano(false, false).getStatus());
        verify(persistenceServiceMock, times(1)).persist(anyString(), eq("cardano-user-789"), eq(Chain.CARDANO));
    }

    @Test
    void testVerifyCardanoHashedPayloadEndToEnd() throws Exception {
        assertEquals(Response.Status.OK.getStatusCode(), verifyCardano(true, false).getStatus());
    }

    @Test
    void testVerifyCardanoWrongMessageRejected() throws Exception {
        assertEquals(400, verifyCardano(false, true).getStatus());
        assertEquals(400, verifyCardano(true, true).getStatus());
        verify(persistenceServiceMock, never()).persist(anyString(), anyString(), any());
    }

    @Test
    void testBlake2b224KnownVector() {
        // Blake2b-224 of the empty string
        assertEquals("836cc68931c2e4e3e838602eca1902591d216837bafddfe6f0c8cb07",
                java.util.HexFormat.of().formatHex(WalletVerificationResource.blake2b224(new byte[0])));
    }

    @Test
    void testChallengeMissingDiscordId() {
        when(jwtMock.getClaim("discord_id")).thenReturn(null);

        Response response = resource.challenge();

        assertEquals(Response.Status.UNAUTHORIZED.getStatusCode(), response.getStatus());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testGetAssociationSuccess() {
        when(jwtMock.getClaim("discord_id")).thenReturn("user-discord-123");

        Wallet wallet1 = new Wallet();
        wallet1.id = 1L;
        wallet1.setAddress("stake1address1");
        wallet1.setDiscordId("user-discord-123");
        wallet1.setChain(Chain.CARDANO);

        when(persistenceServiceMock.findByDiscordId("user-discord-123")).thenReturn(List.of(wallet1));

        Response response = resource.getAssociation();

        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        List<WalletAssociationResponse> list = (List<WalletAssociationResponse>) response.getEntity();
        assertEquals(1, list.size());
        assertEquals("stake1address1", list.get(0).stakeAddress());
        assertEquals("user-discord-123", list.get(0).discordId());
    }

    @Test
    void testGetAssociationMissingDiscordId() {
        when(jwtMock.getClaim("discord_id")).thenReturn(null);

        Response response = resource.getAssociation();

        assertEquals(Response.Status.UNAUTHORIZED.getStatusCode(), response.getStatus());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testDeleteAssociationSpecificAddressSuccess() {
        when(jwtMock.getClaim("discord_id")).thenReturn("user-discord-123");

        Wallet wallet1 = new Wallet();
        wallet1.id = 1L;
        wallet1.setAddress("stake1address1");
        wallet1.setDiscordId("user-discord-123");
        wallet1.setChain(Chain.CARDANO);

        when(persistenceServiceMock.findByAddressAndDiscordId("stake1address1", "user-discord-123")).thenReturn(wallet1);
        when(persistenceServiceMock.deleteByAddressAndDiscordId("stake1address1", "user-discord-123")).thenReturn(true);

        Response response = resource.deleteAssociation("stake1address1");

        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());

        ArgumentCaptor<TrackingCommand> captor = ArgumentCaptor.forClass(TrackingCommand.class);
        verify(emitterMock, times(1)).send(captor.capture());
        assertEquals(TrackingCommand.Action.REMOVE_ADDRESS, captor.getValue().action());
        assertEquals("stake1address1", captor.getValue().stakeAddress());

        verify(persistenceServiceMock, times(1)).deleteByAddressAndDiscordId("stake1address1", "user-discord-123");
        verify(roleEvaluationServiceMock, times(1)).enqueueUserEvaluationForAllGuilds("user-discord-123");
    }

    @Test
    @SuppressWarnings("unchecked")
    void testDeleteAssociationAllWallets() {
        when(jwtMock.getClaim("discord_id")).thenReturn("user-discord-123");

        Wallet wallet1 = new Wallet();
        wallet1.id = 1L;
        wallet1.setAddress("stake1address1");
        wallet1.setDiscordId("user-discord-123");
        wallet1.setChain(Chain.CARDANO);

        when(persistenceServiceMock.findByDiscordId("user-discord-123")).thenReturn(List.of(wallet1));
        when(persistenceServiceMock.deleteAllByDiscordId("user-discord-123")).thenReturn(1L);

        Response response = resource.deleteAssociation(null);

        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());

        ArgumentCaptor<TrackingCommand> captor = ArgumentCaptor.forClass(TrackingCommand.class);
        verify(emitterMock, times(1)).send(captor.capture());
        assertEquals(TrackingCommand.Action.REMOVE_ADDRESS, captor.getValue().action());
        assertEquals("stake1address1", captor.getValue().stakeAddress());

        verify(persistenceServiceMock, times(1)).deleteAllByDiscordId("user-discord-123");
        verify(roleEvaluationServiceMock, times(1)).enqueueUserEvaluationForAllGuilds("user-discord-123");
    }

    @Test
    void testVerifySolanaWalletSuccess() throws Exception {
        // Generate valid Ed25519 keypair and signature
        java.security.KeyPairGenerator kpg = java.security.KeyPairGenerator.getInstance("Ed25519");
        java.security.KeyPair kp = kpg.generateKeyPair();

        when(jwtMock.getClaim("discord_id")).thenReturn("solana-user-456");
        byte[] rawPubKey = java.util.Arrays.copyOfRange(kp.getPublic().getEncoded(), 12, 44);
        String solAddress = dk.panos.promofacie.util.Base58.encode(rawPubKey);

        // Request the SIWS challenge for this address
        Response challengeRes = resource.challenge(new ChallengeRequest("SOLANA", solAddress));
        Map<?, ?> body = (Map<?, ?>) challengeRes.getEntity();
        String nonce = (String) body.get("nonce");

        // Sign challenge nonce
        java.security.Signature signer = java.security.Signature.getInstance("Ed25519");
        signer.initSign(kp.getPrivate());
        signer.update(nonce.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] sig = signer.sign();

        String sigBase58 = dk.panos.promofacie.util.Base58.encode(sig);

        dk.panos.promofacie.controller.model.VerifyRequest verifyReq =
                new dk.panos.promofacie.controller.model.VerifyRequest(solAddress, null, sigBase58, null, "SOLANA",
                        (String) body.get("nonceId"));

        Response verifyRes = resource.verify(verifyReq);
        assertEquals(Response.Status.OK.getStatusCode(), verifyRes.getStatus());

        verify(persistenceServiceMock, times(1)).persist(solAddress, "solana-user-456", Chain.SOLANA);
        verify(roleEvaluationServiceMock, times(1)).enqueueUserEvaluationForAllGuilds("solana-user-456");

        ArgumentCaptor<TrackingCommand> captor = ArgumentCaptor.forClass(TrackingCommand.class);
        verify(solanaEmitterMock, times(1)).send(captor.capture());
        assertEquals(TrackingCommand.Action.ADD_ADDRESS, captor.getValue().action());
        assertEquals(solAddress, captor.getValue().stakeAddress());
    }

    @Test
    void testDeleteAssociationSolanaAddress() {
        when(jwtMock.getClaim("discord_id")).thenReturn("solana-user-456");

        Wallet solWallet = new Wallet();
        solWallet.id = 99L;
        solWallet.setAddress("SolanaAddr1111111111111111111111111111111111");
        solWallet.setDiscordId("solana-user-456");
        solWallet.setChain(Chain.SOLANA);

        when(persistenceServiceMock.findByAddressAndDiscordId("SolanaAddr1111111111111111111111111111111111", "solana-user-456"))
                .thenReturn(solWallet);
        when(persistenceServiceMock.deleteByAddressAndDiscordId("SolanaAddr1111111111111111111111111111111111", "solana-user-456"))
                .thenReturn(true);

        Response response = resource.deleteAssociation("SolanaAddr1111111111111111111111111111111111");
        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());

        ArgumentCaptor<TrackingCommand> captor = ArgumentCaptor.forClass(TrackingCommand.class);
        verify(solanaEmitterMock, times(1)).send(captor.capture());
        assertEquals(TrackingCommand.Action.REMOVE_ADDRESS, captor.getValue().action());
        assertEquals("SolanaAddr1111111111111111111111111111111111", captor.getValue().stakeAddress());
    }
}
