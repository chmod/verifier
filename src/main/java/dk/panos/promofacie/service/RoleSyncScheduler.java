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

@ApplicationScoped
public class RoleSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(RoleSyncScheduler.class);

    @Inject
    JDA jda;

    @Inject
    RoleEvaluationService roleEvaluationService;

    @Scheduled(every = "15m", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void processOutboxQueue() {
        List<Object[]> pendingGroups = getPendingGroups();
        if (pendingGroups.isEmpty()) {
            return;
        }

        log.info("[RoleSyncScheduler] Spawning virtual threads to process outbox queue for {} user-guild group(s)", pendingGroups.size());

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Object[] group : pendingGroups) {
                String discordId = (String) group[0];
                String guildId = (String) group[1];
                executor.submit(() -> processUserGuildGroup(discordId, guildId));
            }
        } catch (Exception e) {
            log.error("[RoleSyncScheduler] Error executing virtual thread group processors", e);
        }
    }

    private void processUserGuildGroup(String discordId, String guildId) {
        log.info("[RoleSyncScheduler] Start processing group for user: {}, guild: {}", discordId, guildId);
        try {
            // Acquire lock and mark tasks as PROCESSING in a short transaction
            List<RoleSyncOutbox> tasks = acquirePendingTasksWithLock(discordId, guildId);
            if (tasks.isEmpty()) {
                log.info("[RoleSyncScheduler] Group for user: {}, guild: {} is already locked or has no pending tasks", discordId, guildId);
                return;
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
                log.warn("[RoleSyncScheduler] Guild {} not found for user {}", guildId, discordId);
                markTasksFailed(tasks);
                return;
            }

            Member member = guild.getMemberById(discordId);
            if (member == null) {
                try {
                    member = guild.retrieveMemberById(discordId).complete();
                } catch (Exception e) {
                    log.warn("[RoleSyncScheduler] Member {} not found in guild {} — marking tasks as failed", discordId, guildId);
                    markTasksFailed(tasks);
                    return;
                }
            }

            List<Role> rolesToAdd = new ArrayList<>();
            List<Role> rolesToRemove = new ArrayList<>();

            for (RoleSyncOutbox task : tasks) {
                Role role = guild.getRoleById(task.roleId);
                if (role == null) {
                    log.warn("[RoleSyncScheduler] Role {} not found in guild {} — skipping task {}", task.roleId, guildId, task.id);
                    continue;
                }
                if (task.targetState == TargetState.PRESENT) {
                    rolesToAdd.add(role);
                } else {
                    rolesToRemove.add(role);
                }
            }

            log.info("[RoleSyncScheduler] Executing JDA batch modify roles for user {} in guild {}: adding {}, removing {}", 
                    discordId, guildId, rolesToAdd.size(), rolesToRemove.size());
            
            guild.modifyMemberRoles(member, rolesToAdd, rolesToRemove).submit().get();

            // Success path: mark tasks as DONE
            markTasksDone(tasks);
            log.info("[RoleSyncScheduler] Successfully processed roles for user {} in guild {}", discordId, guildId);

        } catch (Exception e) {
            log.error("[RoleSyncScheduler] JDA execution failed for user {} in guild {}", discordId, guildId, e);
            handleGroupFailure(tasks);
        }
    }

    @Transactional
    public List<Object[]> getPendingGroups() {
        return RoleSyncOutbox.getEntityManager()
                .createQuery("select distinct o.discordId, o.guildId from RoleSyncOutbox o where o.status = 'PENDING'", Object[].class)
                .getResultList();
    }

    @Transactional
    public List<RoleSyncOutbox> acquirePendingTasksWithLock(String discordId, String guildId) {
        List<RoleSyncOutbox> tasks = RoleSyncOutbox.find("discordId = ?1 and guildId = ?2 and status = 'PENDING'", discordId, guildId)
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

    @Transactional
    public void markTasksDone(List<RoleSyncOutbox> tasks) {
        for (RoleSyncOutbox task : tasks) {
            RoleSyncOutbox managedTask = RoleSyncOutbox.findById(task.id);
            if (managedTask != null) {
                managedTask.status = "DONE";
                managedTask.updatedAt = Instant.now();
                managedTask.persist();
            }
        }
    }

    @Transactional
    public void markTasksFailed(List<RoleSyncOutbox> tasks) {
        for (RoleSyncOutbox task : tasks) {
            RoleSyncOutbox managedTask = RoleSyncOutbox.findById(task.id);
            if (managedTask != null) {
                managedTask.status = "FAILED";
                managedTask.updatedAt = Instant.now();
                managedTask.persist();
            }
        }
    }

    @Transactional
    public void handleGroupFailure(List<RoleSyncOutbox> tasks) {
        for (RoleSyncOutbox task : tasks) {
            RoleSyncOutbox managedTask = RoleSyncOutbox.findById(task.id);
            if (managedTask != null) {
                managedTask.retryCount++;
                if (managedTask.retryCount >= 5) {
                    managedTask.status = "FAILED";
                } else {
                    managedTask.status = "PENDING"; // Return to queue
                }
                managedTask.updatedAt = Instant.now();
                managedTask.persist();
            }
        }
    }
}
