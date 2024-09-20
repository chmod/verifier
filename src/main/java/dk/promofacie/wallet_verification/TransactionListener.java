package dk.promofacie.wallet_verification;

import dk.promofacie.wallet_verification.kafka.KafkaVerificationService;
import dk.promofacie.wallet_verification.redis.RedisVerificationService;
import dk.promofacie.wallet_verification.redis.redis_model.Verification;
import dk.promofacie.wallet_verification.service.BlockchainVerificationService;
import io.quarkus.arc.profile.IfBuildProfile;
import io.quarkus.runtime.StartupEvent;
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
@IfBuildProfile("prod")
public class TransactionListener {
    private static Logger log = LoggerFactory.getLogger(TransactionListener.class);
    @Inject
    RedisVerificationService verificationService;
    @Inject
    BlockchainVerificationService blockchainVerificationService;
    @Inject
    KafkaVerificationService kafkaVerificationService;

    public void listen(@Observes StartupEvent ev) {
        Multi<Tuple2<Verification, Boolean>> transactionExistsCheck = verificationService.process()
                .onItem().invoke(items -> log.debug("I have the following items for verification {}", items))
                .toMulti()
                .onItem()
                .transformToIterable(Function.identity())
                .onItem()
                .invoke(verification -> log.debug("Picked {} for verification", verification))
                .onItem()
                .transformToUni(verification ->
                        blockchainVerificationService.transactionExists(verification.address(), verification.discordId(), verification.dateTime())
                                .onFailure().retry().withBackOff(Duration.ofMillis(100))
                                .atMost(100)
                                .map(outcome -> Tuple2.of(verification, outcome))
                )
                .merge();

        Multi.createFrom().ticks().every(Duration.of(5, ChronoUnit.MINUTES))
                .onItem()
                .invoke(tick -> log.debug("Checking for new transactions")).onItem()
                .transformToMulti(tick -> transactionExistsCheck)
                .merge()
                .onItem()
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
                }).merge()
                .onFailure().retry().indefinitely()
                .subscribe().with(nothing -> {
                });
    }
}
