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
import java.util.ArrayList;
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

    void onStart(@jakarta.enterprise.event.Observes io.quarkus.runtime.StartupEvent ev) {
        log.info("[DiscordNotification] Testing Dashboard API connection on startup...");
        try {
            String testResult = resolvePolicyName("6c79a125d11bdf0cd0323dde9ce28d5ea201a259159205d7539c41d4");
            log.info("[DiscordNotification] Startup test finished. Result for 'test_policy_id_startup': {}",
                    testResult);
        } catch (Exception e) {
            log.error("[DiscordNotification] Startup test failed!", e);
        }
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
                if ("ASSET_OFFER".equals(message.offerType())) {
                    header = "**New Item Offer!** 🤝";
                } else {
                    header = "**New Collection Offer!** 🤝";
                }
            }

            String assetName = message.name();
            if ("OFFER".equals(message.type()) && (assetName == null || assetName.isBlank())) {
                assetName = resolvePolicyName(message.policyId());
            }

            String cardanoscanUrl = (message.txHash() != null && !message.txHash().isBlank())
                    ? "https://cardanoscan.io/transaction/" + message.txHash()
                    : null;

            StringBuilder sb = new StringBuilder();
            sb.append("-# ").append(header).append("\n\n");
            sb.append("🎯 **Asset:** ").append(assetName != null ? assetName : "Unknown").append("\n");
            sb.append("🤓 **Quantity:** ").append(message.quantity() != null ? message.quantity() : "1").append("\n");
            sb.append("💰 **Price:** ").append(message.price() != null ? message.price() : "Unknown");

            String formattedMessage = sb.toString().trim();

            List<net.dv8tion.jda.api.interactions.components.buttons.Button> buttons = new ArrayList<>();
            if (message.url() != null && !message.url().isBlank()) {
                buttons.add(net.dv8tion.jda.api.interactions.components.buttons.Button.link(message.url(), "View on Pool.pm")
                        .withEmoji(net.dv8tion.jda.api.entities.emoji.Emoji.fromUnicode("👀")));
            }
            if (cardanoscanUrl != null) {
                buttons.add(net.dv8tion.jda.api.interactions.components.buttons.Button.link(cardanoscanUrl, "View Transaction")
                        .withEmoji(net.dv8tion.jda.api.entities.emoji.Emoji.fromUnicode("🔗")));
            }

            boolean isWayup = "Wayup".equalsIgnoreCase(message.platform()) ||
                    (message.url() != null && message.url().toLowerCase().contains("wayup"));
            if (isWayup) {
                if (message.policyId() != null && !message.policyId().isBlank() &&
                        message.requestedAssetNameHex() != null && !message.requestedAssetNameHex().isBlank()) {
                    String wayupUrl = "https://www.wayup.io/collection/" + message.policyId() + "/asset/" + message.requestedAssetNameHex();
                    buttons.add(net.dv8tion.jda.api.interactions.components.buttons.Button.link(wayupUrl, "View on WayUp")
                            .withEmoji(net.dv8tion.jda.api.entities.emoji.Emoji.fromUnicode("🛍️")));
                    log.info("[Discord] Identified platform as WayUp for txHash={} — attached WayUp button: {}", message.txHash(), wayupUrl);
                } else {
                    log.warn("[Discord] Platform is WayUp but missing policyId or requestedAssetNameHex for txHash={}, cannot build WayUp button", message.txHash());
                }
            } else {
                log.info("[Discord] Platform is not WayUp (platform={}) for txHash={}", message.platform(), message.txHash());
            }

            net.dv8tion.jda.api.utils.messages.MessageCreateBuilder messageBuilder = new net.dv8tion.jda.api.utils.messages.MessageCreateBuilder()
                    .setContent(formattedMessage);
            if (!buttons.isEmpty()) {
                messageBuilder.setActionRow(buttons);
            }

            channel.sendMessage(messageBuilder.build()).queue(
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
            String jwt = Jwt.issuer("promofacie")
                    .audience("promofacie-dashboard")
                    .subject("promofacie-bot")
                    .expiresIn(Duration.of(1, ChronoUnit.HOURS))
                    .signWithSecret(secret);

            Map<String, String> policies = dashboardApiClient.fetchPolicies("Bearer " + jwt);
            if (policies != null && policies.containsKey(policyId)) {
                return policies.get(policyId);
            }
        } catch (Exception e) {
            log.error("Failed to resolve policy name for policy ID: {}", policyId, e);
        }
        return policyId;
    }
}
