package dk.panos.promofacie.service;

import dk.panos.promofacie.db.GuildRoleRule;
import dk.panos.promofacie.db.PendingRuleEvaluation;
import dk.panos.promofacie.db.PendingUserEvaluation;
import dk.panos.promofacie.db.RoleSyncOutbox;
import dk.panos.promofacie.db.TargetState;
import dk.panos.promofacie.db.UserAssetInventory;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RuleEvaluationSchedulerTest {

    private static Member member(String id, Role... roles) {
        Member m = mock(Member.class);
        when(m.getId()).thenReturn(id);
        when(m.getRoles()).thenReturn(List.of(roles));
        return m;
    }

    private static PendingRuleEvaluation task(long id, long ruleId) {
        PendingRuleEvaluation t = new PendingRuleEvaluation();
        t.id = id;
        t.guildId = "guild-1";
        t.ruleId = ruleId;
        t.status = "PENDING";
        return t;
    }

    private static GuildRoleRule goldRule() {
        GuildRoleRule rule = new GuildRoleRule();
        rule.id = 1L;
        rule.guildId = "guild-1";
        rule.roleId = "role-gold";
        rule.policyId = "policy-1";
        rule.minQuantity = 10L;
        return rule;
    }

    /** Scheduler wired with a real RoleEvaluationService (DB write stubbed) and a single-guild setup. */
    private record Fixture(RuleEvaluationScheduler scheduler, RoleEvaluationService service) {}

    private static Fixture fixture(List<Member> members, Map<String, RoleSyncOutbox> outbox) {
        RuleEvaluationScheduler scheduler = Mockito.spy(new RuleEvaluationScheduler());
        JDA jda = mock(JDA.class);
        scheduler.jda = jda;
        RoleEvaluationService service = spy(new RoleEvaluationService());
        scheduler.roleEvaluationService = service;
        doNothing().when(service).upsertOutboxRow(any(), any(), any(), any(), anyLong());

        Guild guild = mock(Guild.class);
        when(jda.getGuildById("guild-1")).thenReturn(guild);
        when(guild.getMembers()).thenReturn(members);

        GuildRoleRule rule = goldRule();
        doReturn(List.of(task(100L, 1L))).when(scheduler).acquirePendingEvaluations();
        doReturn(Set.of("role-gold")).when(scheduler).loadRoleIdsForRules(List.of(1L));
        doReturn(List.of(rule)).when(scheduler).loadRulesForRoles("guild-1", Set.of("role-gold"));

        UserAssetInventory holding = new UserAssetInventory();
        holding.id = new UserAssetInventory.InventoryId("address-1", "policy-1", "");
        holding.quantity = 15L;
        holding.traits = Map.of();
        doReturn(Map.of("user-1", List.of("address-1"))).when(scheduler).loadWalletAddressesByDiscordId();
        doReturn(Map.of("address-1", List.of(holding))).when(scheduler).loadInventoryByAddress(Set.of("policy-1"));
        doReturn(outbox).when(scheduler).loadOutboxByUserRole("guild-1", Set.of("role-gold"));

        doNothing().when(scheduler).markTasksDone(any());
        doNothing().when(scheduler).handleTasksFailure(any(), any());
        return new Fixture(scheduler, service);
    }

    @Test
    void testBulkRuleEvaluationWritesOnlyNeededOutboxRows() {
        Role gold = mock(Role.class);
        when(gold.getId()).thenReturn("role-gold");

        RoleSyncOutbox stalePendingPresent = new RoleSyncOutbox();
        stalePendingPresent.discordId = "user-4";
        stalePendingPresent.roleId = "role-gold";
        stalePendingPresent.status = "PENDING";
        stalePendingPresent.targetState = TargetState.PRESENT;

        Fixture f = fixture(List.of(
                member("user-1"),        // wallet with 15 >= 10, lacks role      -> PRESENT
                member("user-2", gold),  // no wallet, holds role                 -> ABSENT
                member("user-3"),        // no wallet, no role, nothing queued    -> nothing
                member("user-4")         // no wallet, no role, stale PENDING PRESENT -> ABSENT
        ), Map.of("user-4|role-gold", stalePendingPresent));

        f.scheduler().processPendingRuleEvaluations();

        ArgumentCaptor<Long> versions = ArgumentCaptor.forClass(Long.class);
        verify(f.service()).upsertOutboxRow(eq("user-1"), eq("guild-1"), eq("role-gold"), eq(TargetState.PRESENT), versions.capture());
        verify(f.service()).upsertOutboxRow(eq("user-2"), eq("guild-1"), eq("role-gold"), eq(TargetState.ABSENT), versions.capture());
        verify(f.service()).upsertOutboxRow(eq("user-4"), eq("guild-1"), eq("role-gold"), eq(TargetState.ABSENT), versions.capture());
        verify(f.service(), never()).upsertOutboxRow(eq("user-3"), any(), any(), any(), anyLong());
        // One evaluation version for the whole run
        assertEquals(1, versions.getAllValues().stream().distinct().count());
        // No per-member DB access in the bulk path
        verify(f.service(), never()).getRuleMatchingQuantity(any(), any(), any());
        verify(f.service(), never()).isOutboxTaskNeeded(any(String.class), any(), any(), any());
        verify(f.scheduler(), times(1)).markTasksDone(argThat(list -> list.size() == 1 && list.get(0).id == 100L));
    }

    @Test
    void testPartialFailureIsRetryable() {
        Fixture f = fixture(List.of(member("user-1")), Map.of());
        doThrow(new RuntimeException("Simulated Database Error"))
                .when(f.service()).upsertOutboxRow(any(), any(), any(), any(), anyLong());

        f.scheduler().processPendingRuleEvaluations();

        verify(f.scheduler(), times(1)).handleTasksFailure(
                argThat(list -> list.size() == 1 && list.get(0).id == 100L),
                eq("Simulated Database Error")
        );
        verify(f.scheduler(), never()).markTasksDone(any());
    }

    @Test
    void testProcessPendingUserEvaluations() {
        RuleEvaluationScheduler scheduler = Mockito.spy(new RuleEvaluationScheduler());
        RoleEvaluationService roleEvaluationService = mock(RoleEvaluationService.class);
        RoleSyncScheduler roleSyncScheduler = mock(RoleSyncScheduler.class);
        scheduler.roleEvaluationService = roleEvaluationService;
        scheduler.roleSyncScheduler = roleSyncScheduler;

        PendingUserEvaluation userTask = new PendingUserEvaluation();
        userTask.id = 200L;
        userTask.guildId = "guild-1";
        userTask.discordId = "user-123";
        userTask.status = "PROCESSING";

        doReturn(List.of(userTask)).when(scheduler).acquirePendingUserEvaluations();
        doNothing().when(scheduler).markUserTaskDone(any());

        scheduler.processPendingUserEvaluations();

        verify(roleEvaluationService, times(1)).recalculateUserRolesInGuild("user-123", "guild-1");
        verify(roleSyncScheduler, times(1)).processUserGuildGroup("user-123", "guild-1");
        verify(scheduler, times(1)).markUserTaskDone(userTask);
        // User evaluations no longer piggyback on the rule evaluation job
        verify(scheduler, never()).acquirePendingEvaluations();
    }
}
