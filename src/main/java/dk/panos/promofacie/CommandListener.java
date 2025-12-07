package dk.panos.promofacie;

import dk.panos.promofacie.redis.RedisVerificationService;
import dk.panos.promofacie.redis.redis_model.Verification;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import net.dv8tion.jda.api.EmbedBuilder;
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
            if (!event.getOption("address").getAsString().startsWith("account_rdx")) {
                event.getHook().editOriginal("That doesn't seem a valid rdx address").queue();
                return;
            }
            verificationService.queue(new Verification(event.getOption("address").getAsString(), event.getUser().getId(), ZonedDateTime.now(ZoneOffset.UTC).minusMinutes(5)));
            event.getHook().editOriginal("Please send 0 XRD to the address below with your verification ID as the message:")
                    .queue(success -> {
                        EmbedBuilder embed = new EmbedBuilder()
                                .setTitle("Verification Details")
                                .setColor(0x00FF00)
                                .addField("Send to Address", "```VerifyNFT.xrd```", false)
                                .addField("Verification ID (use as tx message)", "```" + event.getUser().getId() + "```", false)
                                .setFooter("Tap the code blocks above to copy");

                        event.getHook().sendMessageEmbeds(embed.build())
                                .setEphemeral(true)
                                .queue();
                    });


            log.debug("Adding for verify: {} {}", event.getOption("address").getAsString(), event.getUser().getId());
        }

    }


}
