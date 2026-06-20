package dk.panos.promofacie.service;

import dk.panos.promofacie.db.PendingRuleEvaluation;
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

    @Scheduled(every = "10s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void processPendingEvaluations() {
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

            for (Member member : members) {
                String discordId = member.getId();
                List<Wallet> wallets = getWalletsByDiscordId(discordId);
                List<String> walletAddresses = wallets.stream().map(Wallet::getAddress).toList();

                long eventSlot = 0L;
                if (!walletAddresses.isEmpty()) {
                    Long maxSlot = getMaxInventorySlot(walletAddresses);
                    if (maxSlot != null) {
                        eventSlot = maxSlot;
                    }
                }

                for (GuildRoleRule rule : rules) {
                    boolean meetsRule = roleEvaluationService.evaluateRuleCompliance(discordId, walletAddresses, rule);
                    TargetState targetState = meetsRule ? TargetState.PRESENT : TargetState.ABSENT;

                    roleEvaluationService.upsertOutboxTask(discordId, guildId, rule.roleId, targetState, eventSlot);
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
        return GuildRoleRule.findById(id);
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
}
