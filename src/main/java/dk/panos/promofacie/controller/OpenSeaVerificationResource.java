package dk.panos.promofacie.controller;

import dk.panos.promofacie.controller.model.NonceEntry;
import dk.panos.promofacie.controller.model.OpenSeaChallengeRequest;
import dk.panos.promofacie.controller.model.OpenSeaVerifyRequest;
import dk.panos.promofacie.db.Chain;
import dk.panos.promofacie.db.WalletAlreadyLinkedException;
import dk.panos.promofacie.db.WalletPersistenceService;
import dk.panos.promofacie.kafka.model.TrackingCommand;
import dk.panos.promofacie.service.NonceStore;
import dk.panos.promofacie.service.RoleEvaluationService;
import dk.panos.promofacie.service.opensea.OpenSeaAccount;
import dk.panos.promofacie.service.opensea.OpenSeaCheckLimiter;
import dk.panos.promofacie.service.opensea.OpenSeaClient;
import dk.panos.promofacie.service.opensea.OpenSeaUnavailableException;
import dk.panos.promofacie.util.EvmAddress;
import dk.panos.promofacie.util.SolanaAddress;
import io.quarkus.security.Authenticated;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Wallet verification through the OpenSea profile bio, for wallets that can't connect to other sites (such as
 * OpenSea's built-in mobile wallet). The user puts a one-time code in the bio of their OpenSea profile; only someone
 * signed in to OpenSea as that wallet can edit it. Nothing is signed on our site, so there is no signature to relay.
 * <p>
 * One successful check links every public wallet of the profile (its primary {@code address} plus
 * {@code include=wallets}). OpenSea registers a wallet only after a SIWX signature from that wallet, and never returns
 * private or watched (unproven) wallets, so controlling the profile proves control of each listed wallet. EVM wallets
 * are linked as ROBINHOOD and Solana wallets as SOLANA; anything else is ignored. Wallets already linked to another
 * Discord account are skipped, never moved.
 */
@Path("/api/wallet/opensea")
@Authenticated
@ApplicationScoped
public class OpenSeaVerificationResource {

    private static final Logger log = LoggerFactory.getLogger(OpenSeaVerificationResource.class);

    /** Editing a bio takes longer than approving a signature. */
    static final Duration BIO_CHALLENGE_TTL = Duration.ofMinutes(30);
    /** Upper bound on wallets linked from one profile, to bound the work of a single request. */
    static final int MAX_WALLETS_PER_PROFILE = 25;
    // No '.': OpenSea rejects bios containing anything that looks like a link, and "name.something" can look like a
    // domain. Dotted Discord usernames fall back to the Discord ID.
    private static final Pattern SAFE_USERNAME = Pattern.compile("^[A-Za-z0-9_]{2,32}$");
    // No 0/O, 1/I/L: the code is typed or pasted by people
    private static final char[] CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String NOT_CONFIGURED = "OpenSea verification is not available right now.";

    @Inject
    JsonWebToken jwt;

    @Inject
    NonceStore nonceStore;

    @Inject
    OpenSeaClient openSea;

    @Inject
    OpenSeaCheckLimiter checkLimiter;

    @Inject
    WalletPersistenceService walletPersistenceService;

    @Inject
    RoleEvaluationService roleEvaluationService;

    @Inject
    @Channel("robinhood-tracking-out")
    Emitter<TrackingCommand> robinhoodTrackingEmitter;

    @Inject
    @Channel("solana-tracking-out")
    Emitter<TrackingCommand> solanaTrackingEmitter;

    /**
     * The site as named in the bio line. Deliberately not the domain: OpenSea rejects bios with links other than
     * OpenSea, X, Instagram or YouTube ("Links must use HTTP or HTTPS and point to …"), and a bare domain counts.
     */
    @ConfigProperty(name = "opensea.bio-site-name", defaultValue = DEFAULT_BIO_SITE_NAME)
    String bioSiteName = DEFAULT_BIO_SITE_NAME;
    static final String DEFAULT_BIO_SITE_NAME = "PromoFacie";

    @GET
    @Path("/status")
    public Response status() {
        return Response.ok(Map.of("enabled", openSea.isConfigured())).build();
    }

    @POST
    @Path("/challenge")
    public Response challenge(OpenSeaChallengeRequest req) {
        String discordId = jwt.getClaim("discord_id");
        if (discordId == null || discordId.isBlank()) {
            return error(Response.Status.UNAUTHORIZED, "Missing or invalid discord_id claim");
        }
        if (!openSea.isConfigured()) {
            return error(Response.Status.SERVICE_UNAVAILABLE, NOT_CONFIGURED);
        }
        String address = req != null && req.address() != null ? req.address().trim() : "";
        Chain entryChain = chainOf(address);
        if (entryChain == null) {
            return error(Response.Status.BAD_REQUEST, "Enter one of the wallet addresses on your OpenSea profile.");
        }

        String code = newCode();
        String bioText = bioText(code, discordId);
        NonceEntry entry = new NonceEntry(code, entryChain, EvmAddress.normalize(address),
                NonceEntry.ProofMethod.OPENSEA_BIO);
        String nonceId = nonceStore.issue(discordId, entry, BIO_CHALLENGE_TTL);
        log.info("Issued OpenSea bio challenge nonceId={} for discordId={}", nonceId, discordId);
        return Response.ok(Map.of(
                "nonceId", nonceId,
                "code", code,
                "bioText", bioText,
                "expiresInSeconds", BIO_CHALLENGE_TTL.toSeconds())).build();
    }

    @POST
    @Path("/verify")
    public Response verify(OpenSeaVerifyRequest req) {
        String discordId = jwt.getClaim("discord_id");
        if (discordId == null || discordId.isBlank()) {
            return error(Response.Status.UNAUTHORIZED, "Missing or invalid discord_id claim");
        }
        if (!openSea.isConfigured()) {
            return error(Response.Status.SERVICE_UNAVAILABLE, NOT_CONFIGURED);
        }
        String nonceId = req != null ? req.nonceId() : null;
        // Peek, don't consume: OpenSea can take a moment to show an edited bio, so the user may check several times.
        Optional<NonceEntry> pending = nonceStore.peek(discordId, nonceId);
        if (pending.isEmpty() || pending.get().method() != NonceEntry.ProofMethod.OPENSEA_BIO) {
            return error(Response.Status.BAD_REQUEST, "This code expired or was replaced. Get a new code and try again.");
        }
        NonceEntry entry = pending.get();

        // Every lookup spends the shared API key's quota, so it's limited per user and globally.
        OpenSeaCheckLimiter.Decision decision = checkLimiter.tryAcquire(discordId);
        if (!decision.allowed()) {
            return limited(decision, discordId);
        }

        Optional<OpenSeaAccount> account;
        try {
            account = openSea.getAccount(entry.address());
        } catch (OpenSeaUnavailableException e) {
            log.warn("OpenSea lookup failed for discordId={}: {}", discordId, e.getMessage());
            return error(Response.Status.SERVICE_UNAVAILABLE, "OpenSea isn't responding right now. Try again in a minute.");
        }
        if (account.isEmpty()) {
            return error(Response.Status.NOT_FOUND,
                    "OpenSea has no profile for " + entry.address() + ". Check the address on your OpenSea profile page.");
        }
        // The entered address must be one of the profile's own wallets as reported in the response; the lookup path
        // also accepts usernames, so the lookup itself proves nothing.
        List<String> profileWallets = profileWallets(account.get());
        if (!profileWallets.contains(entry.address())) {
            return error(Response.Status.BAD_REQUEST,
                    "This address isn't a public wallet of that OpenSea profile. Make it public in OpenSea, or use another of your addresses.");
        }
        String bio = account.get().bio();
        if (bio == null || !bio.contains(entry.message())) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of(
                    "message", "Your OpenSea bio doesn't show the code yet. Save your profile, wait a few seconds and check again.",
                    "code", "BIO_CODE_MISSING")).build();
        }

        // Consume atomically: of two concurrent successful checks, only one links the wallet.
        if (nonceStore.consume(discordId, nonceId).isEmpty()) {
            return error(Response.Status.BAD_REQUEST, "This code expired or was already used. Get a new code and try again.");
        }

        List<Map<String, String>> linked = new ArrayList<>();
        List<Map<String, String>> skipped = new ArrayList<>();
        for (String wallet : profileWallets) {
            Chain chain = chainOf(wallet);
            try {
                // Each persist is its own transaction: one conflicting wallet doesn't block the others
                walletPersistenceService.persist(wallet, discordId, chain);
                linked.add(Map.of("address", wallet, "chain", chain.name()));
                track(wallet, chain);
            } catch (WalletAlreadyLinkedException e) {
                skipped.add(Map.of("address", wallet, "chain", chain.name(), "reason", "LINKED_TO_ANOTHER_ACCOUNT"));
            }
        }
        log.info("OpenSea bio verification for discordId={}: linked={} skipped={}", discordId, linked.size(), skipped.size());

        if (linked.isEmpty()) {
            return Response.status(Response.Status.CONFLICT).entity(Map.of(
                    "message", "All wallets of this OpenSea profile are already linked to another Discord account. Unlink them there first.",
                    "linked", linked,
                    "skipped", skipped)).build();
        }
        if (roleEvaluationService != null) {
            roleEvaluationService.enqueueUserEvaluationForAllGuilds(discordId);
        }
        return Response.ok(Map.of("linked", linked, "skipped", skipped)).build();
    }

    /**
     * The profile's primary address plus its public wallets, normalized (EVM lowercase, Solana as-is), deduplicated,
     * restricted to the chains we support, and capped at {@link #MAX_WALLETS_PER_PROFILE}.
     */
    static List<String> profileWallets(OpenSeaAccount account) {
        Set<String> wallets = new LinkedHashSet<>();
        wallets.add(EvmAddress.normalize(account.address()));
        account.wallets().forEach(w -> wallets.add(EvmAddress.normalize(w)));
        List<String> supported = wallets.stream().filter(w -> chainOf(w) != null).toList();
        if (supported.size() > MAX_WALLETS_PER_PROFILE) {
            log.warn("OpenSea profile {} has {} wallets; linking the first {}", account.address(), supported.size(), MAX_WALLETS_PER_PROFILE);
            return supported.subList(0, MAX_WALLETS_PER_PROFILE);
        }
        return supported;
    }

    /** ROBINHOOD for EVM addresses, SOLANA for Solana addresses, null for anything else. */
    static Chain chainOf(String address) {
        if (EvmAddress.isEvm(address)) return Chain.ROBINHOOD;
        if (SolanaAddress.isValid(address)) return Chain.SOLANA;
        return null;
    }

    private void track(String wallet, Chain chain) {
        Emitter<TrackingCommand> emitter = chain == Chain.SOLANA ? solanaTrackingEmitter : robinhoodTrackingEmitter;
        emitter.send(new TrackingCommand(TrackingCommand.Action.ADD_ADDRESS, wallet, null)).whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("Failed to send tracking command to {} address={}: {}", chain, wallet, ex.getMessage(), ex);
            }
        });
    }

    /**
     * A refused check. Per-user limits are 429 and the global cap is 503 (code {@code OPENSEA_BUSY}); both carry
     * {@code retryAfterSeconds} and a {@code Retry-After} header, and the frontend keeps the code for both.
     */
    private Response limited(OpenSeaCheckLimiter.Decision decision, String discordId) {
        long wait = decision.retryAfterSeconds();
        return switch (decision.limit()) {
            case USER_INTERVAL -> limitedResponse(429, null, wait,
                    "Please wait " + wait + " seconds before checking again.");
            case USER_HOURLY -> limitedResponse(429, null, wait,
                    "You've reached the limit of " + decision.cap()
                            + " checks per hour. Try again in " + humanWait(wait) + ".");
            case GLOBAL_HOURLY -> {
                log.warn("OpenSea global check cap reached ({}/hour); refusing discordId={} for {}s",
                        decision.cap(), discordId, wait);
                yield limitedResponse(503, "OPENSEA_BUSY", wait,
                        "Too many OpenSea checks right now. Try again in " + humanWait(wait) + ".");
            }
            case NONE -> throw new IllegalStateException("not limited");
        };
    }

    private static Response limitedResponse(int status, String code, long wait, String message) {
        Map<String, Object> body = code == null
                ? Map.of("message", message, "retryAfterSeconds", wait)
                : Map.of("message", message, "retryAfterSeconds", wait, "code", code);
        return Response.status(status).header("Retry-After", wait).entity(body).build();
    }

    static String humanWait(long seconds) {
        if (seconds < 90) return seconds + " seconds";
        long minutes = (seconds + 59) / 60;
        return minutes + " minutes";
    }

    /**
     * The line the user pastes into their bio. It names the site and the Discord account so that a scam asking a
     * victim to paste someone else's code stands out.
     */
    String bioText(String code, String discordId) {
        String username = jwt.getClaim("preferred_username");
        String who = username != null && SAFE_USERNAME.matcher(username).matches() && !username.matches("\\d+")
                ? "@" + username
                : "ID " + discordId;
        // Any dot could make OpenSea see a link, so a misconfigured name falls back to the default
        String site = bioSiteName != null && !bioSiteName.isBlank() && !bioSiteName.contains(".")
                ? bioSiteName.trim()
                : DEFAULT_BIO_SITE_NAME;
        return "Linking this wallet to Discord " + who + " on " + site + ": " + code;
    }

    static String newCode() {
        StringBuilder sb = new StringBuilder("PF-");
        for (int i = 0; i < 12; i++) {
            if (i > 0 && i % 4 == 0) sb.append('-');
            sb.append(CODE_ALPHABET[RANDOM.nextInt(CODE_ALPHABET.length)]);
        }
        return sb.toString();
    }

    private static Response error(Response.Status status, String message) {
        return Response.status(status).entity(Map.of("message", message)).build();
    }
}
