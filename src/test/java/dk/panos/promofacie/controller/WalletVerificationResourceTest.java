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
    private WalletPersistenceService persistenceServiceMock;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        resource = new WalletVerificationResource();
        jwtMock = mock(JsonWebToken.class);
        emitterMock = mock(Emitter.class);
        persistenceServiceMock = mock(WalletPersistenceService.class);

        resource.jwt = jwtMock;
        resource.walletTrackingEmitter = emitterMock;
        resource.walletPersistenceService = persistenceServiceMock;

        when(emitterMock.send(any(TrackingCommand.class))).thenReturn(CompletableFuture.completedFuture(null));
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
}
