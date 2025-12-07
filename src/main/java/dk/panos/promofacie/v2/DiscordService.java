package dk.panos.promofacie.v2;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import org.jboss.logging.Logger;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@ApplicationScoped
public class DiscordService {
    @Inject
    CacheService cacheService;

    @Inject
    JDA discordAPI;

    @Inject
    VerificationService verificationService;

    private static final Logger LOG = Logger.getLogger(DiscordService.class);

    /**
     * Called when a user joins a guild
     * Check if they're already verified and auto-assign roles
     */
    public void handleMemberJoin(String guildId, String userId) {
        try {
            // Check if user has a verified address
            String address = cacheService.getAddress(userId);
            if (address == null) {
                LOG.info("User " + userId + " joined guild " + guildId + " but is not verified");
                return;
            }

            // Check if guild is configured
            RoleConfig config = cacheService.getRoleConfig(guildId);
            if (config == null) {
                LOG.info("User " + userId + " joined guild " + guildId + " but guild is not configured");
                return;
            }

            LOG.info("Verified user " + userId + " joined guild " + guildId + ", auto-verifying...");

            // Trigger verification for this user in the new guild
            VerificationRequest request = new VerificationRequest(
                    userId,
                    guildId,
                    address,
                    System.currentTimeMillis()
            );

            VerificationResult result = verificationService.verifyUser(request);

            if (result.success()) {
                LOG.info("Auto-verified user " + userId + " in guild " + guildId + " with " + result.nftCount() + " NFTs");
            } else {
                LOG.info("User " + userId + " has no NFTs for guild " + guildId);
            }

        } catch (Exception e) {
            LOG.error("Failed to handle member join for user " + userId + " in guild " + guildId, e);
        }
    }

    /**
     * Called when a user leaves a guild
     * Clean up guild-user relationship
     */
    public void handleMemberLeave(String guildId, String userId) {
        try {
            LOG.info("User " + userId + " left guild " + guildId + ", cleaning up...");

            // Remove user from guild set
            cacheService.unlinkUserFromGuild(userId, guildId);

            LOG.info("Cleaned up guild membership for user " + userId);

        } catch (Exception e) {
            LOG.error("Failed to handle member leave for user " + userId + " in guild " + guildId, e);
        }
    }

    public void updateMemberRoles(String guildId, String userId, List<String> newRoleIds) {
        try {
            Guild guild = discordAPI.getGuildById(guildId);
            if (guild == null) {
                LOG.error("Guild " + guildId + " not found");
                return;
            }

            Member member = guild.retrieveMemberById(userId).complete();
            if (member == null) {
                LOG.error("Member " + userId + " not found in guild " + guildId);
                return;
            }

            // Get current NFT-related roles
            Set<String> currentNftRoles = member.getRoles().stream()
                    .filter(role -> role.getName().contains("NFT") || role.getName().contains("Holder"))
                    .map(Role::getId)
                    .collect(Collectors.toSet());

            Set<String> newRoleSet = new HashSet<>(newRoleIds);

            // Calculate roles to add and remove
            List<Role> rolesToAdd = newRoleSet.stream()
                    .filter(roleId -> !currentNftRoles.contains(roleId))
                    .map(guild::getRoleById)
                    .filter(Objects::nonNull)
                    .toList();

            List<Role> rolesToRemove = currentNftRoles.stream()
                    .filter(roleId -> !newRoleSet.contains(roleId))
                    .map(guild::getRoleById)
                    .filter(Objects::nonNull)
                    .toList();

            // Remove roles first (important for role hierarchies)
            if (!rolesToRemove.isEmpty()) {
                LOG.info("Removing roles from " + userId + ": " + rolesToRemove.stream().map(Role::getName).toList());
                guild.modifyMemberRoles(member, List.of(), rolesToRemove).queue();
            }

            // Add new roles
            if (!rolesToAdd.isEmpty()) {
                LOG.info("Adding roles to " + userId + ": " + rolesToAdd.stream().map(Role::getName).toList());
                guild.modifyMemberRoles(member, rolesToAdd, List.of()).queue();
            }

            LOG.info("Updated roles for user " + userId + " in guild " + guildId);

        } catch (Exception e) {
            LOG.error("Failed to update roles for user " + userId, e);
            throw new RuntimeException("Role update failed", e);
        }
    }
}