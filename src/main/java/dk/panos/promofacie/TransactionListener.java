package dk.panos.promofacie;

import dk.panos.promofacie.kafka.KafkaVerificationService;
import dk.panos.promofacie.redis.RedisVerificationService;
import dk.panos.promofacie.redis.redis_model.Verification;
import dk.panos.promofacie.service.BlockchainVerificationService;
import io.quarkus.arc.profile.IfBuildProfile;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.tuples.Tuple2;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.function.Function;

@ApplicationScoped
//@IfBuildProfile("prod")
public class TransactionListener {
    private static Logger log = LoggerFactory.getLogger(TransactionListener.class);
    @Inject
    RedisVerificationService verificationService;
    @Inject
    BlockchainVerificationService blockchainVerificationService;
    @Inject
    KafkaVerificationService kafkaVerificationService;

    @Scheduled(every = "5m")
    Uni<Void> listen() {
        return verificationService.process()
                .onItem().invoke(items -> log.debug("I have the following items for verification {}", items))
                .toMulti()
                .onItem()
                .transformToIterable(Function.identity())
                .onItem()
                .invoke(verification -> log.debug("Picked {} for verification", verification))
                .onItem()
                .transformToUni(verification ->
                        blockchainVerificationService.transactionExists(verification.address(), verification.discordId(), verification.dateTime())
                                .onFailure().invoke(err -> log.error(err.getMessage()))
                                .onFailure().retry().withBackOff(Duration.ofMillis(100))
                                .atMost(100)
                                .map(outcome -> Tuple2.of(verification, outcome))
                ).merge(4).onItem()
                .transformToUni(outcome -> {
                    Verification verification = outcome.getItem1();
                    boolean success = outcome.getItem2();
                    if (success) {
                        log.debug("Sending success for {}", verification);
                        return kafkaVerificationService.sendSuccess(verification);
                    } else {
                        return verificationService.queue(verification)
                                .onItem().transformToUni(itemsAdded -> {
                                    if (itemsAdded > 0) {
                                        log.debug("Retrying {}", verification);
                                        return Uni.createFrom().voidItem();
                                    } else {
                                        log.debug("Sending failure for {} due to reached max amount of tries", verification);
                                        return kafkaVerificationService.sendFailure(verification);
                                    }
                                });
                    }
                })
                .concatenate().collect().asList().replaceWithVoid();
    }
}
