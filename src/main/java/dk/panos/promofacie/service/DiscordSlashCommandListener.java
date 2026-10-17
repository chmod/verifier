package dk.panos.promofacie.service;

import dk.panos.promofacie.db.PendingUserEvaluation;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

@ApplicationScoped
public class DiscordSlashCommandListener extends ListenerAdapter {
    private static final Logger log = LoggerFactory.getLogger(DiscordSlashCommandListener.class);

    public static final String COMMAND_RECALCULATE = "recalculate-roles";
    public static final String COMMAND_SYNC = "sync-roles";

    @Inject
    JDA jda;

    void onStart(@Observes StartupEvent ev) {
        if (jda != null) {
            try {
                log.info("[DiscordSlashCommand] Attaching slash command listener to JDA...");
                jda.addEventListener(this);
                jda.updateCommands().addCommands(
                        Commands.slash(COMMAND_RECALCULATE, "Queue a full role recalculation for a user in this server")
                                .addOption(OptionType.USER, "user", "User to recalculate roles for (defaults to yourself)", false),
                        Commands.slash(COMMAND_SYNC, "Queue a full role recalculation for a user in this server")
                                .addOption(OptionType.USER, "user", "User to recalculate roles for (defaults to yourself)", false)
                ).queue(
                        success -> log.info("[DiscordSlashCommand] Successfully registered slash commands with Discord: [{}, {}]", COMMAND_RECALCULATE, COMMAND_SYNC),
                        failure -> log.error("[DiscordSlashCommand] Failed to register slash commands with Discord", failure)
                );
            } catch (Exception e) {
                log.error("[DiscordSlashCommand] Failed during slash command initialization: {}", e.getMessage(), e);
            }
        }
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        String commandName = event.getName();
        if (!COMMAND_RECALCULATE.equals(commandName) && !COMMAND_SYNC.equals(commandName)) {
            return;
        }

        Guild guild = event.getGuild();
        if (guild == null) {
            event.reply("❌ This command can only be used within a server.").setEphemeral(true).queue();
            return;
        }

        Member callingMember = event.getMember();
        OptionMapping userOption = event.getOption("user");
        User targetUser = userOption != null ? userOption.getAsUser() : event.getUser();

        // If targeting another member, require MANAGE_ROLES or ADMINISTRATOR permission
        if (!targetUser.getId().equals(event.getUser().getId())) {
            if (callingMember == null || (!callingMember.hasPermission(Permission.MANAGE_ROLES) && !callingMember.hasPermission(Permission.ADMINISTRATOR))) {
                event.reply("❌ You need **Manage Roles** permissions to recalculate roles for another member.").setEphemeral(true).queue();
                return;
            }
        }

        try {
            enqueueUserEvaluation(guild.getId(), targetUser.getId());
            event.reply("⏳ Role recalculation has been queued for " + targetUser.getAsMention() + ".")
                    .setEphemeral(true)
                    .queue();
        } catch (Exception e) {
            log.error("[DiscordSlashCommand] Failed to enqueue role recalculation for user {} in guild {}",
                    targetUser.getId(), guild.getId(), e);
            event.reply("❌ Failed to queue role recalculation: " + e.getMessage())
                    .setEphemeral(true)
                    .queue();
        }
    }

    @Transactional
    public void enqueueUserEvaluation(String guildId, String discordId) {
        long pendingCount = PendingUserEvaluation.count(
                "guildId = ?1 and discordId = ?2 and (status = 'PENDING' or status = 'PROCESSING')",
                guildId, discordId
        );
        if (pendingCount == 0) {
            PendingUserEvaluation eval = new PendingUserEvaluation();
            eval.guildId = guildId;
            eval.discordId = discordId;
            eval.status = "PENDING";
            eval.retryCount = 0;
            eval.createdAt = Instant.now();
            eval.updatedAt = Instant.now();
            eval.persist();
            log.info("[DiscordSlashCommand] Enqueued PendingUserEvaluation for user {} in guild {}", discordId, guildId);
        } else {
            log.info("[DiscordSlashCommand] Evaluation already pending/processing for user {} in guild {}", discordId, guildId);
        }
    }
}
