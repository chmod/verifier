package dk.panos.promofacie.service;

import dk.panos.promofacie.controller.model.RuleRequest;
import dk.panos.promofacie.db.PendingRuleEvaluation;
import dk.panos.promofacie.db.RoleSyncOutbox;
import dk.panos.promofacie.db.PendingUserEvaluation;
import dk.panos.promofacie.db.GuildRoleRule;
import dk.panos.promofacie.db.UserAssetInventory;
import dk.panos.promofacie.db.Wallet;
import dk.panos.promofacie.db.TargetState;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@ApplicationScoped
public class RuleEvaluationScheduler {

    private static final Logger log = LoggerFactory.getLogger(RuleEvaluationScheduler.class);
    private static final int MAX_RETRY_COUNT = 5;

    @Inject
    JDA jda;

    @Inject
    RoleEvaluationService roleEvaluationService;

    @Inject
    RoleSyncScheduler roleSyncScheduler;

    // Rule and user evaluations are separate jobs so a long guild-wide rule evaluation never
    // blocks member joins or /sync-roles requests.
    @Scheduled(every = "10s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void processPendingRuleEvaluations() {
        List<PendingRuleEvaluation> acquired = acquirePendingEvaluations();
        if (acquired.isEmpty()) {
            return;
        }

        log.info("[RuleEvaluationScheduler] Acquired {} pending rule evaluation task(s) to process", acquired.size());

        Map<String, List<PendingRuleEvaluation>> grouped = acquired.stream()
                .collect(Collectors.groupingBy(item -> item.guildId));

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Map.Entry<String, List<PendingRuleEvaluation>> entry : grouped.entrySet()) {
                String guildId = entry.getKey();
                List<PendingRuleEvaluation> guildPending = entry.getValue();
                executor.submit(() -> processGuildEvaluations(guildId, guildPending));
            }
        } catch (Exception e) {
            log.error("[RuleEvaluationScheduler] Error executing virtual thread guild evaluation processors", e);
        }
    }

    void processGuildEvaluations(String guildId, List<PendingRuleEvaluation> tasks) {
        log.info("[RuleEvaluationScheduler] Starting rule evaluation for guild {} (tasks count: {})", guildId, tasks.size());
        long startedAt = System.currentTimeMillis();
        // One version for the whole run, taken before any data is loaded: an event-driven upsert that
        // lands mid-run carries a newer version and wins over this run's (possibly older) view.
        long evaluationVersion = startedAt;
        try {
            Guild guild = jda.getGuildById(guildId);
            if (guild == null) {
                log.warn("[RuleEvaluationScheduler] Guild {} not found in JDA cache", guildId);
                markTasksFailedTerminal(tasks, "Guild not found in JDA cache");
                return;
            }

            List<Member> members = guild.getMembers();
            log.info("[RuleEvaluationScheduler] Found {} member(s) in JDA cache for guild {}", members.size(), guildId);

            Set<String> roleIds = new HashSet<>();
            for (PendingRuleEvaluation task : tasks) {
                GuildRoleRule rule = getRuleById(task.ruleId);
                if (rule != null) {
                    roleIds.add(rule.roleId);
                } else {
                    log.warn("[RuleEvaluationScheduler] GuildRoleRule with id={} not found for task {}", task.ruleId, task.id);
                }
            }

            if (roleIds.isEmpty()) {
                log.info("[RuleEvaluationScheduler] No valid rules to evaluate for guild {}", guildId);
                markTasksDone(tasks);
                return;
            }

            // Bulk-load everything once; the per-member loop below runs purely in memory.
            // All rules of each affected role are needed, not just the rules named by the tasks.
            Map<String, List<GuildRoleRule>> rulesByRole = loadRulesForRoles(guildId, roleIds).stream()
                    .collect(Collectors.groupingBy(r -> r.roleId));
            Set<String> policyIds = new HashSet<>();
            for (List<GuildRoleRule> roleRules : rulesByRole.values()) {
                for (GuildRoleRule r : roleRules) {
                    policyIds.add(r.policyId);
                    String normalized = RuleRequest.normalizePolicyId(r.policyId);
                    if (normalized != null) policyIds.add(normalized);
                }
            }
            Map<String, List<String>> walletsByDiscordId = loadWalletAddressesByDiscordId();
            Map<String, List<UserAssetInventory>> inventoryByAddress = loadInventoryByAddress(policyIds);
            Map<String, RoleSyncOutbox> outboxByUserRole = loadOutboxByUserRole(guildId, roleIds);

            int membersWithWallets = 0;
            int rowsWritten = 0;
            for (Member member : members) {
                String discordId = member.getId();
                List<String> addresses = walletsByDiscordId.getOrDefault(discordId, List.of());
                List<UserAssetInventory> userInventory = new ArrayList<>();
                for (String address : addresses) {
                    userInventory.addAll(inventoryByAddress.getOrDefault(address, List.of()));
                }
                if (!addresses.isEmpty()) {
                    membersWithWallets++;
                }
                Set<String> heldRoleIds = member.getRoles().stream().map(Role::getId).collect(Collectors.toSet());

                for (String roleId : roleIds) {
                    List<GuildRoleRule> roleRules = rulesByRole.getOrDefault(roleId, List.of());
                    boolean eligible = !roleRules.isEmpty() && roleEvaluationService.evaluateRules(discordId, roleRules,
                            rule -> roleEvaluationService.matchingQuantityInMemory(userInventory, rule));
                    TargetState targetState = eligible ? TargetState.PRESENT : TargetState.ABSENT;

                    RoleSyncOutbox existing = outboxByUserRole.get(discordId + "|" + roleId);
                    if (roleEvaluationService.isOutboxTaskNeeded(heldRoleIds.contains(roleId), targetState, existing)) {
                        roleEvaluationService.upsertOutboxRow(discordId, guildId, roleId, targetState, evaluationVersion);
                        rowsWritten++;
                    }
                }
            }

            markTasksDone(tasks);
            log.info("[RuleEvaluationScheduler] Successfully completed rule evaluation sync for guild {}: {} member(s) evaluated "
                            + "({} with linked wallets) against {} role(s), {} outbox task(s) written, took {} ms",
                    guildId, members.size(), membersWithWallets, roleIds.size(), rowsWritten, System.currentTimeMillis() - startedAt);
        } catch (Exception e) {
            log.error("[RuleEvaluationScheduler] Failed rule evaluation sync for guild {}", guildId, e);
            handleTasksFailure(tasks, e.getMessage() != null ? e.getMessage() : e.getClass().getName());
        }
    }

    @Transactional
    public List<PendingRuleEvaluation> acquirePendingEvaluations() {
        List<PendingRuleEvaluation> list = PendingRuleEvaluation.find("status = 'PENDING' or status = 'FAILED'")
                .withLock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
                .withHint("jakarta.persistence.lock.timeout", -2) // SKIP LOCKED
                .list();
        List<PendingRuleEvaluation> acquired = new ArrayList<>();
        for (PendingRuleEvaluation item : list) {
            if (item.retryCount < MAX_RETRY_COUNT) {
                item.status = "PROCESSING";
                item.updatedAt = Instant.now();
                item.persist();
                acquired.add(item);
            } else {
                item.status = "FAILED_EXHAUSTED";
                item.updatedAt = Instant.now();
                item.persist();
                log.warn("[RuleEvaluationScheduler] Task {} has exhausted retry budget ({}) — marking FAILED_EXHAUSTED",
                        item.id, MAX_RETRY_COUNT);
            }
        }
        return acquired;
    }

    @Transactional
    public GuildRoleRule getRuleById(Long id) {
        return GuildRoleRule.find("from GuildRoleRule r left join fetch r.criteria where r.id = ?1", id).firstResult();
    }

    /** All rules of the given roles in the guild, with trait criteria fetched (used after the transaction closes). */
    @Transactional
    public List<GuildRoleRule> loadRulesForRoles(String guildId, Set<String> roleIds) {
        // Hibernate 6 de-duplicates root entities of a fetch join, so no "distinct" is needed.
        return GuildRoleRule.list("from GuildRoleRule r left join fetch r.criteria where r.guildId = ?1 and r.roleId in ?2",
                guildId, roleIds);
    }

    /** discordId -> wallet addresses, for all linked wallets. Avoids an IN list over (possibly huge) member ids. */
    @Transactional
    public Map<String, List<String>> loadWalletAddressesByDiscordId() {
        List<Object[]> rows = Wallet.getEntityManager()
                .createQuery("select w.discordId, w.address from Wallet w", Object[].class)
                .getResultList();
        Map<String, List<String>> result = new HashMap<>();
        for (Object[] row : rows) {
            result.computeIfAbsent((String) row[0], k -> new ArrayList<>()).add((String) row[1]);
        }
        return result;
    }

    /** stakeAddress -> inventory rows, restricted to the given policy ids (raw and normalized forms). */
    @Transactional
    public Map<String, List<UserAssetInventory>> loadInventoryByAddress(Set<String> policyIds) {
        if (policyIds.isEmpty()) {
            return Map.of();
        }
        List<UserAssetInventory> rows = UserAssetInventory.list("id.policyId in ?1", policyIds);
        return rows.stream().collect(Collectors.groupingBy(u -> u.id.stakeAddress));
    }

    /** "discordId|roleId" -> current outbox row for the guild's affected roles. */
    @Transactional
    public Map<String, RoleSyncOutbox> loadOutboxByUserRole(String guildId, Set<String> roleIds) {
        List<RoleSyncOutbox> rows = RoleSyncOutbox.list("guildId = ?1 and roleId in ?2", guildId, roleIds);
        Map<String, RoleSyncOutbox> result = new HashMap<>();
        for (RoleSyncOutbox row : rows) {
            result.put(row.discordId + "|" + row.roleId, row);
        }
        return result;
    }

    @Transactional
    public void markTasksDone(List<PendingRuleEvaluation> tasks) {
        for (PendingRuleEvaluation task : tasks) {
            PendingRuleEvaluation managed = PendingRuleEvaluation.findById(task.id);
            if (managed != null) {
                managed.status = "DONE";
                managed.updatedAt = Instant.now();
                managed.persist();
                log.info("[RuleEvaluationScheduler] Rule created, sync completed for task id={}", task.id);
            }
        }
    }

    @Transactional
    public void markTasksFailedTerminal(List<PendingRuleEvaluation> tasks, String reason) {
        for (PendingRuleEvaluation task : tasks) {
            PendingRuleEvaluation managed = PendingRuleEvaluation.findById(task.id);
            if (managed != null) {
                managed.status = "FAILED_EXHAUSTED";
                managed.errorMessage = reason;
                managed.updatedAt = Instant.now();
                managed.persist();
                log.error("[RuleEvaluationScheduler] Rule created, sync failed terminally: task id={}, reason={}", task.id, reason);
            }
        }
    }

    @Transactional
    public void handleTasksFailure(List<PendingRuleEvaluation> tasks, String errorMessage) {
        for (PendingRuleEvaluation task : tasks) {
            PendingRuleEvaluation managed = PendingRuleEvaluation.findById(task.id);
            if (managed != null) {
                managed.retryCount++;
                if (managed.retryCount >= MAX_RETRY_COUNT) {
                    managed.status = "FAILED_EXHAUSTED";
                    log.error("[RuleEvaluationScheduler] Rule created, sync failed terminally (exhausted retry limit): task id={}, error={}", task.id, errorMessage);
                } else {
                    managed.status = "FAILED";
                    log.warn("[RuleEvaluationScheduler] Rule created, sync failed and will retry: task id={} (attempt {}/{}), error={}",
                            task.id, managed.retryCount, MAX_RETRY_COUNT, errorMessage);
                }
                managed.errorMessage = errorMessage.length() > 999 ? errorMessage.substring(0, 999) : errorMessage;
                managed.updatedAt = Instant.now();
                managed.persist();
            }
        }
    }

    @Scheduled(every = "10s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void processPendingUserEvaluations() {
        List<PendingUserEvaluation> acquired = acquirePendingUserEvaluations();
        if (acquired.isEmpty()) {
            return;
        }

        log.info("[RuleEvaluationScheduler] Acquired {} pending user evaluation task(s) to process", acquired.size());

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (PendingUserEvaluation task : acquired) {
                executor.submit(() -> processSingleUserEvaluation(task));
            }
        } catch (Exception e) {
            log.error("[RuleEvaluationScheduler] Error executing virtual thread user evaluation processors", e);
        }
    }

    public void processSingleUserEvaluation(PendingUserEvaluation task) {
        String guildId = task.guildId;
        String discordId = task.discordId;
        log.info("[RuleEvaluationScheduler] Starting user evaluation for user {} in guild {} (task id: {})",
                discordId, guildId, task.id);
        try {
            roleEvaluationService.recalculateUserRolesInGuild(discordId, guildId);
            if (roleSyncScheduler != null) {
                roleSyncScheduler.processUserGuildGroup(discordId, guildId);
            }
            markUserTaskDone(task);
            log.info("[RuleEvaluationScheduler] Successfully completed user evaluation for user {} in guild {}",
                    discordId, guildId);
        } catch (Exception e) {
            log.error("[RuleEvaluationScheduler] Failed user evaluation for user {} in guild {}: {}",
                    discordId, guildId, e.getMessage(), e);
            handleUserTaskFailure(task, e.getMessage() != null ? e.getMessage() : e.getClass().getName());
        }
    }

    @Transactional
    public List<PendingUserEvaluation> acquirePendingUserEvaluations() {
        List<PendingUserEvaluation> list = PendingUserEvaluation.find("status = 'PENDING' or status = 'FAILED'")
                .withLock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
                .withHint("jakarta.persistence.lock.timeout", -2) // SKIP LOCKED
                .list();
        List<PendingUserEvaluation> acquired = new ArrayList<>();
        for (PendingUserEvaluation item : list) {
            if (item.retryCount < MAX_RETRY_COUNT) {
                item.status = "PROCESSING";
                item.updatedAt = Instant.now();
                item.persist();
                acquired.add(item);
            } else {
                item.status = "FAILED_EXHAUSTED";
                item.updatedAt = Instant.now();
                item.persist();
                log.warn("[RuleEvaluationScheduler] User evaluation task {} has exhausted retry budget ({}) — marking FAILED_EXHAUSTED",
                        item.id, MAX_RETRY_COUNT);
            }
        }
        return acquired;
    }

    @Transactional
    public void markUserTaskDone(PendingUserEvaluation task) {
        PendingUserEvaluation managed = PendingUserEvaluation.findById(task.id);
        if (managed != null) {
            managed.status = "DONE";
            managed.updatedAt = Instant.now();
            managed.persist();
            log.info("[RuleEvaluationScheduler] User evaluation completed for task id={}", task.id);
        }
    }

    @Transactional
    public void handleUserTaskFailure(PendingUserEvaluation task, String errorMessage) {
        PendingUserEvaluation managed = PendingUserEvaluation.findById(task.id);
        if (managed != null) {
            managed.retryCount++;
            if (managed.retryCount >= MAX_RETRY_COUNT) {
                managed.status = "FAILED_EXHAUSTED";
                log.error("[RuleEvaluationScheduler] User evaluation failed terminally (exhausted retry limit): task id={}, error={}", task.id, errorMessage);
            } else {
                managed.status = "FAILED";
                log.warn("[RuleEvaluationScheduler] User evaluation failed and will retry: task id={} (attempt {}/{}), error={}",
                        task.id, managed.retryCount, MAX_RETRY_COUNT, errorMessage);
            }
            managed.errorMessage = errorMessage.length() > 999 ? errorMessage.substring(0, 999) : errorMessage;
            managed.updatedAt = Instant.now();
            managed.persist();
        }
    }
}
