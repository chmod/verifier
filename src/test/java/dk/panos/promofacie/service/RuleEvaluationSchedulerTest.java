package dk.panos.promofacie.service;

import dk.panos.promofacie.db.PendingRuleEvaluation;
import dk.panos.promofacie.db.GuildRoleRule;
import dk.panos.promofacie.db.Wallet;
import dk.panos.promofacie.db.TargetState;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.concurrent.Task;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RuleEvaluationSchedulerTest {

    @Test
    @SuppressWarnings("unchecked")
    void testRuleEvaluationSchedulerFlows() throws Exception {
        RuleEvaluationScheduler scheduler = Mockito.spy(new RuleEvaluationScheduler());
        JDA jda = mock(JDA.class);
        scheduler.jda = jda;

        RoleEvaluationService roleEvaluationService = mock(RoleEvaluationService.class);
        scheduler.roleEvaluationService = roleEvaluationService;

        // Tasks setup
        PendingRuleEvaluation task1 = new PendingRuleEvaluation();
        task1.id = 100L;
        task1.guildId = "guild-1";
        task1.ruleId = 1L;
        task1.status = "PENDING";
        
        doReturn(List.of(task1)).when(scheduler).acquirePendingEvaluations();

        Guild guild = mock(Guild.class);
        when(jda.getGuildById("guild-1")).thenReturn(guild);
        when(guild.isLoaded()).thenReturn(true);

        Member member1 = mock(Member.class);
        when(member1.getId()).thenReturn("user-1");
        Member member2 = mock(Member.class);
        when(member2.getId()).thenReturn("user-2");

        when(guild.getMembers()).thenReturn(List.of(member1, member2));

        GuildRoleRule rule = new GuildRoleRule();
        rule.id = 1L;
        rule.guildId = "guild-1";
        rule.roleId = "role-gold";
        rule.policyId = "policy-1";
        rule.minQuantity = 10L;

        doReturn(rule).when(scheduler).getRuleById(1L);

        // Wallets
        Wallet w1 = new Wallet();
        w1.setAddress("address-1");
        w1.setDiscordId("user-1");

        // user-1 has wallet, user-2 has no wallet
        doReturn(List.of(w1)).when(scheduler).getWalletsByDiscordId("user-1");
        doReturn(List.of()).when(scheduler).getWalletsByDiscordId("user-2");

        // Inventory slot for user-1
        doReturn(500L).when(scheduler).getMaxInventorySlot(List.of("address-1"));

        // Compliance evaluation mock
        when(roleEvaluationService.evaluateRuleCompliance("user-1", List.of("address-1"), rule)).thenReturn(true);
        when(roleEvaluationService.evaluateRuleCompliance("user-2", List.of(), rule)).thenReturn(false);

        doNothing().when(scheduler).markTasksDone(any());

        // Act
        scheduler.processPendingEvaluations();

        // Assert
        verify(roleEvaluationService, times(1)).upsertOutboxTask("user-1", "guild-1", "role-gold", TargetState.PRESENT, 500L);
        verify(roleEvaluationService, times(1)).upsertOutboxTask("user-2", "guild-1", "role-gold", TargetState.ABSENT, 0L);
        verify(scheduler, times(1)).markTasksDone(argThat(list -> list.size() == 1 && list.get(0).id == 100L));
    }


    @Test
    @SuppressWarnings("unchecked")
    void testPartialFailureIsRetryable() throws Exception {
        RuleEvaluationScheduler scheduler = Mockito.spy(new RuleEvaluationScheduler());
        JDA jda = mock(JDA.class);
        scheduler.jda = jda;

        RoleEvaluationService roleEvaluationService = mock(RoleEvaluationService.class);
        scheduler.roleEvaluationService = roleEvaluationService;

        PendingRuleEvaluation task1 = new PendingRuleEvaluation();
        task1.id = 100L;
        task1.guildId = "guild-1";
        task1.ruleId = 1L;
        task1.status = "PENDING";
        
        doReturn(List.of(task1)).when(scheduler).acquirePendingEvaluations();

        Guild guild = mock(Guild.class);
        when(jda.getGuildById("guild-1")).thenReturn(guild);
        when(guild.isLoaded()).thenReturn(true);

        Member member1 = mock(Member.class);
        when(member1.getId()).thenReturn("user-1");
        when(guild.getMembers()).thenReturn(List.of(member1));

        GuildRoleRule rule = new GuildRoleRule();
        rule.id = 1L;
        rule.guildId = "guild-1";
        rule.roleId = "role-gold";
        rule.policyId = "policy-1";

        doReturn(rule).when(scheduler).getRuleById(1L);
        doReturn(List.of()).when(scheduler).getWalletsByDiscordId("user-1");

        // Force runtime exception mid-processing during upsert to simulate DB error
        doThrow(new RuntimeException("Simulated Database Error"))
            .when(roleEvaluationService).upsertOutboxTask(any(), any(), any(), any(), anyLong());

        doNothing().when(scheduler).handleTasksFailure(any(), any());

        // Act
        scheduler.processPendingEvaluations();

        // Assert
        verify(scheduler, times(1)).handleTasksFailure(
            argThat(list -> list.size() == 1 && list.get(0).id == 100L), 
            eq("Simulated Database Error")
        );
        verify(scheduler, never()).markTasksDone(any());
    }
}
