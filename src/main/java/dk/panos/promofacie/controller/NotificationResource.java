package dk.panos.promofacie.controller;

import dk.panos.promofacie.db.Notification;
import dk.panos.promofacie.db.NotificationChannel;
import dk.panos.promofacie.kafka.model.TrackingCommand;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import java.util.List;

@Path("/notifications")
@ApplicationScoped
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class NotificationResource {

    private final Emitter<TrackingCommand> trackingEmitter;

    @Inject
    public NotificationResource(@Channel("wallet-tracking-out") Emitter<TrackingCommand> trackingEmitter) {
        this.trackingEmitter = trackingEmitter;
    }

    @POST
    @Transactional
    public Uni<Response> create(Notification notification) {
        return Uni.createFrom().item(() -> {
            if (notification.channels != null) {
                for (NotificationChannel channel : notification.channels) {
                    channel.notification = notification;
                }
            }
            persistNotification(notification);
            trackingEmitter.send(new TrackingCommand(TrackingCommand.Action.ADD_MINT_POLICY, null, notification.policyId));
            return Response.status(Response.Status.CREATED).entity(notification).build();
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    @GET
    public Uni<List<Notification>> listAll() {
        return Uni.createFrom().item(this::getAllNotifications)
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    @GET
    @Path("/{id}")
    public Uni<Response> getById(@PathParam("id") Long id) {
        return Uni.createFrom().item(() -> {
            Notification notification = findNotificationById(id);
            if (notification == null) {
                return Response.status(Response.Status.NOT_FOUND).build();
            }
            return Response.ok(notification).build();
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    @DELETE
    @Path("/{id}")
    @Transactional
    public Uni<Response> delete(@PathParam("id") Long id) {
        return Uni.createFrom().item(() -> {
            Notification notification = findNotificationById(id);
            if (notification == null) {
                return Response.status(Response.Status.NOT_FOUND).build();
            }
            String policyId = notification.policyId;
            deleteNotification(notification);
            trackingEmitter.send(new TrackingCommand(TrackingCommand.Action.REMOVE_MINT_POLICY, null, policyId));
            return Response.noContent().build();
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    @GET
    @Path("/policy/{policyId}")
    public Uni<Response> getByPolicyId(@PathParam("policyId") String policyId) {
        return Uni.createFrom().item(() -> {
            Notification notification = findNotificationByPolicyId(policyId);
            if (notification == null) {
                return Response.status(Response.Status.NOT_FOUND).build();
            }
            return Response.ok(notification).build();
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    // --- Helper methods to allow mocking static Panache calls in JUnit ---

    void persistNotification(Notification notification) {
        notification.persist();
    }

    @SuppressWarnings("unchecked")
    List<Notification> getAllNotifications() {
        List<?> list = Notification.listAll();
        return (List<Notification>) list;
    }

    Notification findNotificationById(Long id) {
        return Notification.findById(id);
    }

    void deleteNotification(Notification notification) {
        notification.delete();
    }

    Notification findNotificationByPolicyId(String policyId) {
        return Notification.find("policyId", policyId).firstResult();
    }
}
