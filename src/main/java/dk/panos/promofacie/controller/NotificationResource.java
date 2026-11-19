package dk.panos.promofacie.controller;

import dk.panos.promofacie.db.Notification;
import dk.panos.promofacie.db.NotificationChannel;
import dk.panos.promofacie.kafka.model.TrackingCommand;
import dk.panos.promofacie.controller.model.RuleRequest;
import io.quarkus.narayana.jta.QuarkusTransaction;
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
import java.util.Objects;
import java.util.function.Supplier;

@Path("/notifications")
@ApplicationScoped
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class NotificationResource {

    private static final java.util.regex.Pattern EVM_ADDRESS = java.util.regex.Pattern.compile("^(0x)?[0-9a-fA-F]{40}$");
    private static final java.util.regex.Pattern SOLANA_ADDRESS = java.util.regex.Pattern.compile("^[1-9A-HJ-NP-Za-km-z]{32,44}$");

    private final Emitter<TrackingCommand> trackingEmitter;
    private final Emitter<TrackingCommand> robinhoodTrackingEmitter;
    private final Emitter<TrackingCommand> solanaTrackingEmitter;

    @Inject
    public NotificationResource(@Channel("wallet-tracking-out") Emitter<TrackingCommand> trackingEmitter,
                                @Channel("robinhood-tracking-out") Emitter<TrackingCommand> robinhoodTrackingEmitter,
                                @Channel("solana-tracking-out") Emitter<TrackingCommand> solanaTrackingEmitter) {
        this.trackingEmitter = trackingEmitter;
        this.robinhoodTrackingEmitter = robinhoodTrackingEmitter;
        this.solanaTrackingEmitter = solanaTrackingEmitter;
    }

    /**
     * Notifications have no chain column, so route mint-policy commands by policy id format:
     * EVM contract -> Robinhood indexer, base58 mint -> Solana indexer, anything else (56-hex policy id) -> Cardano.
     */
    Emitter<TrackingCommand> emitterFor(String policyId) {
        String id = policyId == null ? "" : policyId.trim();
        if (EVM_ADDRESS.matcher(id).matches()) {
            return robinhoodTrackingEmitter;
        }
        if (SOLANA_ADDRESS.matcher(id).matches()) {
            return solanaTrackingEmitter;
        }
        return trackingEmitter;
    }

    @POST
    @Transactional
    public Uni<Response> create(Notification notification) {
        return Uni.createFrom().item(() -> {
            // Same normalization as rules: indexers emit lowercase EVM addresses, and the Discord lookup must match.
            notification.policyId = dk.panos.promofacie.controller.model.RuleRequest.normalizePolicyId(notification.policyId);
            if (notification.channels != null) {
                for (NotificationChannel channel : notification.channels) {
                    channel.notification = notification;
                }
            }
            persistNotification(notification);
            emitterFor(notification.policyId).send(new TrackingCommand(TrackingCommand.Action.ADD_MINT_POLICY, null, notification.policyId));
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
            emitterFor(policyId).send(new TrackingCommand(TrackingCommand.Action.REMOVE_MINT_POLICY, null, policyId));
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

    /**
     * Sets this guild's channel for a policy, creating the notification if the policy has none yet.
     * Other guilds' channels on the same policy are left alone, so several servers can follow one collection.
     * Upsert per (policy, guild, type): a guild that already has a channel for the policy gets it replaced.
     * Responds 201 when the notification was created (and ADD_MINT_POLICY sent), 200 otherwise.
     */
    @POST
    @Path("/policy/{policyId}/channels")
    public Uni<Response> upsertChannel(@PathParam("policyId") String rawPolicyId, NotificationChannel request) {
        return Uni.createFrom().item(() -> {
            if (request == null || isBlank(request.guildId) || isBlank(request.channelId) || isBlank(rawPolicyId)) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(java.util.Map.of("error", "policyId, guildId and channelId are required")).build();
            }
            String policyId = RuleRequest.normalizePolicyId(rawPolicyId);
            NotificationChannel.ChannelType type = request.type != null ? request.type : NotificationChannel.ChannelType.DISCORD;

            UpsertResult result = inTransaction(() -> {
                lockPolicy(policyId);
                Notification notification = findNotificationForPolicy(policyId);
                boolean created = notification == null;
                if (created) {
                    notification = new Notification();
                    notification.policyId = policyId;
                }

                NotificationChannel kept = null;
                for (java.util.Iterator<NotificationChannel> it = notification.channels.iterator(); it.hasNext(); ) {
                    NotificationChannel channel = it.next();
                    if (channel.type == type && Objects.equals(channel.guildId, request.guildId)) {
                        if (kept == null) {
                            kept = channel;
                        } else {
                            it.remove(); // one channel per guild per policy
                        }
                    }
                }
                if (kept != null) {
                    kept.channelId = request.channelId;
                } else {
                    NotificationChannel channel = new NotificationChannel();
                    channel.type = type;
                    channel.guildId = request.guildId;
                    channel.channelId = request.channelId;
                    channel.notification = notification;
                    notification.channels.add(channel);
                }
                if (created) {
                    persistNotification(notification);
                }
                return new UpsertResult(notification, created);
            });

            // After commit, so the indexers never track a policy whose notification was rolled back.
            if (result.created()) {
                emitterFor(policyId).send(new TrackingCommand(TrackingCommand.Action.ADD_MINT_POLICY, null, policyId));
            }
            return Response.status(result.created() ? Response.Status.CREATED : Response.Status.OK)
                    .entity(result.notification()).build();
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    /**
     * Removes this guild's channel(s) from a policy's notification. When no channel is left, the notification is
     * deleted and REMOVE_MINT_POLICY is sent. Responds 204 also when there was nothing to remove (idempotent).
     */
    @DELETE
    @Path("/policy/{policyId}/channels")
    public Uni<Response> deleteChannel(@PathParam("policyId") String rawPolicyId, @QueryParam("guildId") String guildId) {
        return Uni.createFrom().item(() -> {
            if (isBlank(guildId) || isBlank(rawPolicyId)) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(java.util.Map.of("error", "guildId query parameter is required")).build();
            }
            String policyId = RuleRequest.normalizePolicyId(rawPolicyId);

            String removedPolicyId = inTransaction(() -> {
                lockPolicy(policyId);
                Notification notification = findNotificationForPolicy(policyId);
                if (notification == null) {
                    return null;
                }
                notification.channels.removeIf(channel -> Objects.equals(channel.guildId, guildId));
                if (!notification.channels.isEmpty()) {
                    return null;
                }
                String stored = notification.policyId;
                deleteNotification(notification);
                return stored;
            });

            if (removedPolicyId != null) {
                emitterFor(removedPolicyId).send(new TrackingCommand(TrackingCommand.Action.REMOVE_MINT_POLICY, null, removedPolicyId));
            }
            return Response.noContent().build();
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    record UpsertResult(Notification notification, boolean created) {}

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    // --- Helper methods to allow mocking static Panache calls in JUnit ---

    /** Runs on the calling (worker) thread in its own transaction; the advisory lock below lives until it ends. */
    <T> T inTransaction(Supplier<T> work) {
        return QuarkusTransaction.requiringNew().call(work::get);
    }

    /** Serializes concurrent channel changes for one policy (e.g. two guilds adding the same new collection). */
    void lockPolicy(String policyId) {
        Notification.getEntityManager()
                .createNativeQuery("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtext(?1))) AS policy_lock")
                .setParameter(1, policyId)
                .getSingleResult();
    }

    /** EVM ids match case-insensitively: rows saved before normalization may be checksummed. */
    Notification findNotificationForPolicy(String policyId) {
        if (policyId.startsWith("0x")) {
            return Notification.find("lower(policyId) = ?1", policyId.toLowerCase()).firstResult();
        }
        return Notification.find("policyId", policyId).firstResult();
    }

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
