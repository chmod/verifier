package dk.panos.promofacie.service;

import dk.panos.promofacie.db.GuildRoleRule;
import dk.panos.promofacie.db.RuleTraitCriteria;
import dk.panos.promofacie.db.UserAssetInventory;
import dk.panos.promofacie.db.Wallet;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;

@ApplicationScoped
public class RoleEvaluationService {

    private static final Logger log = LoggerFactory.getLogger(RoleEvaluationService.class);

    private final JDA jda;

    @Inject
    public RoleEvaluationService(JDA jda) {
        this.jda = jda;
    }

    @Transactional
    public void evaluateRoles(String stakeAddress, Set<String> changedPolicies) {
        if (changedPolicies == null || changedPolicies.isEmpty()) {
            return;
        }

        Wallet wallet = Wallet.find("address = ?1", stakeAddress).firstResult();
        if (wallet == null) {
            log.warn("[RoleEvaluation] Stake address {} is not linked to any user — skipping role check", stakeAddress);
            return;
        }

        String discordId = wallet.getDiscordId();
        log.info("[RoleEvaluation] Evaluating roles for user {} due to changes in policies: {}", discordId, changedPolicies);

        // Fetch all wallets for the user
        List<Wallet> userWallets = Wallet.list("discordId = ?1", discordId);
        List<String> walletAddresses = userWallets.stream().map(Wallet::getAddress).toList();

        // Fetch rules matching the changed policies
        List<GuildRoleRule> rules = GuildRoleRule.list("policyId in ?1", changedPolicies);
        log.info("[RoleEvaluation] Found {} rule(s) matching changed policies for user {}", rules.size(), discordId);

        for (GuildRoleRule rule : rules) {
            evaluateRuleForUser(discordId, walletAddresses, rule);
        }
    }

    private void evaluateRuleForUser(String discordId, List<String> walletAddresses, GuildRoleRule rule) {
        try {
            // Fetch all assets of this policy ID for the user's wallets
            List<UserAssetInventory> inventoryItems = UserAssetInventory.list(
                    "id.stakeAddress in ?1 and id.policyId = ?2",
                    walletAddresses,
                    rule.policyId
            );

            long matchingQuantity = 0;
            for (UserAssetInventory item : inventoryItems) {
                boolean matchesAllCriteria = true;
                if (rule.criteria != null) {
                    for (RuleTraitCriteria criterion : rule.criteria) {
                        String traitValue = item.traits != null ? item.traits.get(criterion.traitKey) : null;
                        if (traitValue == null || !traitValue.equalsIgnoreCase(criterion.traitValue)) {
                            matchesAllCriteria = false;
                            break;
                        }
                    }
                }
                if (matchesAllCriteria) {
                    matchingQuantity += item.quantity;
                }
            }

            boolean meetsRule = matchingQuantity >= rule.minQuantity;
            log.info("[RoleEvaluation] User {} has matching quantity {} for policy {} (required: {})", 
                    discordId, matchingQuantity, rule.policyId, rule.minQuantity);

            Guild guild = jda.getGuildById(rule.guildId);
            if (guild == null) {
                log.warn("[RoleEvaluation] Guild {} not found for rule {}", rule.guildId, rule.id);
                return;
            }

            Member member = guild.getMemberById(discordId);
            if (member == null) {
                log.debug("[RoleEvaluation] Member {} not found in guild {}", discordId, rule.guildId);
                return;
            }

            Role role = guild.getRoleById(rule.roleId);
            if (role == null) {
                log.warn("[RoleEvaluation] Role {} not found in guild {}", rule.roleId, rule.guildId);
                return;
            }

            boolean hasRole = member.getRoles().contains(role);

            if (meetsRule && !hasRole) {
                log.info("[RoleEvaluation] Adding role {} to user {} in guild {}", rule.roleId, discordId, rule.guildId);
                guild.addRoleToMember(member, role).queue(
                        success -> log.info("[RoleEvaluation] Successfully added role {} to user {}", rule.roleId, discordId),
                        failure -> log.error("[RoleEvaluation] Failed to add role {} to user {}", rule.roleId, discordId, failure)
                );
            } else if (!meetsRule && hasRole) {
                log.info("[RoleEvaluation] Removing role {} from user {} in guild {}", rule.roleId, discordId, rule.guildId);
                guild.removeRoleFromMember(member, role).queue(
                        success -> log.info("[RoleEvaluation] Successfully removed role {} from user {}", rule.roleId, discordId),
                        failure -> log.error("[RoleEvaluation] Failed to remove role {} from user {}", rule.roleId, discordId, failure)
                );
            }
        } catch (Exception e) {
            log.error("[RoleEvaluation] Failed to evaluate rule {} for user {}", rule.id, discordId, e);
        }
    }
}
