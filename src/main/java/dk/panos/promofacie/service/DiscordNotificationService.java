package dk.panos.promofacie.service;

import dk.panos.promofacie.db.Notification;
import dk.panos.promofacie.db.NotificationChannel;
import dk.panos.promofacie.kafka.model.TransactionMessage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.smallrye.jwt.build.Jwt;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Base64;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

@ApplicationScoped
public class DiscordNotificationService {
    private static final Logger log = LoggerFactory.getLogger(DiscordNotificationService.class);

    private final JDA jda;

    @RestClient
    DashboardApiClient dashboardApiClient;

    @ConfigProperty(name = "dashboard.jwt_secret")
    String secret;

    @Inject
    public DiscordNotificationService(JDA jda) {
        this.jda = jda;
    }

    @Transactional
    public void sendTransactionNotification(TransactionMessage message) {
        if (message.policyId() == null || message.policyId().isBlank()) {
            log.warn("[DiscordNotification] Received transaction message without policy ID, skipping notification");
            return;
        }

        try {
            // Query notification settings for the given policyId
            Notification notification = Notification.find("policyId", message.policyId()).firstResult();
            if (notification == null) {
                log.info("[DiscordNotification] No notification configuration found in database for policyId: {}",
                        message.policyId());
                return;
            }

            if (notification.channels == null || notification.channels.isEmpty()) {
                log.info("[DiscordNotification] Notification configuration has no channels configured for policyId: {}",
                        message.policyId());
                return;
            }

            for (NotificationChannel channel : notification.channels) {
                if (channel.type == NotificationChannel.ChannelType.DISCORD) {
                    sendDiscordChannelNotification(channel.guildId, channel.channelId, message);
                }
            }
        } catch (Exception e) {
            log.error("[DiscordNotification] Failed to query or dispatch notifications for policyId: {}",
                    message.policyId(), e);
        }
    }

    private void sendDiscordChannelNotification(String guildId, String channelId, TransactionMessage message) {
        try {
            Guild guild = jda.getGuildById(guildId);
            if (guild == null) {
                log.error("Could not find Discord Guild with ID: {}", guildId);
                return;
            }

            TextChannel channel = guild.getTextChannelById(channelId);
            if (channel == null) {
                log.error("Could not find Text Channel with ID: {} in Guild: {}", channelId, guild.getName());
                return;
            }

            String header = "**New Transaction Detected!**";
            if ("MINT".equals(message.type())) {
                header = "**New Mint Detected!** 🆕";
            } else if ("SALE".equals(message.type())) {
                header = "**New Sale Detected!** 🛒";
            } else if ("SELL".equals(message.type())) {
                header = "**New Sell Swap Detected!** 🔄";
            } else if ("LISTING".equals(message.type())) {
                header = "**New Listing Detected!** 🏷️";
            } else if ("OFFER".equals(message.type())) {
                header = "**New Collection Offer!** 🤝";
            }

            String assetName = message.name();
            if ("OFFER".equals(message.type())) {
                assetName = resolvePolicyName(message.policyId());
            }

            String formattedMessage = String.format("%s\n" +
                    "**Asset:** %s\n" +
                    "**Quantity:** %s\n" +
                    "**Price:** %s\n" +
                    "**View:** %s", header, assetName, message.quantity(), message.price(), message.url());

            channel.sendMessage(formattedMessage).queue(
                    success -> log.info("Successfully sent transaction notification to Discord channel: {}", channelId),
                    failure -> log.error("Failed to send message to Discord channel: {}", channelId, failure));
        } catch (Exception e) {
            log.error("Failed to send Discord notification to channel: {}", channelId, e);
        }
    }

    private String resolvePolicyName(String policyId) {
        if (policyId == null || policyId.isBlank()) {
            return "Unknown";
        }
        try {
            byte[] decodedSecret = Base64.getDecoder().decode(secret);
            SecretKey key = new SecretKeySpec(decodedSecret, "HMACSHA256");

            String jwt = Jwt.issuer("promofacie")
                    .audience("promofacie-dashboard")
                    .subject("promofacie-bot")
                    .expiresIn(Duration.of(1, ChronoUnit.HOURS))
                    .sign(key);

            List<Map<String, String>> policies = dashboardApiClient.fetchPolicies("Bearer " + jwt);
            if (policies != null) {
                for (Map<String, String> map : policies) {
                    if (map.containsKey(policyId)) {
                        return map.get(policyId);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to resolve policy name for policy ID: {}", policyId, e);
        }
        return policyId;
    }
}
