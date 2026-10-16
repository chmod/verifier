package dk.panos.promofacie.controller;

import dk.panos.promofacie.controller.model.WalletAssociationResponse;
import dk.panos.promofacie.db.Chain;
import dk.panos.promofacie.db.Wallet;
import dk.panos.promofacie.db.WalletPersistenceService;
import dk.panos.promofacie.kafka.model.TrackingCommand;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WalletVerificationResourceTest {

    private WalletVerificationResource resource;
    private JsonWebToken jwtMock;
    private Emitter<TrackingCommand> emitterMock;
    private Emitter<TrackingCommand> solanaEmitterMock;
    private WalletPersistenceService persistenceServiceMock;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        resource = new WalletVerificationResource();
        jwtMock = mock(JsonWebToken.class);
        emitterMock = mock(Emitter.class);
        solanaEmitterMock = mock(Emitter.class);
        persistenceServiceMock = mock(WalletPersistenceService.class);

        resource.jwt = jwtMock;
        resource.walletTrackingEmitter = emitterMock;
        resource.solanaTrackingEmitter = solanaEmitterMock;
        resource.walletPersistenceService = persistenceServiceMock;

        when(emitterMock.send(any(TrackingCommand.class))).thenReturn(CompletableFuture.completedFuture(null));
        when(solanaEmitterMock.send(any(TrackingCommand.class))).thenReturn(CompletableFuture.completedFuture(null));
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
    }

    @Test
    void testVerifySolanaWalletSuccess() throws Exception {
        // Generate valid Ed25519 keypair and signature
        java.security.KeyPairGenerator kpg = java.security.KeyPairGenerator.getInstance("Ed25519");
        java.security.KeyPair kp = kpg.generateKeyPair();

        when(jwtMock.getClaim("discord_id")).thenReturn("solana-user-456");

        // Request challenge
        Response challengeRes = resource.challenge();
        Map<?, ?> body = (Map<?, ?>) challengeRes.getEntity();
        String nonce = (String) body.get("nonce");

        // Sign challenge nonce
        java.security.Signature signer = java.security.Signature.getInstance("Ed25519");
        signer.initSign(kp.getPrivate());
        signer.update(nonce.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] sig = signer.sign();

        byte[] rawPubKey = java.util.Arrays.copyOfRange(kp.getPublic().getEncoded(), 12, 44);
        String solAddress = dk.panos.promofacie.util.Base58.encode(rawPubKey);
        String sigBase58 = dk.panos.promofacie.util.Base58.encode(sig);

        dk.panos.promofacie.controller.model.VerifyRequest verifyReq =
                new dk.panos.promofacie.controller.model.VerifyRequest(solAddress, null, sigBase58, null, "SOLANA");

        Response verifyRes = resource.verify(verifyReq);
        assertEquals(Response.Status.OK.getStatusCode(), verifyRes.getStatus());

        verify(persistenceServiceMock, times(1)).persist(solAddress, "solana-user-456", Chain.SOLANA);

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
