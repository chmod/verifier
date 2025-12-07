package dk.panos.promofacie.v2;

import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;

@ApplicationScoped
public class MemberJoinConsumer {

    @Inject
    DiscordService discordService;

    private static final Logger LOG = Logger.getLogger(MemberJoinConsumer.class);

    @Incoming("member-join")
    @RunOnVirtualThread
    public void processMemberJoin(MemberJoinEvent event) {
        LOG.info("Processing member join event for user " + event.discordId() + " in guild " + event.guildId());
        discordService.handleMemberJoin(event.guildId(), event.discordId());
    }
}