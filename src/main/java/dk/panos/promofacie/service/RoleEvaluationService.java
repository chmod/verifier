package dk.panos.promofacie.service;

import dk.panos.promofacie.db.GuildRoleRule;
import dk.panos.promofacie.db.RuleTraitCriteria;
import dk.panos.promofacie.db.UserAssetInventory;
import dk.panos.promofacie.db.Wallet;
import dk.panos.promofacie.db.RoleSyncOutbox;
import dk.panos.promofacie.db.TargetState;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@ApplicationScoped
public class RoleEvaluationService {

    private static final Logger log = LoggerFactory.getLogger(RoleEvaluationService.class);

    @Inject
    JDA jda;

    /**
     * Upserts desired-state outbox rows keyed on (discord_id, guild_id, role_id). Requires
     * a matching unique constraint on that triple in the schema — ON CONFLICT will throw at
     * runtime otherwise. The event_slot CASE guard ensures an out-of-order/late-arriving
     * evaluation for an older slot can never clobber a row already updated by a newer one.
     */
    @Transactional
    public void enqueueRoleUpdates(String stakeAddress, Set<String> changedPolicies, long eventSlot) {
        if (changedPolicies == null || changedPolicies.isEmpty()) {
            return;
        }

        Wallet wallet = Wallet.find("address = ?1", stakeAddress).firstResult();
        if (wallet == null) {
            log.warn("[RoleEvaluation] Stake address {} is not linked to any user — skipping outbox enqueue", stakeAddress);
            return;
        }

        String discordId = wallet.getDiscordId();
        log.info("[RoleEvaluation] Enqueuing role updates for user {} (slot: {}) due to changed policies: {}",
                discordId, eventSlot, changedPolicies);

        List<Wallet> userWallets = Wallet.list("discordId = ?1", discordId);
        List<String> walletAddresses = userWallets.stream().map(Wallet::getAddress).toList();

        List<GuildRoleRule> matchedRules = GuildRoleRule.list("policyId in ?1", changedPolicies);
        log.info("[RoleEvaluation] Found {} matched rule(s) for changed policies", matchedRules.size());

        // Group the matched rules by (guildId, roleId) to execute a single aggregated evaluation per role
        Map<String, Set<String>> affectedRolesByGuild = matchedRules.stream()
                .collect(Collectors.groupingBy(
                        rule -> rule.guildId,
                        Collectors.mapping(rule -> rule.roleId, Collectors.toSet())
                ));

        for (Map.Entry<String, Set<String>> guildEntry : affectedRolesByGuild.entrySet()) {
            String guildId = guildEntry.getKey();
            for (String roleId : guildEntry.getValue()) {
                boolean meetsGroupedRules = evaluateRoleEligibility(discordId, walletAddresses, guildId, roleId);
                TargetState targetState = meetsGroupedRules ? TargetState.PRESENT : TargetState.ABSENT;

                upsertOutboxTask(discordId, guildId, roleId, targetState, eventSlot);
            }
        }
    }

    @Transactional
    public boolean evaluateRoleEligibility(String discordId, List<String> walletAddresses, String guildId, String roleId) {
        // Fetch all rules configured for this role in this guild eagerly
        List<GuildRoleRule> rules = getRulesForRole(guildId, roleId);
        if (rules.isEmpty()) {
            log.info("[RoleEvaluation] No rules configured for user {} in guild {} for role {} - evaluation compliance = false",
                    discordId, guildId, roleId);
            return false;
        }

        // Group rules by their ruleGroup ID (e.g. Group 1, Group 2)
        Map<Integer, List<GuildRoleRule>> groupedRules = rules.stream()
                .collect(Collectors.groupingBy(r -> r.ruleGroup));

        log.info("[RoleEvaluation] Evaluating role eligibility for user {} in guild {} for role {}. Found {} rule group(s).",
                discordId, guildId, roleId, groupedRules.size());

        // Outer level is OR: at least one group must be satisfied
        for (Map.Entry<Integer, List<GuildRoleRule>> entry : groupedRules.entrySet()) {
            int groupId = entry.getKey();
            List<GuildRoleRule> group = entry.getValue();

            log.info("[RoleEvaluation]   Evaluating rule group {} containing {} rule(s)", groupId, group.size());

            boolean isAndGroup = group.stream().anyMatch(r -> Boolean.TRUE.equals(r.isAnd));

            if (isAndGroup) {
                boolean groupSatisfied = true;
                for (GuildRoleRule rule : group) {
                    long ruleQty = getRuleMatchingQuantity(discordId, walletAddresses, rule);
                    boolean ruleCompliant = ruleQty >= rule.minQuantity;
                    log.info("[RoleEvaluation]     (AND Group) Rule id={} policy={} matchingQty={} (required={}) -> ruleCompliant={}",
                            rule.id, rule.policyId, ruleQty, rule.minQuantity, ruleCompliant);
                    if (!ruleCompliant) {
                        groupSatisfied = false;
                    }
                }
                if (groupSatisfied) {
                    log.info("[RoleEvaluation]   Rule group {} (AND) satisfied!", groupId);
                    return true; // Satisfied one of the OR pathways
                } else {
                    log.info("[RoleEvaluation]   Rule group {} (AND) NOT satisfied!", groupId);
                }
            } else {
                long groupTotalQty = 0;
                long groupRequiredQty = 0;

                for (GuildRoleRule rule : group) {
                    long ruleQty = getRuleMatchingQuantity(discordId, walletAddresses, rule);
                    groupTotalQty += ruleQty;
                    groupRequiredQty += rule.minQuantity;
                    log.info("[RoleEvaluation]     (SUM Group) Rule id={} policy={} matchingQty={} -> groupTotalQty={}, groupRequiredQty={}",
                            rule.id, rule.policyId, ruleQty, groupTotalQty, groupRequiredQty);
                }

                if (groupTotalQty >= groupRequiredQty) {
                    log.info("[RoleEvaluation]   Rule group {} (SUM) satisfied! Total matching quantity {} meets required quantity {}",
                            groupId, groupTotalQty, groupRequiredQty);
                    return true; // Satisfied one of the OR pathways
                } else {
                    log.info("[RoleEvaluation]   Rule group {} (SUM) NOT satisfied! Total matching quantity {} is less than required quantity {}",
                            groupId, groupTotalQty, groupRequiredQty);
                }
            }
        }

        log.info("[RoleEvaluation]   No rule groups satisfied for user {} in guild {} for role {}.",
                discordId, guildId, roleId);
        return false; // None of the groups were satisfied
    }

    @Transactional
    public void upsertOutboxTask(String discordId, String guildId, String roleId, TargetState targetState, long eventSlot) {
        if (!isOutboxTaskNeeded(discordId, guildId, roleId, targetState)) {
            log.debug("[RoleEvaluation] Skip upserting task for user={} guild={} role={} targetState={} (slot={}) as state is already in sync",
                    discordId, guildId, roleId, targetState, eventSlot);
            return;
        }

        RoleSyncOutbox.getEntityManager().createNativeQuery(
                        "INSERT INTO role_sync_outbox (discord_id, guild_id, role_id, target_state, status, event_slot, retry_count, created_at, updated_at) " +
                                "VALUES (:discordId, :guildId, :roleId, :targetState, 'PENDING', :eventSlot, 0, now(), now()) " +
                                 "ON CONFLICT (discord_id, guild_id, role_id) " +
                                "DO UPDATE SET " +
                                "    target_state = CASE WHEN :eventSlot >= role_sync_outbox.event_slot THEN EXCLUDED.target_state ELSE role_sync_outbox.target_state END, " +
                                "    status = CASE WHEN :eventSlot >= role_sync_outbox.event_slot THEN 'PENDING' ELSE role_sync_outbox.status END, " +
                                "    retry_count = CASE WHEN :eventSlot >= role_sync_outbox.event_slot THEN 0 ELSE role_sync_outbox.retry_count END, " +
                                "    event_slot = CASE WHEN :eventSlot >= role_sync_outbox.event_slot THEN EXCLUDED.event_slot ELSE role_sync_outbox.event_slot END, " +
                                "    updated_at = now()"
                )
                .setParameter("discordId", discordId)
                .setParameter("guildId", guildId)
                .setParameter("roleId", roleId)
                .setParameter("targetState", targetState.name())
                .setParameter("eventSlot", eventSlot)
                .executeUpdate();

        log.info("[RoleEvaluation] Upserted desired-state task to target_state={} for user={} guild={} role={} (slot={})",
                targetState, discordId, guildId, roleId, eventSlot);
    }

    public boolean isOutboxTaskNeeded(String discordId, String guildId, String roleId, TargetState targetState) {
        Boolean hasRole = null;
        if (jda != null) {
            try {
                Guild guild = jda.getGuildById(guildId);
                if (guild != null) {
                    Member member = guild.getMemberById(discordId);
                    if (member != null) {
                        hasRole = member.getRoles().stream().anyMatch(r -> r.getId().equals(roleId));
                    }
                }
            } catch (Exception e) {
                log.warn("[RoleEvaluation] Failed to look up member {}/guild {} roles in JDA cache: {}", discordId, guildId, e.getMessage());
            }
        }
        
        boolean discordStateMatches = false;
        if (hasRole != null) {
            discordStateMatches = (targetState == TargetState.PRESENT && hasRole) ||
                                   (targetState == TargetState.ABSENT && !hasRole);
        }
        
        RoleSyncOutbox existing = RoleSyncOutbox.find("discordId = ?1 and guildId = ?2 and roleId = ?3", discordId, guildId, roleId).firstResult();
        
        if ("892629798772432906".equals(discordId)) {
            log.info("[RoleEvaluation] DEBUG USER 892629798772432906: targetState={}, hasRole={}, discordStateMatches={}, existing={}",
                    targetState, hasRole, discordStateMatches,
                    existing != null ? ("id=" + existing.id + ", status=" + existing.status + ", targetState=" + existing.targetState + ", slot=" + existing.eventSlot) : "null");
        }

        if (existing == null) {
            return !discordStateMatches;
        }
        
        if ("PENDING".equals(existing.status) || "PROCESSING".equals(existing.status)) {
            return existing.targetState != targetState;
        } else {
            return !discordStateMatches;
        }
    }

    @Transactional
    public boolean evaluateRuleCompliance(String discordId, List<String> walletAddresses, GuildRoleRule rule) {
        long matchingQuantity = getRuleMatchingQuantity(discordId, walletAddresses, rule);
        boolean result = matchingQuantity >= rule.minQuantity;
        log.info("[RoleEvaluation]   Evaluation Result for user {} / policy {}: matchingQty={} (required={}) -> meetsRule={}",
                discordId, rule.policyId, matchingQuantity, rule.minQuantity, result);
        return result;
    }

    public long getRuleMatchingQuantity(String discordId, List<String> walletAddresses, GuildRoleRule rule) {
        if (walletAddresses == null || walletAddresses.isEmpty()) {
            log.info("[RoleEvaluation]   User {} has no linked wallets - matching quantity = 0", discordId);
            return 0;
        }

        List<UserAssetInventory> inventoryItems = UserAssetInventory.list(
                "id.stakeAddress in ?1 and id.policyId = ?2",
                walletAddresses,
                rule.policyId
        );

        log.info("[RoleEvaluation]   User {} has {} inventory items matching policyId {}",
                discordId, inventoryItems.size(), rule.policyId);

        long matchingQuantity = 0;
        for (UserAssetInventory item : inventoryItems) {
            if (satisfiesCriteria(item, rule)) {
                matchingQuantity += item.quantity;
                log.info("[RoleEvaluation]     Item policy={}, asset={}, qty={} PASSED criteria. Current total={}",
                        item.id.policyId, item.id.assetNameHex, item.quantity, matchingQuantity);
            }
        }
        return matchingQuantity;
    }

    public boolean satisfiesCriteria(UserAssetInventory item, GuildRoleRule rule) {
        if (rule.criteria != null && !rule.criteria.isEmpty()) {
            for (RuleTraitCriteria criterion : rule.criteria) {
                String traitValue = item.traits != null ? item.traits.get(criterion.traitKey) : null;
                if (traitValue == null || !traitValue.equalsIgnoreCase(criterion.traitValue)) {
                    log.info("[RoleEvaluation]     Item policy={}, asset={}, qty={} FAILED trait criteria: expected {}={}, found {}",
                            item.id.policyId, item.id.assetNameHex, item.quantity, criterion.traitKey, criterion.traitValue, traitValue);
                    return false;
                }
            }
        }
        return true;
    }

    List<GuildRoleRule> getRulesForRole(String guildId, String roleId) {
        return GuildRoleRule.list("guildId = ?1 and roleId = ?2", guildId, roleId);
    }
}