package dk.promofacie.wallet_verification.kafka.model;

import io.quarkus.kafka.client.serialization.ObjectMapperDeserializer;

public class VerificationDeserializer extends ObjectMapperDeserializer<VerificationOutcome> {

	public VerificationDeserializer() {
		super(VerificationOutcome.class);
	}
}