package dk.panos.promofacie.controller;

import dk.panos.promofacie.controller.model.NonceEntry;
import dk.panos.promofacie.controller.model.VerifyRequest;
import dk.panos.promofacie.db.WalletPersistenceService;
import dk.panos.promofacie.kafka.model.TrackingCommand;
import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import org.cardanofoundation.cip30.AddressFormat;
import org.cardanofoundation.cip30.CIP30Verifier;
import org.cardanofoundation.cip30.Cip30VerificationResult;
import org.cardanofoundation.cip30.MessageFormat;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Path("/api/wallet")
@Authenticated
public class WalletVerificationResource {
    private static final Logger log = LoggerFactory.getLogger(WalletVerificationResource.class);

    @Inject
    JsonWebToken jwt;

    @Inject
    @Channel("wallet-tracking-out")
    Emitter<TrackingCommand> walletTrackingEmitter;

    @Inject
    WalletPersistenceService walletPersistenceService;

    private final ConcurrentHashMap<String, NonceEntry> nonces = new ConcurrentHashMap<>();

    @POST
    @Path("/challenge")
    public Response challenge() {
        log.info("Challenge requested");
        String discordId = jwt.getClaim("discord_id");
        log.info("Discord ID: {}", discordId);
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        String nonce = HexFormat.of().formatHex(bytes);
        nonces.put(discordId, new NonceEntry(nonce, Instant.now().plusSeconds(300)));
        log.info("Nonce: {}", nonce);
        return Response.ok(Map.of("nonce", nonce)).build();
    }

    @POST
    @Path("/verify")
    public Response verify(VerifyRequest req) {
        String discordId = jwt.getClaim("discord_id");

        NonceEntry entry = nonces.remove(discordId);
        if (entry == null || entry.isExpired())
            return Response.status(400).entity(Map.of("message", "Nonce missing or expired")).build();

        if (!verifyWalletOwnership(req.stakeAddress(), req.signature(), req.key(), entry.nonce()))
            return Response.status(400).entity(Map.of("message", "Signature verification failed")).build();
        log.info("Signature verification successful for stakeAddress={} and discordId={}", req.stakeAddress(), discordId);
        
        walletTrackingEmitter.send(new TrackingCommand(TrackingCommand.Action.ADD_ADDRESS, req.stakeAddress(), null))
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to send tracking command stakeAddress={}: {}", req.stakeAddress(), ex.getMessage(), ex);
                    } else {
                        walletPersistenceService.persist(req.stakeAddress(), discordId);
                    }
                });

        return Response.ok(Map.of("discordId", discordId, "stakeAddress", req.stakeAddress())).build();
    }

    public boolean verifyWalletOwnership(String stakeAddress, String signature, String key, String nonce) {
        try {
            var verifier = new CIP30Verifier(signature, key);
            Cip30VerificationResult result = verifier.verify();

            if (!result.isValid()) return false;

            String addressFromSig = result.getAddress(AddressFormat.TEXT).orElse("");
            if (!addressFromSig.equals(stakeAddress)) {
                return false;
            }

            String message = result.getMessage(MessageFormat.TEXT);
            return message.equals(nonce);

        } catch (Exception e) {
            return false;
        }
    }
}
