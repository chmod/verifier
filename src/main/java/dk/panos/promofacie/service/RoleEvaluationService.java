package dk.panos.promofacie.service;

import dk.panos.promofacie.db.GuildRoleRule;
import dk.panos.promofacie.db.RuleTraitCriteria;
import dk.panos.promofacie.db.UserAssetInventory;
import dk.panos.promofacie.db.Wallet;
import dk.panos.promofacie.db.PendingUserEvaluation;
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

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToLongFunction;
import java.util.stream.Collectors;
import dk.panos.promofacie.controller.model.RuleRequest;

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

        Set<String> searchPolicies = new HashSet<>(changedPolicies);
        for (String p : changedPolicies) {
            String norm = RuleRequest.normalizePolicyId(p);
            if (norm != null) searchPolicies.add(norm);
        }
        List<GuildRoleRule> matchedRules = GuildRoleRule.list("policyId in ?1 or lower(policyId) in ?1", searchPolicies);
        log.info("[RoleEvaluation] Found {} matched rule(s) for changed policies", matchedRules.size());

        // Group the matched rules by (guildId, roleId) to execute a single aggregated evaluation per role
        Map<String, Set<String>> affectedRolesByGuild = matchedRules.stream()
                .collect(Collectors.groupingBy(
                        rule -> rule.guildId,
                        Collectors.mapping(rule -> rule.roleId, Collectors.toSet())
                ));

        long evaluationVersion = System.currentTimeMillis();
        for (Map.Entry<String, Set<String>> guildEntry : affectedRolesByGuild.entrySet()) {
            String guildId = guildEntry.getKey();
            for (String roleId : guildEntry.getValue()) {
                boolean meetsGroupedRules = evaluateRoleEligibility(discordId, walletAddresses, guildId, roleId);
                TargetState targetState = meetsGroupedRules ? TargetState.PRESENT : TargetState.ABSENT;

                upsertOutboxTask(discordId, guildId, roleId, targetState, evaluationVersion);
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

        boolean eligible = evaluateRules(discordId, rules, rule -> getRuleMatchingQuantity(discordId, walletAddresses, rule));
        log.info("[RoleEvaluation] Role eligibility for user {} in guild {} role {}: eligible={} ({} rule(s), {} wallet(s))",
                discordId, guildId, roleId, eligible, rules.size(), walletAddresses != null ? walletAddresses.size() : 0);
        return eligible;
    }

    /**
     * Pure evaluation of one role's rules. Rules are grouped by ruleGroup; groups are OR'ed.
     * An AND group requires every rule to be compliant; a SUM group requires the summed quantity
     * to meet the summed min (and max, if any). Matching quantities come from {@code quantityFn},
     * so the same logic serves both the DB-backed per-user path and the in-memory bulk path.
     */
    public boolean evaluateRules(String discordId, List<GuildRoleRule> rules, ToLongFunction<GuildRoleRule> quantityFn) {
        Map<Integer, List<GuildRoleRule>> groupedRules = rules.stream()
                .collect(Collectors.groupingBy(r -> r.ruleGroup));

        // Outer level is OR: at least one group must be satisfied
        for (Map.Entry<Integer, List<GuildRoleRule>> entry : groupedRules.entrySet()) {
            int groupId = entry.getKey();
            List<GuildRoleRule> group = entry.getValue();

            boolean isAndGroup = group.stream().anyMatch(r -> Boolean.TRUE.equals(r.isAnd));

            if (isAndGroup) {
                boolean groupSatisfied = true;
                for (GuildRoleRule rule : group) {
                    long ruleQty = quantityFn.applyAsLong(rule);
                    boolean ruleCompliant = isQuantityCompliant(ruleQty, rule.minQuantity, rule.maxQuantity);
                    log.debug("[RoleEvaluation]     (AND Group {}) user={} rule id={} policy={} matchingQty={} (min={}, max={}) -> ruleCompliant={}",
                            groupId, discordId, rule.id, rule.policyId, ruleQty, rule.minQuantity, rule.maxQuantity, ruleCompliant);
                    if (!ruleCompliant) {
                        groupSatisfied = false;
                    }
                }
                if (groupSatisfied) {
                    return true; // Satisfied one of the OR pathways
                }
            } else {
                long groupTotalQty = 0;
                long groupRequiredQty = 0;
                Long groupMaxQty = null;

                for (GuildRoleRule rule : group) {
                    long ruleQty = quantityFn.applyAsLong(rule);
                    groupTotalQty += ruleQty;
                    groupRequiredQty += (rule.minQuantity != null ? rule.minQuantity : 1L);
                    if (rule.maxQuantity != null) {
                        groupMaxQty = (groupMaxQty == null) ? rule.maxQuantity : (groupMaxQty + rule.maxQuantity);
                    }
                    log.debug("[RoleEvaluation]     (SUM Group {}) user={} rule id={} policy={} matchingQty={} -> groupTotalQty={}, groupRequiredQty={}, groupMaxQty={}",
                            groupId, discordId, rule.id, rule.policyId, ruleQty, groupTotalQty, groupRequiredQty, groupMaxQty);
                }

                if (isQuantityCompliant(groupTotalQty, groupRequiredQty, groupMaxQty)) {
                    return true; // Satisfied one of the OR pathways
                }
            }
        }
        return false; // None of the groups were satisfied
    }

    @Transactional
    public void upsertOutboxTask(String discordId, String guildId, String roleId, TargetState targetState, long eventSlot) {
        if (!isOutboxTaskNeeded(discordId, guildId, roleId, targetState)) {
            log.debug("[RoleEvaluation] Skip upserting task for user={} guild={} role={} targetState={} (slot={}) as state is already in sync",
                    discordId, guildId, roleId, targetState, eventSlot);
            return;
        }
        upsertOutboxRow(discordId, guildId, roleId, targetState, eventSlot);
    }

    /**
     * Writes the desired state without the "is it needed" check. Callers must have decided via
     * {@link #isOutboxTaskNeeded(Boolean, TargetState, RoleSyncOutbox)}. The row is only overwritten
     * when {@code eventSlot} is at least the stored one, so newer evaluations always win.
     */
    @Transactional
    public void upsertOutboxRow(String discordId, String guildId, String roleId, TargetState targetState, long eventSlot) {
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
                    if (member == null) {
                        // The member cache is complete (GUILD_MEMBERS intent + ChunkingFilter.ALL), so a
                        // cache miss means the user is not in this guild. Enqueuing would only produce a
                        // REST lookup that fails with 404 Unknown Member on every re-evaluation.
                        log.debug("[RoleEvaluation] User {} is not a member of guild {} — skipping outbox task for role {}",
                                discordId, guildId, roleId);
                        return false;
                    }
                    hasRole = member.getRoles().stream().anyMatch(r -> r.getId().equals(roleId));
                }
            } catch (Exception e) {
                log.warn("[RoleEvaluation] Failed to look up member {}/guild {} roles in JDA cache: {}", discordId, guildId, e.getMessage());
            }
        }
        
        RoleSyncOutbox existing = RoleSyncOutbox.find("discordId = ?1 and guildId = ?2 and roleId = ?3", discordId, guildId, roleId).firstResult();
        return isOutboxTaskNeeded(hasRole, targetState, existing);
    }

    /**
     * Pure decision: does the desired state need to be written to the outbox?
     *
     * @param hasRole  whether the member currently holds the role per the gateway cache, or null if unknown
     * @param existing the current outbox row for (user, guild, role), or null
     */
    public boolean isOutboxTaskNeeded(Boolean hasRole, TargetState targetState, RoleSyncOutbox existing) {
        boolean discordStateMatches = false;
        if (hasRole != null) {
            discordStateMatches = (targetState == TargetState.PRESENT && hasRole) ||
                                   (targetState == TargetState.ABSENT && !hasRole);
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

    public boolean isQuantityCompliant(long quantity, Long minQuantity, Long maxQuantity) {
        long min = minQuantity != null ? minQuantity : 1L;
        boolean meetsMin = quantity >= min;
        boolean meetsMax = (maxQuantity == null) || (quantity <= maxQuantity);
        return meetsMin && meetsMax;
    }

    @Transactional
    public boolean evaluateRuleCompliance(String discordId, List<String> walletAddresses, GuildRoleRule rule) {
        long matchingQuantity = getRuleMatchingQuantity(discordId, walletAddresses, rule);
        boolean result = isQuantityCompliant(matchingQuantity, rule.minQuantity, rule.maxQuantity);
        log.info("[RoleEvaluation]   Evaluation Result for user {} / policy {}: matchingQty={} (min={}, max={}) -> meetsRule={}",
                discordId, rule.policyId, matchingQuantity, rule.minQuantity, rule.maxQuantity, result);
        return result;
    }

    public long getRuleMatchingQuantity(String discordId, List<String> walletAddresses, GuildRoleRule rule) {
        if (walletAddresses == null || walletAddresses.isEmpty()) {
            log.debug("[RoleEvaluation]   User {} has no linked wallets - matching quantity = 0", discordId);
            return 0;
        }

        String normalizedPolicyId = RuleRequest.normalizePolicyId(rule.policyId);
        List<UserAssetInventory> inventoryItems = findInventoryForPolicy(walletAddresses, rule.policyId, normalizedPolicyId);

        log.debug("[RoleEvaluation]   User {} has {} inventory items matching policyId {}",
                discordId, inventoryItems.size(), rule.policyId);

        return sumSatisfyingQuantity(inventoryItems, rule);
    }

    /**
     * In-memory equivalent of {@link #getRuleMatchingQuantity}: {@code userInventory} is all inventory
     * rows of the user's wallets (any policy). Policy matching mirrors {@link #findInventoryForPolicy}:
     * exact equality against the rule's raw policy id or its normalized form.
     */
    public long matchingQuantityInMemory(List<UserAssetInventory> userInventory, GuildRoleRule rule) {
        if (userInventory == null || userInventory.isEmpty()) {
            return 0;
        }
        String normalizedPolicyId = RuleRequest.normalizePolicyId(rule.policyId);
        List<UserAssetInventory> matching = userInventory.stream()
                .filter(item -> item.id.policyId != null
                        && (item.id.policyId.equals(rule.policyId) || item.id.policyId.equals(normalizedPolicyId)))
                .toList();
        return sumSatisfyingQuantity(matching, rule);
    }

    private long sumSatisfyingQuantity(List<UserAssetInventory> items, GuildRoleRule rule) {
        long matchingQuantity = 0;
        for (UserAssetInventory item : items) {
            if (satisfiesCriteria(item, rule)) {
                matchingQuantity += item.quantity;
                log.debug("[RoleEvaluation]     Item policy={}, asset={}, qty={} PASSED criteria. Current total={}",
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
                    log.debug("[RoleEvaluation]     Item policy={}, asset={}, qty={} FAILED trait criteria: expected {}={}, found {}",
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

    List<UserAssetInventory> findInventoryForPolicy(List<String> walletAddresses, String rulePolicyId, String normalizedPolicyId) {
        if (normalizedPolicyId != null && !normalizedPolicyId.equals(rulePolicyId)) {
            return UserAssetInventory.list(
                    "id.stakeAddress in ?1 and (id.policyId = ?2 or id.policyId = ?3)",
                    walletAddresses,
                    rulePolicyId,
                    normalizedPolicyId
            );
        } else {
            return UserAssetInventory.list(
                    "id.stakeAddress in ?1 and id.policyId = ?2",
                    walletAddresses,
                    rulePolicyId
            );
        }
    }

    @Transactional
    public void recalculateUserRolesInGuild(String discordId, String guildId) {
        List<GuildRoleRule> rules = GuildRoleRule.list("guildId", guildId);
        List<Wallet> wallets = Wallet.list("discordId = ?1", discordId);
        List<String> walletAddresses = wallets.stream().map(Wallet::getAddress).toList();

        if (rules.isEmpty()) {
            log.info("[RoleEvaluation] No rules configured for guild {}", guildId);
            return;
        }

        Set<String> uniqueRoleIds = rules.stream().map(r -> r.roleId).collect(Collectors.toSet());
        long evaluationVersion = System.currentTimeMillis();

        for (String roleId : uniqueRoleIds) {
            boolean meetsGroupedRules = evaluateRoleEligibility(discordId, walletAddresses, guildId, roleId);
            TargetState targetState = meetsGroupedRules ? TargetState.PRESENT : TargetState.ABSENT;
            upsertOutboxTask(discordId, guildId, roleId, targetState, evaluationVersion);
        }

        log.info("[RoleEvaluation] Recalculated {} unique role(s) for user {} in guild {}",
                uniqueRoleIds.size(), discordId, guildId);
    }

    /**
     * Called when a member joins a guild. Members who left and rejoined (or verified before joining)
     * are skipped by evaluation while they're not in the guild, so they need a fresh evaluation here.
     * Only enqueues when the user has linked wallets and the guild has rules configured.
     */
    @Transactional
    public void enqueueEvaluationOnMemberJoin(String guildId, String discordId) {
        if (Wallet.count("discordId = ?1", discordId) == 0) {
            log.debug("[RoleEvaluation] Member {} joined guild {} but has no linked wallets — skipping", discordId, guildId);
            return;
        }
        if (GuildRoleRule.count("guildId = ?1", guildId) == 0) {
            log.debug("[RoleEvaluation] Member {} joined guild {} which has no rules — skipping", discordId, guildId);
            return;
        }
        log.info("[RoleEvaluation] Member {} with linked wallet(s) joined guild {} — enqueuing evaluation", discordId, guildId);
        enqueueUserEvaluation(guildId, discordId);
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
            log.info("[RoleEvaluation] Enqueued PendingUserEvaluation for user {} in guild {}", discordId, guildId);
        } else {
            log.info("[RoleEvaluation] Evaluation already pending/processing for user {} in guild {}", discordId, guildId);
        }
    }

    @Transactional
    public void enqueueUserEvaluationForAllGuilds(String discordId) {
        if (discordId == null || discordId.isBlank()) {
            return;
        }
        List<String> guildIds = GuildRoleRule.getEntityManager()
                .createQuery("select distinct r.guildId from GuildRoleRule r", String.class)
                .getResultList();
        log.info("[RoleEvaluation] Enqueuing user evaluation for user {} across {} configured guild(s)",
                discordId, guildIds.size());
        for (String guildId : guildIds) {
            enqueueUserEvaluation(guildId, discordId);
        }
    }
}