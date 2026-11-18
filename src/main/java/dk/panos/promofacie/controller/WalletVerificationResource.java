package dk.panos.promofacie.controller;

import dk.panos.promofacie.controller.model.ChallengeRequest;
import dk.panos.promofacie.controller.model.NonceEntry;
import dk.panos.promofacie.controller.model.VerifyRequest;
import dk.panos.promofacie.controller.model.WalletAssociationResponse;
import dk.panos.promofacie.db.Chain;
import dk.panos.promofacie.db.Wallet;
import dk.panos.promofacie.db.WalletAlreadyLinkedException;
import dk.panos.promofacie.db.WalletPersistenceService;
import dk.panos.promofacie.service.NonceStore;
import dk.panos.promofacie.service.TooManyChallengesException;
import dk.panos.promofacie.service.RoleEvaluationService;
import dk.panos.promofacie.kafka.model.TrackingCommand;
import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;
import dk.panos.promofacie.util.Base58;
import org.cardanofoundation.cip30.AddressFormat;
import org.cardanofoundation.cip30.CIP30Verifier;
import org.cardanofoundation.cip30.Cip30VerificationResult;
import org.bouncycastle.crypto.digests.Blake2bDigest;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

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
    @Channel("solana-tracking-out")
    Emitter<TrackingCommand> solanaTrackingEmitter;

    @Inject
    WalletPersistenceService walletPersistenceService;

    @Inject
    RoleEvaluationService roleEvaluationService;

    @Inject
    NonceStore nonceStore;

    static final Duration NONCE_TTL = Duration.ofMinutes(5);
    static final long ROBINHOOD_CHAIN_ID = 4663;
    static final String DEFAULT_SIWE_DOMAIN = "associate.promofacie.site";
    static final String DEFAULT_SIWE_URI = "https://associate.promofacie.site";
    private static final Pattern EVM_ADDRESS = Pattern.compile("^0x[0-9a-fA-F]{40}$");
    // Characters allowed in a SIWE statement (RFC 3986 unreserved) so the Discord ID can't break the message format
    private static final Pattern SIWE_SAFE_ID = Pattern.compile("^[0-9A-Za-z._~-]{1,64}$");

    /** The site users verify on. Comes from config only: request headers such as Origin are attacker-controlled. */
    @ConfigProperty(name = "verifier.siwe.domain", defaultValue = DEFAULT_SIWE_DOMAIN)
    String siweDomain = DEFAULT_SIWE_DOMAIN;

    @ConfigProperty(name = "verifier.siwe.uri", defaultValue = DEFAULT_SIWE_URI)
    String siweUri = DEFAULT_SIWE_URI;

    /** Legacy challenge (no chain): the single-line format used by Cardano and Solana. */
    public Response challenge() {
        return challenge(null);
    }

    @POST
    @Path("/challenge")
    public Response challenge(ChallengeRequest req) {
        log.info("Challenge requested");
        String discordId = jwt.getClaim("discord_id");
        if (discordId == null || discordId.isBlank()) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(Map.of("message", "Missing or invalid discord_id claim"))
                    .build();
        }
        log.info("Discord ID: {}", discordId);
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        String rawNonce = HexFormat.of().formatHex(bytes);
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);

        NonceEntry entry;
        if (req != null && isEvmChain(req.chain())) {
            String address = req.address() != null ? req.address().trim() : "";
            if (!EVM_ADDRESS.matcher(address).matches()) {
                return Response.status(400).entity(Map.of("message", "A valid EVM address is required for an EVM challenge")).build();
            }
            if (!SIWE_SAFE_ID.matcher(discordId).matches()) {
                log.warn("discord_id claim contains characters not allowed in a SIWE statement: {}", discordId);
                return Response.status(400).entity(Map.of("message", "Unsupported Discord ID format")).build();
            }
            String checksumAddress = Keys.toChecksumAddress(address);
            String message = buildSiweMessage(siweDomain, siweUri, checksumAddress, discordId, rawNonce,
                    now, now.plus(NONCE_TTL));
            entry = new NonceEntry(message, Chain.ROBINHOOD, checksumAddress);
        } else {
            entry = new NonceEntry(legacyChallengeMessage(discordId, rawNonce, now),
                    req != null ? parseNonEvmChain(req.chain()) : null, null);
        }

        String nonceId;
        try {
            nonceId = nonceStore.issue(discordId, entry, NONCE_TTL);
        } catch (TooManyChallengesException e) {
            log.warn("Challenge rate limit hit for discordId={}", discordId);
            return Response.status(429).entity(Map.of("message",
                    "Too many verification attempts. Please wait a few minutes and try again.")).build();
        }
        log.info("Generated challenge nonceId={} chain={} for discordId={}", nonceId, entry.chain(), discordId);
        return Response.ok(Map.of("nonce", entry.message(), "nonceId", nonceId)).build();
    }

    private static String legacyChallengeMessage(String discordId, String rawNonce, Instant issuedAt) {
        // Single line of printable ASCII on purpose: the Ledger Cardano app rejects CIP-8 text
        // messages containing control characters (e.g. '\n'), leading/trailing or double spaces,
        // and only displays ~160 bytes in full. Eternl reports that device rejection as
        // "user declined sign data".
        return String.format(
                "Promofacie Wallet Verification | Discord ID: %s | Nonce: %s | Issued At: %d",
                discordId, rawNonce, issuedAt.getEpochSecond()
        );
    }

    /**
     * EIP-4361 message. Wallets (MetaMask, Rabby, Rainbow…) parse this format and warn the user when the domain doesn't
     * match the site requesting the signature, which defeats relaying our challenge through a phishing page.
     * Multi-line by definition, so it is only used for EVM; Cardano keeps the Ledger-safe single line.
     */
    static String buildSiweMessage(String domain, String uri, String checksumAddress, String discordId,
                                   String nonce, Instant issuedAt, Instant expiresAt) {
        return domain + " wants you to sign in with your Ethereum account:\n"
                + checksumAddress + "\n"
                + "\n"
                + "Link this wallet to Discord ID " + discordId + " for Promofacie role verification.\n"
                + "\n"
                + "URI: " + uri + "\n"
                + "Version: 1\n"
                + "Chain ID: " + ROBINHOOD_CHAIN_ID + "\n"
                + "Nonce: " + nonce + "\n"
                + "Issued At: " + issuedAt + "\n"
                + "Expiration Time: " + expiresAt;
    }

    private static boolean isEvmChain(String chain) {
        return "ROBINHOOD".equalsIgnoreCase(chain) || "EVM".equalsIgnoreCase(chain);
    }

    private static Chain parseNonEvmChain(String chain) {
        if ("CARDANO".equalsIgnoreCase(chain)) return Chain.CARDANO;
        if ("SOLANA".equalsIgnoreCase(chain)) return Chain.SOLANA;
        return null;
    }

    @POST
    @Path("/verify")
    public Response verify(VerifyRequest req) {
        String discordId = jwt.getClaim("discord_id");
        if (discordId == null || discordId.isBlank()) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(Map.of("message", "Missing or invalid discord_id claim"))
                    .build();
        }
        log.info("Verify endpoint called: discordId={}, req={}", discordId, req);

        // Consumed before the signature check: every attempt, successful or not, burns the challenge.
        NonceEntry entry = nonceStore.consume(discordId, req.nonceId()).orElse(null);
        if (entry == null)
            return Response.status(400).entity(Map.of("message", "Nonce missing or expired")).build();

        String resolvedAddress = req.getResolvedAddress();
        if (resolvedAddress == null || resolvedAddress.isBlank()) {
            return Response.status(400).entity(Map.of("message", "Wallet address is required")).build();
        }

        boolean isSolana = "SOLANA".equalsIgnoreCase(req.chain());

        boolean isRobinhood = !isSolana && ("ROBINHOOD".equalsIgnoreCase(req.chain())
                || resolvedAddress.startsWith("0x")
                || resolvedAddress.startsWith("0X"));

        Chain targetChain = isSolana ? Chain.SOLANA : (isRobinhood ? Chain.ROBINHOOD : Chain.CARDANO);

        // EVM must sign the SIWE challenge issued for this exact address; a legacy challenge would bypass the domain binding.
        if (targetChain == Chain.ROBINHOOD
                && (!entry.isSiwe() || !entry.address().equalsIgnoreCase(resolvedAddress.trim()))) {
            return Response.status(400).entity(Map.of("message",
                    "This challenge was not issued for this EVM address. Reload the page and try again.")).build();
        }
        if (targetChain != Chain.ROBINHOOD && entry.chain() != null && entry.chain() != targetChain) {
            return Response.status(400).entity(Map.of("message", "This challenge was issued for a different chain.")).build();
        }

        boolean verified;
        if (isSolana) {
            verified = verifySolanaWalletOwnership(resolvedAddress, req.signature(), entry.message());
        } else if (isRobinhood) {
            verified = verifyEvmWalletOwnership(resolvedAddress, req.signature(), entry.message());
        } else {
            verified = verifyWalletOwnership(resolvedAddress, req.signature(), req.key(), entry.message());
        }

        if (!verified) {
            return Response.status(400).entity(Map.of("message", "Signature verification failed")).build();
        }

        log.info("Signature verification successful for address={} discordId={} chain={}", 
                resolvedAddress, discordId, targetChain);

        // 1. Synchronously persist in database. A wallet linked to another Discord account is never moved:
        // a phished signature must not let an attacker take over someone else's link and roles.
        try {
            walletPersistenceService.persist(resolvedAddress, discordId, targetChain);
        } catch (WalletAlreadyLinkedException e) {
            return Response.status(Response.Status.CONFLICT).entity(Map.of("message",
                    "This wallet is already linked to another Discord account. Unlink it from that account first.")).build();
        }

        // Enqueue role recalculation for user across all configured guilds
        if (roleEvaluationService != null) {
            roleEvaluationService.enqueueUserEvaluationForAllGuilds(discordId);
        }

        // 2. Broadcast tracking command to Kafka
        TrackingCommand cmd = new TrackingCommand(TrackingCommand.Action.ADD_ADDRESS, resolvedAddress, null);
        if (isSolana) {
            solanaTrackingEmitter.send(cmd)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.error("Failed to send tracking command to solana address={}: {}", resolvedAddress, ex.getMessage(), ex);
                        } else {
                            log.info("Successfully sent ADD_ADDRESS tracking command to solana for address: {}", resolvedAddress);
                        }
                    });
        } else if (isRobinhood) {
            robinhoodTrackingEmitter.send(cmd)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.error("Failed to send tracking command to robinhood address={}: {}", resolvedAddress, ex.getMessage(), ex);
                        } else {
                            log.info("Successfully sent ADD_ADDRESS tracking command to robinhood for address: {}", resolvedAddress);
                        }
                    });
        } else {
            walletTrackingEmitter.send(cmd)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.error("Failed to send tracking command to cardano stakeAddress={}: {}", resolvedAddress, ex.getMessage(), ex);
                        } else {
                            log.info("Successfully sent ADD_ADDRESS tracking command to cardano for stakeAddress: {}", resolvedAddress);
                        }
                    });
        }

        // 3. Return 200 OK only after DB record is committed
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
            if (wallet.getChain() == Chain.SOLANA) {
                solanaTrackingEmitter.send(cmd)
                        .whenComplete((result, ex) -> {
                            if (ex != null) {
                                log.error("Failed to send REMOVE_ADDRESS to solana for address={}: {}", targetAddress, ex.getMessage(), ex);
                            } else {
                                log.info("Successfully sent REMOVE_ADDRESS to solana for address: {}", targetAddress);
                            }
                        });
            } else if (wallet.getChain() == Chain.ROBINHOOD) {
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
            if (roleEvaluationService != null) {
                roleEvaluationService.enqueueUserEvaluationForAllGuilds(discordId);
            }
            return Response.ok(Map.of("message", "Wallet association unlinked successfully")).build();
        } else {
            List<Wallet> wallets = walletPersistenceService.findByDiscordId(discordId);
            for (Wallet w : wallets) {
                TrackingCommand cmd = new TrackingCommand(TrackingCommand.Action.REMOVE_ADDRESS, w.getAddress(), null);
                if (w.getChain() == Chain.SOLANA) {
                    solanaTrackingEmitter.send(cmd)
                            .whenComplete((result, ex) -> {
                                if (ex != null) {
                                    log.error("Failed to send REMOVE_ADDRESS to solana for address={}: {}", w.getAddress(), ex.getMessage(), ex);
                                } else {
                                    log.info("Successfully sent REMOVE_ADDRESS to solana for address: {}", w.getAddress());
                                }
                            });
                } else if (w.getChain() == Chain.ROBINHOOD) {
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
            if (roleEvaluationService != null) {
                roleEvaluationService.enqueueUserEvaluationForAllGuilds(discordId);
            }
            return Response.ok(Map.of("message", "All wallet associations unlinked successfully", "count", deletedCount)).build();
        }
    }

    public Response deleteAssociation(String stakeAddress) {
        return deleteAssociation(stakeAddress, null);
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

    public boolean verifySolanaWalletOwnership(String address, String signature, String nonce) {
        try {
            if (address == null || signature == null || nonce == null) {
                log.warn("Solana verify missing parameters: address={}, hasSig={}, hasNonce={}",
                        address, signature != null, nonce != null);
                return false;
            }

            // Decode address (Base58 32-byte public key)
            byte[] rawPubKey;
            try {
                rawPubKey = Base58.decode(address.trim());
            } catch (Exception e) {
                log.warn("Invalid Solana address Base58: {}", address);
                return false;
            }
            if (rawPubKey.length != 32) {
                log.warn("Invalid Solana public key length: expected 32 bytes, got {}", rawPubKey.length);
                return false;
            }

            // Decode signature (64 bytes). Try Base58, then Base64, then Hex
            byte[] sigBytes = null;
            String cleanSig = signature.trim();
            try {
                byte[] b58 = Base58.decode(cleanSig);
                if (b58.length == 64) {
                    sigBytes = b58;
                }
            } catch (Exception ignored) {}

            if (sigBytes == null) {
                try {
                    byte[] b64 = Base64.getDecoder().decode(cleanSig);
                    if (b64.length == 64) {
                        sigBytes = b64;
                    }
                } catch (Exception ignored) {}
            }

            if (sigBytes == null) {
                try {
                    String hex = cleanSig.startsWith("0x") ? cleanSig.substring(2) : cleanSig;
                    if (hex.length() == 128) {
                        sigBytes = HexFormat.of().parseHex(hex);
                    }
                } catch (Exception ignored) {}
            }

            if (sigBytes == null || sigBytes.length != 64) {
                log.warn("Invalid Solana signature: could not decode 64 bytes from input (len={})", cleanSig.length());
                return false;
            }

            // Reconstruct X.509 SubjectPublicKeyInfo with RFC 8410 Ed25519 prefix
            byte[] rfc8410Prefix = new byte[]{0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00};
            byte[] fullX509 = new byte[rfc8410Prefix.length + rawPubKey.length];
            System.arraycopy(rfc8410Prefix, 0, fullX509, 0, rfc8410Prefix.length);
            System.arraycopy(rawPubKey, 0, fullX509, rfc8410Prefix.length, rawPubKey.length);

            KeyFactory kf = KeyFactory.getInstance("Ed25519");
            PublicKey pubKey = kf.generatePublic(new X509EncodedKeySpec(fullX509));

            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(pubKey);
            verifier.update(nonce.getBytes(StandardCharsets.UTF_8));
            boolean verified = verifier.verify(sigBytes);

            log.info("Solana verify result: verified={}, address={}", verified, address);
            return verified;
        } catch (Exception e) {
            log.error("Solana signature verification failed for address={}", address, e);
            return false;
        }
    }

    public boolean verifyWalletOwnership(String stakeAddress, String signature, String key, String nonce) {
        try {
            var verifier = new CIP30Verifier(signature, key);
            Cip30VerificationResult result = verifier.verify();

            if (!result.isValid()) {
                log.warn("Cardano verify: invalid COSE signature for address={}, error={}",
                        stakeAddress, result.getValidationError().orElse(null));
                return false;
            }

            String addressFromSig = result.getAddress(AddressFormat.TEXT).orElse("");
            if (!addressFromSig.equals(stakeAddress)) {
                log.warn("Cardano verify: signed address {} does not match requested address {}",
                        addressFromSig, stakeAddress);
                return false;
            }

            // getMessage() is the COSE_Sign1 payload field (raw message, or its hash when hashed=true);
            // getCosePayload() would be the whole serialized Sig_structure, so don't use it here.
            boolean matches = cip8PayloadMatchesNonce(result.isHashed(), result.getMessage(), nonce);
            if (!matches) {
                log.warn("Cardano verify: signed payload does not match challenge (hashed={}) for address={}",
                        result.isHashed(), stakeAddress);
            }
            return matches;

        } catch (Exception e) {
            log.warn("Cardano verify failed for address={}", stakeAddress, e);
            return false;
        }
    }

    /**
     * CIP-8 lets the signer put either the raw message or its Blake2b-224 hash into the COSE_Sign1
     * payload (header "hashed": true). Hardware wallets such as Ledger may use the hashed form,
     * so accept both.
     */
    static boolean cip8PayloadMatchesNonce(boolean hashed, byte[] payload, String nonce) {
        if (payload == null || nonce == null) return false;
        byte[] nonceBytes = nonce.getBytes(StandardCharsets.UTF_8);
        byte[] expected = hashed ? blake2b224(nonceBytes) : nonceBytes;
        return MessageDigest.isEqual(expected, payload);
    }

    static byte[] blake2b224(byte[] input) {
        Blake2bDigest digest = new Blake2bDigest(224);
        digest.update(input, 0, input.length);
        byte[] out = new byte[digest.getDigestSize()];
        digest.doFinal(out, 0);
        return out;
    }
}
