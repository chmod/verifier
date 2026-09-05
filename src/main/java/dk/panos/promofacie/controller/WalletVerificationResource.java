package dk.panos.promofacie.controller;

import dk.panos.promofacie.controller.model.NonceEntry;
import dk.panos.promofacie.controller.model.VerifyRequest;
import dk.panos.promofacie.controller.model.WalletAssociationResponse;
import dk.panos.promofacie.db.Chain;
import dk.panos.promofacie.db.Wallet;
import dk.panos.promofacie.db.WalletPersistenceService;
import dk.panos.promofacie.kafka.model.TrackingCommand;
import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
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
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
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
    @Channel("robinhood-tracking-out")
    Emitter<TrackingCommand> robinhoodTrackingEmitter;

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
        log.info("Verify endpoint called: discordId={}, req={}", discordId, req);

        NonceEntry entry = nonces.remove(discordId);
        if (entry == null || entry.isExpired())
            return Response.status(400).entity(Map.of("message", "Nonce missing or expired")).build();

        String resolvedAddress = req.getResolvedAddress();
        if (resolvedAddress == null || resolvedAddress.isBlank()) {
            return Response.status(400).entity(Map.of("message", "Wallet address is required")).build();
        }

        boolean isRobinhood = "ROBINHOOD".equalsIgnoreCase(req.chain())
                || resolvedAddress.startsWith("0x")
                || resolvedAddress.startsWith("0X");

        boolean verified;
        if (isRobinhood) {
            verified = verifyEvmWalletOwnership(resolvedAddress, req.signature(), entry.nonce());
        } else {
            verified = verifyWalletOwnership(resolvedAddress, req.signature(), req.key(), entry.nonce());
        }

        if (!verified) {
            return Response.status(400).entity(Map.of("message", "Signature verification failed")).build();
        }

        Chain targetChain = isRobinhood ? Chain.ROBINHOOD : Chain.CARDANO;
        log.info("Signature verification successful for address={} discordId={} chain={}", 
                resolvedAddress, discordId, targetChain);

        TrackingCommand cmd = new TrackingCommand(TrackingCommand.Action.ADD_ADDRESS, resolvedAddress, null);
        if (isRobinhood) {
            robinhoodTrackingEmitter.send(cmd)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.error("Failed to send tracking command to robinhood address={}: {}", resolvedAddress, ex.getMessage(), ex);
                        } else {
                            log.info("Successfully sent ADD_ADDRESS tracking command to robinhood for address: {}", resolvedAddress);
                            walletPersistenceService.persist(resolvedAddress, discordId, targetChain);
                        }
                    });
        } else {
            walletTrackingEmitter.send(cmd)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.error("Failed to send tracking command to cardano stakeAddress={}: {}", resolvedAddress, ex.getMessage(), ex);
                        } else {
                            log.info("Successfully sent ADD_ADDRESS tracking command to cardano for stakeAddress: {}", resolvedAddress);
                            walletPersistenceService.persist(resolvedAddress, discordId, targetChain);
                        }
                    });
        }

        return Response.ok(Map.of(
                "discordId", discordId, 
                "stakeAddress", resolvedAddress, 
                "address", resolvedAddress, 
                "chain", targetChain.name()
        )).build();
    }

    @GET
    @Path("/association")
    public Response getAssociation() {
        String discordId = jwt.getClaim("discord_id");
        if (discordId == null || discordId.isBlank()) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(Map.of("message", "Missing or invalid discord_id claim"))
                    .build();
        }

        List<Wallet> wallets = walletPersistenceService.findByDiscordId(discordId);
        List<WalletAssociationResponse> dtos = wallets.stream()
                .map(w -> new WalletAssociationResponse(
                        w.id != null ? w.id.toString() : null,
                        w.id != null ? w.id.toString() : null,
                        w.getAddress(),
                        w.getDiscordId(),
                        w.getChain() != null ? w.getChain().name() : "CARDANO"
                ))
                .toList();

        return Response.ok(dtos).build();
    }

    @DELETE
    @Path("/association")
    public Response deleteAssociation(
            @QueryParam("stakeAddress") String stakeAddress,
            @QueryParam("address") String address
    ) {
        String discordId = jwt.getClaim("discord_id");
        if (discordId == null || discordId.isBlank()) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(Map.of("message", "Missing or invalid discord_id claim"))
                    .build();
        }

        String targetAddress = (address != null && !address.isBlank()) ? address : stakeAddress;

        if (targetAddress != null && !targetAddress.isBlank()) {
            Wallet wallet = walletPersistenceService.findByAddressAndDiscordId(targetAddress, discordId);
            if (wallet == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(Map.of("message", "Wallet association not found"))
                        .build();
            }

            TrackingCommand cmd = new TrackingCommand(TrackingCommand.Action.REMOVE_ADDRESS, targetAddress, null);
            if (wallet.getChain() == Chain.ROBINHOOD) {
                robinhoodTrackingEmitter.send(cmd)
                        .whenComplete((result, ex) -> {
                            if (ex != null) {
                                log.error("Failed to send REMOVE_ADDRESS to robinhood for address={}: {}", targetAddress, ex.getMessage(), ex);
                            } else {
                                log.info("Successfully sent REMOVE_ADDRESS to robinhood for address: {}", targetAddress);
                            }
                        });
            } else {
                walletTrackingEmitter.send(cmd)
                        .whenComplete((result, ex) -> {
                            if (ex != null) {
                                log.error("Failed to send REMOVE_ADDRESS to cardano for stakeAddress={}: {}", targetAddress, ex.getMessage(), ex);
                            } else {
                                log.info("Successfully sent REMOVE_ADDRESS to cardano for stakeAddress: {}", targetAddress);
                            }
                        });
            }

            walletPersistenceService.deleteByAddressAndDiscordId(targetAddress, discordId);
            return Response.ok(Map.of("message", "Wallet association unlinked successfully")).build();
        } else {
            List<Wallet> wallets = walletPersistenceService.findByDiscordId(discordId);
            for (Wallet w : wallets) {
                TrackingCommand cmd = new TrackingCommand(TrackingCommand.Action.REMOVE_ADDRESS, w.getAddress(), null);
                if (w.getChain() == Chain.ROBINHOOD) {
                    robinhoodTrackingEmitter.send(cmd)
                            .whenComplete((result, ex) -> {
                                if (ex != null) {
                                    log.error("Failed to send REMOVE_ADDRESS to robinhood for address={}: {}", w.getAddress(), ex.getMessage(), ex);
                                } else {
                                    log.info("Successfully sent REMOVE_ADDRESS to robinhood for address: {}", w.getAddress());
                                }
                            });
                } else {
                    walletTrackingEmitter.send(cmd)
                            .whenComplete((result, ex) -> {
                                if (ex != null) {
                                    log.error("Failed to send REMOVE_ADDRESS to cardano for stakeAddress={}: {}", w.getAddress(), ex.getMessage(), ex);
                                } else {
                                    log.info("Successfully sent REMOVE_ADDRESS to cardano for stakeAddress: {}", w.getAddress());
                                }
                            });
                }
            }

            long deletedCount = walletPersistenceService.deleteAllByDiscordId(discordId);
            return Response.ok(Map.of("message", "All wallet associations unlinked successfully", "count", deletedCount)).build();
        }
    }

    public boolean verifyEvmWalletOwnership(String address, String signature, String nonce) {
        try {
            if (address == null || signature == null || nonce == null) {
                log.warn("EVM verify missing parameters: address={}, hasSig={}, hasNonce={}", 
                        address, signature != null, nonce != null);
                return false;
            }
            String cleanSig = signature.startsWith("0x") ? signature.substring(2) : signature;
            if (cleanSig.length() != 130) {
                log.warn("Invalid EVM signature length: expected 130 hex chars, got {}", cleanSig.length());
                return false;
            }

            byte[] r = HexFormat.of().parseHex(cleanSig.substring(0, 64));
            byte[] s = HexFormat.of().parseHex(cleanSig.substring(64, 128));
            byte v = (byte) Integer.parseInt(cleanSig.substring(128, 130), 16);
            if (v < 27) {
                v += 27;
            }

            Sign.SignatureData sigData = new Sign.SignatureData(v, r, s);

            // 1. Try UTF-8 bytes of nonce string
            byte[] messageBytes = nonce.getBytes(StandardCharsets.UTF_8);
            BigInteger publicKey = Sign.signedPrefixedMessageToKey(messageBytes, sigData);
            String recoveredAddress = "0x" + Keys.getAddress(publicKey);

            boolean match = recoveredAddress.equalsIgnoreCase(address.trim());

            // 2. If not matched, try raw parsed hex bytes if nonce is a hex string
            if (!match) {
                try {
                    String cleanNonce = nonce.startsWith("0x") ? nonce.substring(2) : nonce;
                    if (cleanNonce.length() % 2 == 0) {
                        byte[] rawBytes = HexFormat.of().parseHex(cleanNonce);
                        BigInteger pk2 = Sign.signedPrefixedMessageToKey(rawBytes, sigData);
                        String recovered2 = "0x" + Keys.getAddress(pk2);
                        if (recovered2.equalsIgnoreCase(address.trim())) {
                            match = true;
                            recoveredAddress = recovered2;
                        }
                    }
                } catch (Exception ignored) {
                }
            }

            log.info("EVM verify result: match={}, recovered={}, expected={}", match, recoveredAddress, address);
            return match;
        } catch (Exception e) {
            log.error("EVM signature verification failed for address={}", address, e);
            return false;
        }
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
