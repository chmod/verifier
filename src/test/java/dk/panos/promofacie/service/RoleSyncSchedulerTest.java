package dk.panos.promofacie.service;

import dk.panos.promofacie.db.RoleSyncOutbox;
import dk.panos.promofacie.db.TargetState;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.requests.restaction.AuditableRestAction;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RoleSyncSchedulerTest {

    @Test
    @SuppressWarnings("unchecked")
    void testSchedulerGroupsTasksAndExecutesBatch() throws Exception {
        // Arrange
        RoleSyncScheduler scheduler = Mockito.spy(new RoleSyncScheduler());
        JDA jda = mock(JDA.class);
        scheduler.jda = jda;

        // Group 1: user-1, guild-1
        List<Object[]> pendingGroups = new ArrayList<>();
        pendingGroups.add(new Object[]{"user-1", "guild-1"});
        doReturn(pendingGroups).when(scheduler).getPendingGroups();

        RoleSyncOutbox task1 = new RoleSyncOutbox();
        task1.id = 1L;
        task1.discordId = "user-1";
        task1.guildId = "guild-1";
        task1.roleId = "role-1";
        task1.targetState = TargetState.PRESENT;

        RoleSyncOutbox task2 = new RoleSyncOutbox();
        task2.id = 2L;
        task2.discordId = "user-1";
        task2.guildId = "guild-1";
        task2.roleId = "role-2";
        task2.targetState = TargetState.ABSENT;

        List<RoleSyncOutbox> tasks = List.of(task1, task2);
        doReturn(tasks).when(scheduler).acquirePendingTasksWithLock("user-1", "guild-1");

        Guild guild = mock(Guild.class);
        when(jda.getGuildById("guild-1")).thenReturn(guild);

        Member member = mock(Member.class);
        when(guild.getMemberById("user-1")).thenReturn(member);

        Role role1 = mock(Role.class);
        Role role2 = mock(Role.class);
        when(guild.getRoleById("role-1")).thenReturn(role1);
        when(guild.getRoleById("role-2")).thenReturn(role2);

        AuditableRestAction<Void> restAction = mock(AuditableRestAction.class);
        CompletableFuture<Void> future = CompletableFuture.completedFuture(null);
        when(restAction.submit()).thenReturn(future);
        
        when(guild.modifyMemberRoles(eq(member), any(List.class), any(List.class))).thenReturn(restAction);

        doNothing().when(scheduler).markTasksDone(any());

        // Act
        scheduler.processOutboxQueue();

        // Assert
        verify(guild, times(1)).modifyMemberRoles(
                eq(member),
                argThat(list -> list.contains(role1) && list.size() == 1),
                argThat(list -> list.contains(role2) && list.size() == 1)
        );
        verify(scheduler, times(1)).markTasksDone(tasks);
    }

    @Test
    @SuppressWarnings("unchecked")
    void testDesiredStateOverwriteFixesRaceCondition() throws Exception {
        // Reproduces the bug scenario:
        // 1. Initially user is ABSENT (status PENDING)
        // 2. ABSENT fails -> retryCount increments, stays PENDING
        // 3. New update comes setting targetState = PRESENT
        // 4. In our Desired-State model, this upserts the same row, setting targetState = PRESENT
        // 5. Scheduler processes group -> executes JDA batch with PRESENT role -> final state correct (PRESENT)

        RoleSyncScheduler scheduler = Mockito.spy(new RoleSyncScheduler());
        JDA jda = mock(JDA.class);
        scheduler.jda = jda;

        List<Object[]> pendingGroups = new ArrayList<>();
        pendingGroups.add(new Object[]{"user-1", "guild-1"});
        doReturn(pendingGroups).when(scheduler).getPendingGroups();

        // Row has been overwritten to PRESENT by upsert
        RoleSyncOutbox task = new RoleSyncOutbox();
        task.id = 1L;
        task.discordId = "user-1";
        task.guildId = "guild-1";
        task.roleId = "role-1";
        task.targetState = TargetState.PRESENT; // overwritten from ABSENT
        task.retryCount = 0; // reset by upsert

        doReturn(List.of(task)).when(scheduler).acquirePendingTasksWithLock("user-1", "guild-1");

        Guild guild = mock(Guild.class);
        when(jda.getGuildById("guild-1")).thenReturn(guild);

        Member member = mock(Member.class);
        when(guild.getMemberById("user-1")).thenReturn(member);

        Role role1 = mock(Role.class);
        when(guild.getRoleById("role-1")).thenReturn(role1);

        AuditableRestAction<Void> restAction = mock(AuditableRestAction.class);
        CompletableFuture<Void> future = CompletableFuture.completedFuture(null);
        when(restAction.submit()).thenReturn(future);

        // Expect modifyMemberRoles to add role1, and NOT remove it
        when(guild.modifyMemberRoles(eq(member), any(List.class), any(List.class))).thenReturn(restAction);
        doNothing().when(scheduler).markTasksDone(any());

        // Act
        scheduler.processOutboxQueue();

        // Assert
        verify(guild, times(1)).modifyMemberRoles(
                eq(member),
                argThat(list -> list.contains(role1) && list.size() == 1),
                argThat(list -> list.isEmpty())
        );
        verify(scheduler, times(1)).markTasksDone(List.of(task));
    }
}
