package dk.panos.promofacie.kafka.model;

import dk.panos.promofacie.v2.MemberJoinEvent;
import dk.panos.promofacie.v2.MemberLeaveEvent;
import io.quarkus.kafka.client.serialization.ObjectMapperDeserializer;

public class MemberLeaveDeserializer extends ObjectMapperDeserializer<MemberLeaveEvent> {

	public MemberLeaveDeserializer() {
		super(MemberLeaveEvent.class);
	}
}