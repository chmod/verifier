package dk.panos.promofacie.kafka.model;

import dk.panos.promofacie.v2.AddressVerifiedEvent;
import dk.panos.promofacie.v2.RoleUpdateEvent;
import io.quarkus.kafka.client.serialization.ObjectMapperDeserializer;

public class RoleUpdateDeserializer extends ObjectMapperDeserializer<RoleUpdateEvent> {

	public RoleUpdateDeserializer() {
		super(RoleUpdateEvent.class);
	}
}