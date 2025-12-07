package dk.panos.promofacie.v2;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;

import java.util.List;

@ApplicationScoped
public class EventProducer {

    @Inject
    @Channel("role-updatesout")
    Emitter<RoleUpdateEvent> roleUpdateEmitter;

    @Inject
    @Channel("member-joinout")
    Emitter<MemberJoinEvent> memberJoinEmitter;

    @Inject
    @Channel("member-leaveout")
    Emitter<MemberLeaveEvent> memberLeaveEmitter;

    @Inject
    @Channel("address-verifiedout")
    Emitter<AddressVerifiedEvent> addressVerifiedEmitter;

    public void emitRoleUpdate(String discordId, String guildId, List<String> roles) {
        RoleUpdateEvent event = new RoleUpdateEvent(discordId, guildId, roles, System.currentTimeMillis());
        roleUpdateEmitter.send(event);
    }

    public void emitMemberJoin(String discordId, String guildId) {
        MemberJoinEvent event = new MemberJoinEvent(discordId, guildId, System.currentTimeMillis());
        memberJoinEmitter.send(event);
    }

    public void emitMemberLeave(String discordId, String guildId) {
        MemberLeaveEvent event = new MemberLeaveEvent(discordId, guildId, System.currentTimeMillis());
        memberLeaveEmitter.send(event);
    }

    public void emitAddressVerified(String discordId, String address) {
        AddressVerifiedEvent event = new AddressVerifiedEvent(discordId, address, System.currentTimeMillis());
        addressVerifiedEmitter.send(event);
    }
}
