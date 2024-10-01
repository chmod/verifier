package dk.panos.promofacie.kafka.model;

import io.quarkus.kafka.client.serialization.ObjectMapperDeserializer;

public class VerificationDeserializer extends ObjectMapperDeserializer<VerificationOutcome> {

	public VerificationDeserializer() {
		super(VerificationOutcome.class);
	}
}