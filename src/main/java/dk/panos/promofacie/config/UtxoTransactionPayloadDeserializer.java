package dk.panos.promofacie.config;


import dk.panos.promofacie.kafka.model.UtxoTransactionPayload;
import io.quarkus.kafka.client.serialization.ObjectMapperDeserializer;

public class UtxoTransactionPayloadDeserializer extends ObjectMapperDeserializer<UtxoTransactionPayload> {
    public UtxoTransactionPayloadDeserializer() {
        super(UtxoTransactionPayload.class);
    }
}