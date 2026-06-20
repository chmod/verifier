package dk.panos.promofacie.service;

import dk.panos.promofacie.db.RoleSyncOutbox;
import dk.panos.promofacie.db.TargetState;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.persistence.LockModeType;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@ApplicationScoped
public class RoleSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(RoleSyncScheduler.class);
    private static final int MAX_RETRY_COUNT = 5;
    private static final long JDA_CALL_TIMEOUT_SECONDS = 30;

    @Inject
    JDA jda;

    @Scheduled(every = "15m", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void processOutboxQueue() {
        List<String> guildIds = getPendingGuildIds();
        if (guildIds.isEmpty()) {
            return;
        }

        // One virtual thread per GUILD, not per (user, guild). Discord's role-modification
        // rate limits are bucketed per guild, so spawning a separate thread per user within
        // the same guild doesn't parallelize anything real — they'd all queue behind the
        // same guild bucket inside JDA anyway. Grouping by guild means each thread processes
        // that guild's pending users sequentially internally, while different guilds (with
        // independent rate-limit buckets) still run concurrently against each other.
        log.info("[RoleSyncScheduler] Spawning virtual threads for {} guild(s) with pending outbox work", guildIds.size());

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (String guildId : guildIds) {
                executor.submit(() -> processGuild(guildId));
            }
        } catch (Exception e) {
            log.error("[RoleSyncScheduler] Error executing virtual thread guild processors", e);
        }
    }

    private void processGuild(String guildId) {
        List<String> discordIds = getPendingDiscordIdsForGuild(guildId);
        log.info("[RoleSyncScheduler] Processing {} user(s) with pending tasks in guild {}", discordIds.size(), guildId);

        for (String discordId : discordIds) {
            processUserGuildGroup(discordId, guildId);
        }
    }

    private void processUserGuildGroup(String discordId, String guildId) {
        try {
            // Acquire lock and mark tasks as PROCESSING in a short transaction. This commits
            // and releases the row lock before the JDA call below runs — we deliberately do
            // not hold a DB lock across the blocking network call. Correctness past this
            // point depends on the conditional, slot-guarded updates in
            // markTasksDone/markTasksFailed/handleGroupFailure, not on any lock still held.
            List<RoleSyncOutbox> tasks = acquirePendingTasksWithLock(discordId, guildId);
            if (tasks.isEmpty()) {
                log.info("[RoleSyncScheduler] Group for user: {}, guild: {} has no pending tasks or is already locked.", discordId, guildId);
                return;
            }

            log.info("[RoleSyncScheduler] Start processing group for user: {}, guild: {}. Acquired {} task(s).",
                    discordId, guildId, tasks.size());
            for (RoleSyncOutbox task : tasks) {
                log.info("[RoleSyncScheduler]   Acquired Outbox Task: id={}, roleId={}, targetState={}, eventSlot={}, retryCount={}",
                        task.id, task.roleId, task.targetState, task.eventSlot, task.retryCount);
            }

            executeGroupBatch(discordId, guildId, tasks);
        } catch (Exception e) {
            log.error("[RoleSyncScheduler] Error processing user {} in guild {}", discordId, guildId, e);
        }
    }

    private void executeGroupBatch(String discordId, String guildId, List<RoleSyncOutbox> tasks) {
        try {
            Guild guild = jda.getGuildById(guildId);
            if (guild == null) {
                log.warn("[RoleSyncScheduler] Guild {} not found in JDA cache for user {}", guildId, discordId);
                markTasksFailed(tasks, "guild not found");
                return;
            }

            Member member = guild.getMemberById(discordId);
            if (member == null) {
                log.info("[RoleSyncScheduler] Member {} not in JDA cache for guild {}. Retrieving from API...", discordId, guildId);
                try {
                    member = guild.retrieveMemberById(discordId).complete();
                    log.info("[RoleSyncScheduler] Member {} successfully retrieved from Discord API for guild {}", discordId, guildId);
                } catch (Exception e) {
                    // Member may genuinely have left, or this may be transient gateway lag.
                    // Route through the retry path rather than an immediate terminal failure.
                    log.warn("[RoleSyncScheduler] Member {} retrieve from Discord API failed in guild {} — routing to retry. Reason: {}",
                            discordId, guildId, e.getMessage());
                    handleGroupFailure(tasks, "member not found: " + e.getMessage());
                    return;
                }
            }

            List<Role> rolesToAdd = new ArrayList<>();
            List<Role> rolesToRemove = new ArrayList<>();
            List<RoleSyncOutbox> appliedTasks = new ArrayList<>();
            List<RoleSyncOutbox> unresolvedRoleTasks = new ArrayList<>();

            for (RoleSyncOutbox task : tasks) {
                Role role = guild.getRoleById(task.roleId);
                if (role == null) {
                    log.warn("[RoleSyncScheduler] Role {} not found in guild {} — task {} routed to retry", task.roleId, guildId, task.id);
                    unresolvedRoleTasks.add(task);
                    continue;
                }
                if (task.targetState == TargetState.PRESENT) {
                    rolesToAdd.add(role);
                } else {
                    rolesToRemove.add(role);
                }
                appliedTasks.add(task);
            }

            // Tasks whose role couldn't be resolved get an explicit outcome (retry budget,
            // same as any other failure) instead of being silently dropped from every status.
            if (!unresolvedRoleTasks.isEmpty()) {
                log.info("[RoleSyncScheduler] Handling {} unresolved role task(s) for user {} in guild {}",
                        unresolvedRoleTasks.size(), discordId, guildId);
                handleGroupFailure(unresolvedRoleTasks, "role not found in guild");
            }

            if (appliedTasks.isEmpty()) {
                log.info("[RoleSyncScheduler] No resolvable role tasks left for user {} in guild {}", discordId, guildId);
                return;
            }

            log.info("[RoleSyncScheduler] Executing JDA batch modify roles for user {} in guild {}: adding {} role(s), removing {} role(s)",
                    discordId, guildId, rolesToAdd.size(), rolesToRemove.size());
            for (Role r : rolesToAdd) {
                log.info("[RoleSyncScheduler]   Role to ADD: {} (id={})", r.getName(), r.getId());
            }
            for (Role r : rolesToRemove) {
                log.info("[RoleSyncScheduler]   Role to REMOVE: {} (id={})", r.getName(), r.getId());
            }

            guild.modifyMemberRoles(member, rolesToAdd, rolesToRemove)
                    .submit()
                    .get(JDA_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            // Success path: mark only the tasks actually applied as DONE — conditionally,
            // so any task superseded by a newer enqueue mid-flight is left untouched.
            markTasksDone(appliedTasks);
            log.info("[RoleSyncScheduler] Successfully processed roles for user {} in guild {}", discordId, guildId);

        } catch (TimeoutException e) {
            log.error("[RoleSyncScheduler] JDA call timed out after {}s for user {} in guild {}", JDA_CALL_TIMEOUT_SECONDS, discordId, guildId, e);
            handleGroupFailure(tasks, "JDA call timed out");
        } catch (Exception e) {
            log.error("[RoleSyncScheduler] JDA execution failed for user {} in guild {}", discordId, guildId, e);
            handleGroupFailure(tasks, e.getMessage());
        }
    }

    @Transactional
    public List<String> getPendingGuildIds() {
        return RoleSyncOutbox.getEntityManager()
                .createQuery("select distinct o.guildId from RoleSyncOutbox o where o.status = 'PENDING'", String.class)
                .getResultList();
    }

    @Transactional
    public List<String> getPendingDiscordIdsForGuild(String guildId) {
        return RoleSyncOutbox.getEntityManager()
                .createQuery("select distinct o.discordId from RoleSyncOutbox o where o.guildId = ?1 and o.status = 'PENDING'", String.class)
                .setParameter(1, guildId)
                .getResultList();
    }

    @Transactional
    public List<RoleSyncOutbox> acquirePendingTasksWithLock(String discordId, String guildId) {
        List<RoleSyncOutbox> tasks = RoleSyncOutbox.find("discordId = ?1 and guildId = ?2 and status = ?3", discordId, guildId, "PENDING")
                .withLock(LockModeType.PESSIMISTIC_WRITE)
                .withHint("jakarta.persistence.lock.timeout", -2) // SKIP LOCKED
                .list();

        for (RoleSyncOutbox task : tasks) {
            task.status = "PROCESSING";
            task.updatedAt = Instant.now();
            task.persist();
        }
        return tasks;
    }

    /**
     * Marks tasks DONE, but only if the row's eventSlot still matches what was executed
     * against and its status is still PROCESSING. If a newer enqueue has superseded the row
     * in the meantime, this is a deliberate no-op — the row already reflects newer desired
     * state and must be left for the next scheduler tick to pick up and apply correctly.
     */
    @Transactional
    public void markTasksDone(List<RoleSyncOutbox> tasks) {
        for (RoleSyncOutbox task : tasks) {
            long updated;
            if (task.eventSlot == null) {
                updated = RoleSyncOutbox.update(
                        "status = 'DONE', updatedAt = ?1 where id = ?2 and eventSlot is null and status = 'PROCESSING'",
                        Instant.now(), task.id
                );
            } else {
                updated = RoleSyncOutbox.update(
                        "status = 'DONE', updatedAt = ?1 where id = ?2 and eventSlot = ?3 and status = 'PROCESSING'",
                        Instant.now(), task.id, task.eventSlot
                );
            }
            if (updated == 0) {
                log.info("[RoleSyncScheduler] Task {} was superseded during execution — leaving as-is for re-pick", task.id);
            }
        }
    }

    /**
     * Terminal failure (no further retry). Same supersede-guard as markTasksDone.
     */
    @Transactional
    public void markTasksFailed(List<RoleSyncOutbox> tasks, String reason) {
        for (RoleSyncOutbox task : tasks) {
            long updated;
            if (task.eventSlot == null) {
                updated = RoleSyncOutbox.update(
                        "status = 'FAILED', updatedAt = ?1 where id = ?2 and eventSlot is null and status = 'PROCESSING'",
                        Instant.now(), task.id
                );
            } else {
                updated = RoleSyncOutbox.update(
                        "status = 'FAILED', updatedAt = ?1 where id = ?2 and eventSlot = ?3 and status = 'PROCESSING'",
                        Instant.now(), task.id, task.eventSlot
                );
            }
            if (updated == 0) {
                log.info("[RoleSyncScheduler] Task {} was superseded before failure could be recorded — leaving as-is for re-pick", task.id);
            } else {
                log.warn("[RoleSyncScheduler] Task {} marked FAILED (terminal): {}", task.id, reason);
            }
        }
    }

    /**
     * Failure with retry: increments retryCount and either requeues as PENDING or marks
     * FAILED once the retry budget is exhausted. Only acts on rows still in the state we
     * expect (PROCESSING, same eventSlot we executed against) — a superseded row is left
     * alone since it's already PENDING with newer intent.
     */
    @Transactional
    public void handleGroupFailure(List<RoleSyncOutbox> tasks, String reason) {
        for (RoleSyncOutbox task : tasks) {
            RoleSyncOutbox managedTask;
            if (task.eventSlot == null) {
                managedTask = RoleSyncOutbox.find(
                        "id = ?1 and eventSlot is null and status = 'PROCESSING'", task.id
                ).firstResult();
            } else {
                managedTask = RoleSyncOutbox.find(
                        "id = ?1 and eventSlot = ?2 and status = 'PROCESSING'", task.id, task.eventSlot
                ).firstResult();
            }

            if (managedTask == null) {
                log.info("[RoleSyncScheduler] Task {} was superseded during failure handling — leaving as-is for re-pick", task.id);
                continue;
            }

            managedTask.retryCount++;
            if (managedTask.retryCount >= MAX_RETRY_COUNT) {
                managedTask.status = "FAILED";
                log.warn("[RoleSyncScheduler] Task {} exhausted retry budget ({}) — marking FAILED. Reason: {}",
                        managedTask.id, MAX_RETRY_COUNT, reason);
            } else {
                managedTask.status = "PENDING";
                log.info("[RoleSyncScheduler] Task {} requeued (attempt {}/{}). Reason: {}",
                        managedTask.id, managedTask.retryCount, MAX_RETRY_COUNT, reason);
            }
            managedTask.updatedAt = Instant.now();
            managedTask.persist();
        }
    }
}