package dk.panos.promofacie.v2;

import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;

@ApplicationScoped
public class MemberLeaveConsumer {

    @Inject
    DiscordService discordService;

    private static final Logger LOG = Logger.getLogger(MemberLeaveConsumer.class);

    @Incoming("member-leave")
    @RunOnVirtualThread
    public void processMemberLeave(MemberLeaveEvent event) {
        LOG.info("Processing member leave event for user " + event.discordId() + " in guild " + event.guildId());
        discordService.handleMemberLeave(event.guildId(), event.discordId());
    }
}