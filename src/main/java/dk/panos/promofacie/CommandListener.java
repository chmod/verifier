package dk.panos.promofacie;

import dk.panos.promofacie.redis.RedisVerificationService;
import dk.panos.promofacie.redis.redis_model.Verification;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
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
    private static final Logger log = LoggerFactory.getLogger(CommandListener.class);

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (event.getName().equals("verify")) {
            event.deferReply(true).queue();
            if(!event.getOption("address").getAsString().startsWith("account_rdx")) {
                event.getHook().editOriginal("That doesn't seem a valid rdx address").queue();
                return;
            }
            verificationService.queue(new Verification(event.getOption("address").getAsString(), event.getUser().getId(), ZonedDateTime.now(ZoneOffset.UTC).minusMinutes(5)))
                    .onItem().invoke(tries -> event.getHook().editOriginal("Please send any amount to account_rdx129wl7trqd7ttwfd2nx9tf3qgag6n2e5tjs3t9cfgslpp3yxh526e2u but set message: "+event.getUser().getId()).queue())
                    .subscribe().with(x-> log.debug("Adding for verify: {} {}", event.getOption("address").getAsString(), event.getUser().getId()));
        }

    }


}
