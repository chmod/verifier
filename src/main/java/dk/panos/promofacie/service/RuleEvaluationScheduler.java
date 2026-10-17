package dk.panos.promofacie.service;

import dk.panos.promofacie.db.PendingRuleEvaluation;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
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

    @Scheduled(every = "10s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void processPendingEvaluations() {
        processPendingRuleEvaluations();
        processPendingUserEvaluations();
    }

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

    private void processGuildEvaluations(String guildId, List<PendingRuleEvaluation> tasks) {
        log.info("[RuleEvaluationScheduler] Starting rule evaluation for guild {} (tasks count: {})", guildId, tasks.size());
        try {
            Guild guild = jda.getGuildById(guildId);
            if (guild == null) {
                log.warn("[RuleEvaluationScheduler] Guild {} not found in JDA cache", guildId);
                markTasksFailedTerminal(tasks, "Guild not found in JDA cache");
                return;
            }

            List<Member> members = guild.getMembers();
            log.info("[RuleEvaluationScheduler] Found {} member(s) in JDA cache for guild {}", members.size(), guildId);

            List<GuildRoleRule> rules = new ArrayList<>();
            for (PendingRuleEvaluation task : tasks) {
                GuildRoleRule rule = getRuleById(task.ruleId);
                if (rule != null) {
                    rules.add(rule);
                } else {
                    log.warn("[RuleEvaluationScheduler] GuildRoleRule with id={} not found for task {}", task.ruleId, task.id);
                }
            }

            if (rules.isEmpty()) {
                log.info("[RuleEvaluationScheduler] No valid rules to evaluate for guild {}", guildId);
                markTasksDone(tasks);
                return;
            }

            // Group rules by roleId to run a single aggregated evaluation per role
            Set<String> uniqueRoleIds = rules.stream()
                    .map(r -> r.roleId)
                    .collect(Collectors.toSet());

            for (Member member : members) {
                String discordId = member.getId();
                if ("892629798772432906".equals(discordId)) {
                    log.info("[RuleEvaluationScheduler] DEBUG USER 892629798772432906: found in JDA cache, roles: {}",
                            member.getRoles().stream().map(r -> r.getName() + " (" + r.getId() + ")").toList());
                }
                List<Wallet> wallets = getWalletsByDiscordId(discordId);
                List<String> walletAddresses = wallets.stream().map(Wallet::getAddress).toList();

                long evaluationVersion = System.currentTimeMillis();

                for (String roleId : uniqueRoleIds) {
                    boolean meetsGroupedRules = roleEvaluationService.evaluateRoleEligibility(discordId, walletAddresses, guildId, roleId);
                    TargetState targetState = meetsGroupedRules ? TargetState.PRESENT : TargetState.ABSENT;

                    roleEvaluationService.upsertOutboxTask(discordId, guildId, roleId, targetState, evaluationVersion);
                }
            }

            markTasksDone(tasks);
            log.info("[RuleEvaluationScheduler] Successfully completed rule evaluation sync for guild {}", guildId);
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

    @Transactional
    public List<Wallet> getWalletsByDiscordId(String discordId) {
        return Wallet.list("discordId = ?1", discordId);
    }

    @Transactional
    public Long getMaxInventorySlot(List<String> walletAddresses) {
        return UserAssetInventory.getEntityManager()
                .createQuery("select max(u.lastUpdatedSlot) from UserAssetInventory u where u.id.stakeAddress in ?1", Long.class)
                .setParameter(1, walletAddresses)
                .getSingleResult();
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
