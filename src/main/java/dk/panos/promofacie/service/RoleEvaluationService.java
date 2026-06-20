package dk.panos.promofacie.service;

import dk.panos.promofacie.db.GuildRoleRule;
import dk.panos.promofacie.db.RuleTraitCriteria;
import dk.panos.promofacie.db.UserAssetInventory;
import dk.panos.promofacie.db.Wallet;
import dk.panos.promofacie.db.RoleSyncOutbox;
import dk.panos.promofacie.db.TargetState;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;

@ApplicationScoped
public class RoleEvaluationService {

    private static final Logger log = LoggerFactory.getLogger(RoleEvaluationService.class);

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

        List<GuildRoleRule> rules = GuildRoleRule.list("policyId in ?1", changedPolicies);
        log.info("[RoleEvaluation] Found {} rule(s) matching changed policies for user {}", rules.size(), discordId);

        for (GuildRoleRule rule : rules) {
            log.info("[RoleEvaluation] Evaluating compliance for user {} in guild {} for role {} [policyId={}, minQuantity={}]",
                    discordId, rule.guildId, rule.roleId, rule.policyId, rule.minQuantity);
            boolean meetsRule = evaluateRuleCompliance(discordId, walletAddresses, rule);
            TargetState targetState = meetsRule ? TargetState.PRESENT : TargetState.ABSENT;

            upsertOutboxTask(discordId, rule.guildId, rule.roleId, targetState, eventSlot);
        }
    }

    @Transactional
    public void upsertOutboxTask(String discordId, String guildId, String roleId, TargetState targetState, long eventSlot) {
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

    public boolean evaluateRuleCompliance(String discordId, List<String> walletAddresses, GuildRoleRule rule) {
        if (walletAddresses == null || walletAddresses.isEmpty()) {
            log.info("[RoleEvaluation]   User {} has no linked wallets - evaluation compliance = false", discordId);
            return false;
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
            boolean matchesAllCriteria = true;
            if (rule.criteria != null && !rule.criteria.isEmpty()) {
                for (RuleTraitCriteria criterion : rule.criteria) {
                    String traitValue = item.traits != null ? item.traits.get(criterion.traitKey) : null;
                    if (traitValue == null || !traitValue.equalsIgnoreCase(criterion.traitValue)) {
                        matchesAllCriteria = false;
                        log.info("[RoleEvaluation]     Item policy={}, asset={}, qty={} FAILED trait criteria: expected {}={}, found {}",
                                item.id.policyId, item.id.assetNameHex, item.quantity, criterion.traitKey, criterion.traitValue, traitValue);
                        break;
                    }
                }
            }
            if (matchesAllCriteria) {
                matchingQuantity += item.quantity;
                log.info("[RoleEvaluation]     Item policy={}, asset={}, qty={} PASSED criteria. Current total={}",
                        item.id.policyId, item.id.assetNameHex, item.quantity, matchingQuantity);
            }
        }

        boolean result = matchingQuantity >= rule.minQuantity;
        log.info("[RoleEvaluation]   Evaluation Result for user {} / policy {}: matchingQty={} (required={}) -> meetsRule={}",
                discordId, rule.policyId, matchingQuantity, rule.minQuantity, result);
        return result;
    }
}