package dk.panos.promofacie;

import dk.panos.promofacie.redis.RedisVerificationService;
import dk.panos.promofacie.redis.redis_model.Verification;
import dk.panos.promofacie.v2.EventProducer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRemoveEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;

@ApplicationScoped
public class CommandListener extends ListenerAdapter {
    @Inject
    RedisVerificationService verificationService;
    @Inject
    EventProducer producer;
    private static final Logger log = LoggerFactory.getLogger(CommandListener.class);

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (event.getName().equals("verify")) {
            event.deferReply(true).queue();
            if(!event.getOption("address").getAsString().startsWith("account_rdx")) {
                event.getHook().editOriginal("That doesn't seem a valid rdx address").queue();
                return;
            }
            verificationService.queue(new Verification(event.getOption("address").getAsString(), event.getUser().getId(), ZonedDateTime.now(ZoneOffset.UTC).minusMinutes(5)));
            event.getHook().editOriginal("Please send 0 XRD to VerifyNFT.xrd but set message: "+event.getUser().getId()).queue();
            log.debug("Adding for verify: {} {}", event.getOption("address").getAsString(), event.getUser().getId());
        }

    }

    @Override
    public void onGuildMemberJoin(GuildMemberJoinEvent event) {
        String guildId = event.getGuild().getId();
        String userId = event.getUser().getId();

        log.info("User " + userId + " joined guild " + guildId);

        // Emit to Kafka - let consumer handle it asynchronously
        producer.emitMemberJoin(userId, guildId);
    }

    @Override
    public void onGuildMemberRemove(GuildMemberRemoveEvent event) {
        String guildId = event.getGuild().getId();
        String userId = event.getUser().getId();

        log.info("User " + userId + " left guild " + guildId);

        // Emit to Kafka - let consumer handle it asynchronously
        producer.emitMemberLeave(userId, guildId);
    }
}
