package dk.panos.promofacie.service;

import dk.panos.promofacie.kafka.model.TransactionMessage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class DiscordNotificationService {
    private static final Logger log = LoggerFactory.getLogger(DiscordNotificationService.class);

    private static final String GUILD_ID = "979324485792567357";
    private static final String CHANNEL_ID = "1516371490755579914";
    private static final String CHANNEL_ID_2 = "1254156786571935766";

    private final JDA jda;

    @Inject
    public DiscordNotificationService(JDA jda) {
        this.jda = jda;
    }

    public void sendTransactionNotification(TransactionMessage message) {
//        try {
//            Guild guild = jda.getGuildById(GUILD_ID);
//            if (guild == null) {
//                log.error("Could not find Discord Guild with ID: {}", GUILD_ID);
//                return;
//            }
//
//            TextChannel channel = guild.getTextChannelById(CHANNEL_ID);
//            if (channel == null) {
//                log.error("Could not find Text Channel with ID: {} in Guild: {}", CHANNEL_ID, guild.getName());
//                return;
//            }
//
//            String formattedMessage = String.format("**New Transaction Detected!**\n" +
//                    "**Asset:** %s\n" +
//                    "**Quantity:** %s\n" +
//                    "**View:** %s", message.name(), message.quantity(), message.url());
//
//            channel.sendMessage(formattedMessage).queue(
//                success -> log.info("Successfully sent transaction notification to Discord channel: {}", CHANNEL_ID),
//                failure -> log.error("Failed to send message to Discord channel: {}", CHANNEL_ID, failure)
//            );
//
//            channel = guild.getTextChannelById(CHANNEL_ID_2);
//            if (channel == null) {
//                log.error("Could not find Text Channel with ID: {} in Guild: {}", CHANNEL_ID_2, guild.getName());
//                return;
//            }
//            channel.sendMessage(formattedMessage).queue(
//                success -> log.info("Successfully sent transaction notification to Discord channel: {}", CHANNEL_ID_2),
//                failure -> log.error("Failed to send message to Discord channel: {}", CHANNEL_ID_2, failure)
//            );
//        } catch (Exception e) {
//            log.error("Failed to process transaction notification to Discord", e);
//        }
    }
}
