package dk.panos.promofacie;

import dk.panos.promofacie.kafka.UtxoTransactionConsumer;
import dk.panos.promofacie.redis.RedisVerificationService;
import dk.panos.promofacie.service.BlockchainVerificationService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class TransactionListener {
    private static final Logger log = LoggerFactory.getLogger(TransactionListener.class);
    @Inject
    RedisVerificationService verificationService;
    @Inject
    BlockchainVerificationService blockchainVerificationService;
    @Inject
    UtxoTransactionConsumer utxoTransactionConsumer;

//    @Scheduled(every = "15m")
    void listen() {
//        try {
//            Set<Verification> items = verificationService.process();
//            log.debug("I have the following items for verification {}", items);
//
//            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
//                List<Callable<Void>> tasks = new ArrayList<>();
//                for (Verification verification : items) {
//                    tasks.add(() -> {
//                        try {
//                            log.debug("Picked {} for verification", verification);
//                            boolean success = blockchainVerificationService.transactionExists(verification.address(), verification.discordId(), verification.dateTime());
//                            if (success) {
//                                log.debug("Sending success for {}", verification);
//                                kafkaVerificationService.sendSuccess(verification);
//                            } else {
//                                int itemsAdded = verificationService.queue(verification);
//                                if (itemsAdded > 0) {
//                                    log.debug("Retrying {}", verification);
//                                } else {
//                                    log.debug("Sending failure for {} due to reached max amount of tries", verification);
//                                    kafkaVerificationService.sendFailure(verification);
//                                }
//                            }
//                        } catch (Exception e) {
//                            log.error("Error processing verification {}", verification, e);
//                        }
//                        return null;
//                    });
//                }
//                executor.invokeAll(tasks);
//            }
//        } catch (Exception e) {
//            log.error("Failed to process verification items", e);
//        }
    }
}
