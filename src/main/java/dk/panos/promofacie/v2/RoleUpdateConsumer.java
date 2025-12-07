package dk.panos.promofacie.v2;

import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Incoming;


@ApplicationScoped
public class RoleUpdateConsumer {

    @Inject
    DiscordService discordService;

    @Incoming("role-updates")
    @RunOnVirtualThread
    public void processRoleUpdate(RoleUpdateEvent event) {
        discordService.updateMemberRoles(
                event.guildId(),
                event.discordId(),
                event.roles()
        );
    }
}