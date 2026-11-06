package dk.panos.promofacie.service;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Queues a role evaluation when a member joins a guild, so verified wallet holders get their
 * roles immediately instead of waiting for their next on-chain change or a manual /sync-roles.
 * Requires the GUILD_MEMBERS gateway intent.
 */
@ApplicationScoped
public class GuildMemberJoinListener extends ListenerAdapter {
    private static final Logger log = LoggerFactory.getLogger(GuildMemberJoinListener.class);

    @Inject
    JDA jda;

    @Inject
    RoleEvaluationService roleEvaluationService;

    void onStart(@Observes StartupEvent ev) {
        if (jda != null) {
            jda.addEventListener(this);
            log.info("[GuildMemberJoin] Member join listener attached to JDA");
        }
    }

    @Override
    public void onGuildMemberJoin(GuildMemberJoinEvent event) {
        if (event.getUser().isBot()) {
            return;
        }
        String guildId = event.getGuild().getId();
        String discordId = event.getUser().getId();
        try {
            roleEvaluationService.enqueueEvaluationOnMemberJoin(guildId, discordId);
        } catch (Exception e) {
            log.error("[GuildMemberJoin] Failed to enqueue evaluation for member {} joining guild {}", discordId, guildId, e);
        }
    }
}
