package dk.panos.promofacie.controller;

import dk.panos.promofacie.controller.model.NonceEntry;
import dk.panos.promofacie.controller.model.VerifyRequest;
import dk.panos.promofacie.db.Chain;
import dk.panos.promofacie.db.Wallet;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.panache.common.Parameters;
import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import org.cardanofoundation.cip30.AddressFormat;
import org.cardanofoundation.cip30.CIP30Verifier;
import org.cardanofoundation.cip30.Cip30VerificationResult;
import org.cardanofoundation.cip30.MessageFormat;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static dk.panos.promofacie.db.Chain.CARDANO;

@Path("/api/wallet")
@Authenticated
public class WalletVerificationResource {
    @Inject
    JsonWebToken jwt;
    private final ConcurrentHashMap<String, NonceEntry> nonces = new ConcurrentHashMap<>();

    @POST
    @Path("/challenge")
    public Response challenge() {
        String discordId = jwt.getClaim("discord_id");

        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        String nonce = HexFormat.of().formatHex(bytes);

        nonces.put(discordId, new NonceEntry(nonce, Instant.now().plusSeconds(300)));
        return Response.ok(Map.of("nonce", nonce)).build();
    }

    @POST
    @Path("/verify")
    @Transactional
    public Response verify(VerifyRequest req) {
        String discordId = jwt.getClaim("discord_id");

        Optional<PanacheEntityBase> walletOptional = Wallet.find(
                "discordId = :discord_id and chain = :chain and address = :address",
                Parameters.with("discord_id", discordId)
                        .and("chain", CARDANO)
                        .and("address", req.stakeAddress())
        ).firstResultOptional();
        if (walletOptional.isEmpty())
            return Response.status(409).build();

        NonceEntry entry = nonces.remove(discordId);
        if (entry == null || entry.isExpired())
            return Response.status(400).entity(Map.of("message", "Nonce missing or expired")).build();

        if (!verifyWalletOwnership(req.stakeAddress(), req.signature(), req.key(), entry.nonce()))
            return Response.status(400).entity(Map.of("message", "Signature verification failed")).build();

        Wallet wallet = new Wallet();
        wallet.setAddress(req.stakeAddress());
        wallet.setDiscordId(discordId);
        wallet.setChain(CARDANO);
        wallet.persist();
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