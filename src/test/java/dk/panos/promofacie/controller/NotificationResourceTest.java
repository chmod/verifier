package dk.panos.promofacie.controller;

import dk.panos.promofacie.db.Notification;
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

        NotificationResource resource = spy(new NotificationResource(emitter));
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

        NotificationResource resource = spy(new NotificationResource(emitter));
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
        NotificationResource resource = spy(new NotificationResource(emitter));

        doReturn(null).when(resource).findNotificationById(99L);

        Response response = resource.delete(99L).await().indefinitely();

        assertEquals(Response.Status.NOT_FOUND.getStatusCode(), response.getStatus());
        verify(emitter, never()).send(any(TrackingCommand.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testGetByPolicyId() {
        Emitter<TrackingCommand> emitter = mock(Emitter.class);
        NotificationResource resource = spy(new NotificationResource(emitter));

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
        NotificationResource resource = spy(new NotificationResource(emitter));

        doReturn(null).when(resource).findNotificationByPolicyId("none");

        Response response = resource.getByPolicyId("none").await().indefinitely();

        assertEquals(Response.Status.NOT_FOUND.getStatusCode(), response.getStatus());
    }
}
