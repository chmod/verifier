package dk.panos.promofacie.config;

import dk.panos.promofacie.kafka.model.TransactionMessage;
import io.quarkus.kafka.client.serialization.ObjectMapperDeserializer;

public class TransactionMessageDeserializer extends ObjectMapperDeserializer<TransactionMessage> {
    public TransactionMessageDeserializer() {
        super(TransactionMessage.class);
    }
}
