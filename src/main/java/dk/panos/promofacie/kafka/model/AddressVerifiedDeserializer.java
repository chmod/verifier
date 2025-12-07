package dk.panos.promofacie.kafka.model;

import dk.panos.promofacie.v2.AddressVerifiedEvent;
import io.quarkus.kafka.client.serialization.ObjectMapperDeserializer;

public class AddressVerifiedDeserializer extends ObjectMapperDeserializer<AddressVerifiedEvent> {

	public AddressVerifiedDeserializer() {
		super(AddressVerifiedEvent.class);
	}
}