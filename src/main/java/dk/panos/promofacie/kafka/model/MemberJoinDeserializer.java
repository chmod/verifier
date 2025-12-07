package dk.panos.promofacie.kafka.model;

import dk.panos.promofacie.v2.MemberJoinEvent;
import dk.panos.promofacie.v2.RoleUpdateEvent;
import io.quarkus.kafka.client.serialization.ObjectMapperDeserializer;

public class MemberJoinDeserializer extends ObjectMapperDeserializer<MemberJoinEvent> {

	public MemberJoinDeserializer() {
		super(MemberJoinEvent.class);
	}
}