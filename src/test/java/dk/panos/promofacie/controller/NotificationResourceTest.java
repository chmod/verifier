package dk.panos.promofacie.controller;

import dk.panos.promofacie.db.Notification;
import dk.panos.promofacie.db.NotificationChannel;
import dk.panos.promofacie.kafka.model.TrackingCommand;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NotificationResourceTest {

    @Test
    @SuppressWarnings("unchecked")
    void testCreateNotification() {
        Emitter<TrackingCommand> emitter = mock(Emitter.class);
        when(emitter.send(any(TrackingCommand.class))).thenReturn(CompletableFuture.completedFuture(null));

        NotificationResource resource = spy(new NotificationResource(emitter, mock(Emitter.class), mock(Emitter.class)));
        doNothing().when(resource).persistNotification(any(Notification.class));

        Notification notification = new Notification();
        notification.policyId = "policy-mint-1";

        Response response = resource.create(notification).await().indefinitely();

        assertEquals(Response.Status.CREATED.getStatusCode(), response.getStatus());
        assertEquals(notification, response.getEntity());

        verify(resource, times(1)).persistNotification(notification);

        ArgumentCaptor<TrackingCommand> captor = ArgumentCaptor.forClass(TrackingCommand.class);
        verify(emitter, times(1)).send(captor.capture());
        TrackingCommand sentCommand = captor.getValue();
        assertEquals(TrackingCommand.Action.ADD_MINT_POLICY, sentCommand.action());
        assertEquals("policy-mint-1", sentCommand.policyId());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testDeleteNotification() {
        Emitter<TrackingCommand> emitter = mock(Emitter.class);
        when(emitter.send(any(TrackingCommand.class))).thenReturn(CompletableFuture.completedFuture(null));

        NotificationResource resource = spy(new NotificationResource(emitter, mock(Emitter.class), mock(Emitter.class)));
        Notification notification = new Notification();
        notification.id = 42L;
        notification.policyId = "policy-mint-2";

        doReturn(notification).when(resource).findNotificationById(42L);
        doNothing().when(resource).deleteNotification(notification);

        Response response = resource.delete(42L).await().indefinitely();

        assertEquals(Response.Status.NO_CONTENT.getStatusCode(), response.getStatus());

        verify(resource, times(1)).findNotificationById(42L);
        verify(resource, times(1)).deleteNotification(notification);

        ArgumentCaptor<TrackingCommand> captor = ArgumentCaptor.forClass(TrackingCommand.class);
        verify(emitter, times(1)).send(captor.capture());
        TrackingCommand sentCommand = captor.getValue();
        assertEquals(TrackingCommand.Action.REMOVE_MINT_POLICY, sentCommand.action());
        assertEquals("policy-mint-2", sentCommand.policyId());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testDeleteNotificationNotFound() {
        Emitter<TrackingCommand> emitter = mock(Emitter.class);
        NotificationResource resource = spy(new NotificationResource(emitter, mock(Emitter.class), mock(Emitter.class)));

        doReturn(null).when(resource).findNotificationById(99L);

        Response response = resource.delete(99L).await().indefinitely();

        assertEquals(Response.Status.NOT_FOUND.getStatusCode(), response.getStatus());
        verify(emitter, never()).send(any(TrackingCommand.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testGetByPolicyId() {
        Emitter<TrackingCommand> emitter = mock(Emitter.class);
        NotificationResource resource = spy(new NotificationResource(emitter, mock(Emitter.class), mock(Emitter.class)));

        Notification notification = new Notification();
        notification.policyId = "policy-mint-3";

        doReturn(notification).when(resource).findNotificationByPolicyId("policy-mint-3");

        Response response = resource.getByPolicyId("policy-mint-3").await().indefinitely();

        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        assertEquals(notification, response.getEntity());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testGetByPolicyIdNotFound() {
        Emitter<TrackingCommand> emitter = mock(Emitter.class);
        NotificationResource resource = spy(new NotificationResource(emitter, mock(Emitter.class), mock(Emitter.class)));

        doReturn(null).when(resource).findNotificationByPolicyId("none");

        Response response = resource.getByPolicyId("none").await().indefinitely();

        assertEquals(Response.Status.NOT_FOUND.getStatusCode(), response.getStatus());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testMintPolicyCommandsRoutedByChain() {
        Emitter<TrackingCommand> cardano = mock(Emitter.class);
        Emitter<TrackingCommand> robinhood = mock(Emitter.class);
        Emitter<TrackingCommand> solana = mock(Emitter.class);
        NotificationResource resource = new NotificationResource(cardano, robinhood, solana);

        assertSame(robinhood, resource.emitterFor("0x164e1c87a56f4971df2b6371cac43324382a33e6"));
        assertSame(solana, resource.emitterFor("DezXAZ8z7PnrnRJjz3wXBoRgixCa6xjnB7YaB1pPB263"));
        assertSame(cardano, resource.emitterFor("6c79a125d11bdf0cd0323dde9ce28d5ea201a259159205d7539c41d4"));
        assertSame(cardano, resource.emitterFor("policy-mint-1"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testCreateNormalizesChecksummedEvmAddress() {
        Emitter<TrackingCommand> robinhood = mock(Emitter.class);
        when(robinhood.send(any(TrackingCommand.class))).thenReturn(CompletableFuture.completedFuture(null));
        NotificationResource resource = spy(new NotificationResource(mock(Emitter.class), robinhood, mock(Emitter.class)));
        doNothing().when(resource).persistNotification(any(Notification.class));

        Notification notification = new Notification();
        notification.policyId = " 0x164E1C87A56f4971df2b6371cac43324382A33e6 ";
        resource.create(notification).await().indefinitely();

        assertEquals("0x164e1c87a56f4971df2b6371cac43324382a33e6", notification.policyId);
        ArgumentCaptor<TrackingCommand> captor = ArgumentCaptor.forClass(TrackingCommand.class);
        verify(robinhood, times(1)).send(captor.capture());
        assertEquals("0x164e1c87a56f4971df2b6371cac43324382a33e6", captor.getValue().policyId());
    }

    // --- POST/DELETE /notifications/policy/{policyId}/channels ---

    private static final String EVM = "0x164e1c87a56f4971df2b6371cac43324382a33e6";

    @SuppressWarnings("unchecked")
    private NotificationResource channelResource(Emitter<TrackingCommand> robinhood, Notification existing) {
        when(robinhood.send(any(TrackingCommand.class))).thenReturn(CompletableFuture.completedFuture(null));
        NotificationResource resource = spy(new NotificationResource(mock(Emitter.class), robinhood, mock(Emitter.class)));
        doAnswer(inv -> ((java.util.function.Supplier<?>) inv.getArgument(0)).get()).when(resource).inTransaction(any());
        doNothing().when(resource).lockPolicy(anyString());
        doReturn(existing).when(resource).findNotificationForPolicy(anyString());
        doNothing().when(resource).persistNotification(any(Notification.class));
        doNothing().when(resource).deleteNotification(any(Notification.class));
        return resource;
    }

    private static NotificationChannel channel(String guildId, String channelId) {
        NotificationChannel c = new NotificationChannel();
        c.type = NotificationChannel.ChannelType.DISCORD;
        c.guildId = guildId;
        c.channelId = channelId;
        return c;
    }

    private static Notification notificationWith(NotificationChannel... channels) {
        Notification n = new Notification();
        n.policyId = EVM;
        for (NotificationChannel c : channels) {
            c.notification = n;
            n.channels.add(c);
        }
        return n;
    }

    @Test
    @SuppressWarnings("unchecked")
    void testUpsertChannelCreatesNotificationForNewPolicy() {
        Emitter<TrackingCommand> robinhood = mock(Emitter.class);
        NotificationResource resource = channelResource(robinhood, null);

        Response response = resource.upsertChannel("0x164E1C87A56f4971df2b6371cac43324382A33e6", channel("g1", "c1")).await().indefinitely();

        assertEquals(201, response.getStatus());
        Notification saved = (Notification) response.getEntity();
        assertEquals(EVM, saved.policyId);
        assertEquals(1, saved.channels.size());
        verify(resource).persistNotification(saved);
        ArgumentCaptor<TrackingCommand> captor = ArgumentCaptor.forClass(TrackingCommand.class);
        verify(robinhood).send(captor.capture());
        assertEquals(TrackingCommand.Action.ADD_MINT_POLICY, captor.getValue().action());
        assertEquals(EVM, captor.getValue().policyId());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testUpsertChannelAddsSecondGuildWithoutCommand() {
        Emitter<TrackingCommand> robinhood = mock(Emitter.class);
        Notification existing = notificationWith(channel("g1", "c1"));
        NotificationResource resource = channelResource(robinhood, existing);

        Response response = resource.upsertChannel(EVM, channel("g2", "c2")).await().indefinitely();

        assertEquals(200, response.getStatus());
        assertEquals(2, existing.channels.size());
        verify(resource, never()).persistNotification(any());
        verify(robinhood, never()).send(any(TrackingCommand.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testUpsertChannelReplacesSameGuildChannel() {
        Emitter<TrackingCommand> robinhood = mock(Emitter.class);
        Notification existing = notificationWith(channel("g1", "old"), channel("g2", "c2"), channel("g1", "dup"));
        NotificationResource resource = channelResource(robinhood, existing);

        resource.upsertChannel(EVM, channel("g1", "new")).await().indefinitely();

        assertEquals(2, existing.channels.size());
        assertEquals("new", existing.channels.stream().filter(c -> c.guildId.equals("g1")).findFirst().orElseThrow().channelId);
        assertEquals("c2", existing.channels.stream().filter(c -> c.guildId.equals("g2")).findFirst().orElseThrow().channelId);
    }

    @Test
    @SuppressWarnings("unchecked")
    void testUpsertChannelRejectsMissingFields() {
        NotificationResource resource = channelResource(mock(Emitter.class), null);
        assertEquals(400, resource.upsertChannel(EVM, channel("g1", null)).await().indefinitely().getStatus());
        assertEquals(400, resource.upsertChannel(EVM, null).await().indefinitely().getStatus());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testDeleteChannelKeepsNotificationWhileOtherGuildsRemain() {
        Emitter<TrackingCommand> robinhood = mock(Emitter.class);
        Notification existing = notificationWith(channel("g1", "c1"), channel("g2", "c2"));
        NotificationResource resource = channelResource(robinhood, existing);

        Response response = resource.deleteChannel(EVM, "g1").await().indefinitely();

        assertEquals(204, response.getStatus());
        assertEquals(1, existing.channels.size());
        assertEquals("g2", existing.channels.get(0).guildId);
        verify(resource, never()).deleteNotification(any());
        verify(robinhood, never()).send(any(TrackingCommand.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testDeleteLastChannelDeletesNotificationAndSendsRemove() {
        Emitter<TrackingCommand> robinhood = mock(Emitter.class);
        Notification existing = notificationWith(channel("g1", "c1"));
        NotificationResource resource = channelResource(robinhood, existing);

        Response response = resource.deleteChannel(EVM, "g1").await().indefinitely();

        assertEquals(204, response.getStatus());
        verify(resource).deleteNotification(existing);
        ArgumentCaptor<TrackingCommand> captor = ArgumentCaptor.forClass(TrackingCommand.class);
        verify(robinhood).send(captor.capture());
        assertEquals(TrackingCommand.Action.REMOVE_MINT_POLICY, captor.getValue().action());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testDeleteChannelIsIdempotentAndRequiresGuild() {
        Emitter<TrackingCommand> robinhood = mock(Emitter.class);
        NotificationResource resource = channelResource(robinhood, null);

        assertEquals(204, resource.deleteChannel(EVM, "g1").await().indefinitely().getStatus());
        assertEquals(400, resource.deleteChannel(EVM, null).await().indefinitely().getStatus());
        verify(robinhood, never()).send(any(TrackingCommand.class));
    }
}
