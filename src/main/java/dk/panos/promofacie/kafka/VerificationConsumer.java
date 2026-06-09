//package dk.panos.promofacie.kafka;
//
//import dk.panos.promofacie.db.Chain;
//import dk.panos.promofacie.db.Wallet;
//import dk.panos.promofacie.kafka.model.VerificationOutcome;
//import io.quarkus.panache.common.Parameters;
//import jakarta.transaction.Transactional;
//import jakarta.enterprise.context.ApplicationScoped;
//import org.eclipse.microprofile.reactive.messaging.Incoming;
//
//@ApplicationScoped
//public class VerificationConsumer {
//    @Incoming("verifier-rdx")
//    @Transactional
//    public void handleVerificationOutcome(VerificationOutcome verificationOutcome) {
//        if ("WALLET_VERIFICATION".equals(verificationOutcome.getPurpose()) && verificationOutcome.isOutcome()) {
//            Wallet wallet = Wallet.find("discordId = :discord_id and chain = :chain", Parameters.with("discord_id", verificationOutcome.getUserId()).and("chain", Chain.RADIX))
//                    .firstResult();
//            if (wallet == null) {
//                wallet = new Wallet();
//                wallet.setChain(Chain.RADIX);
//                wallet.setDiscordId(verificationOutcome.getUserId());
//            }
//            wallet.setAddress(verificationOutcome.getAddress());
//            wallet.persist();
//        }
//    }
//}
