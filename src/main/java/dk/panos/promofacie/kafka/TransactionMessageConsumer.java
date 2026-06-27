package dk.panos.promofacie.kafka;

import dk.panos.promofacie.kafka.model.TransactionMessage;
import dk.panos.promofacie.service.DiscordNotificationService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class TransactionMessageConsumer {
    private static final Logger log = LoggerFactory.getLogger(TransactionMessageConsumer.class);

    @Inject
    DiscordNotificationService notificationService;

    @Incoming("transactions")
    public void consumeTransaction(TransactionMessage message) {
        if (message == null) {
            log.warn("[TransactionConsumer] Received null message");
            return;
        }
        log.info("[TransactionConsumer] Consumed transaction: quantity={}, name={}, url={}",
                message.quantity(), message.name(), message.url());
        
        notificationService.sendTransactionNotification(message);
    }
}
