package dk.panos.promofacie.controller;

import dk.panos.promofacie.controller.model.CriteriaRequest;
import dk.panos.promofacie.controller.model.RuleRequest;
import dk.panos.promofacie.controller.model.RuleUpdateResponse;
import dk.panos.promofacie.db.GuildRoleRule;
import dk.panos.promofacie.db.PendingRuleEvaluation;
import dk.panos.promofacie.kafka.model.TrackingCommand;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RuleResourceTest {

    @Test
    @SuppressWarnings("unchecked")
    void testUpdateRulesEmptyClear() {
        // Arrange
        RuleResource resource = spy(new RuleResource());
        Emitter<TrackingCommand> emitter = mock(Emitter.class);
        resource.trackingEmitter = emitter;

        when(emitter.send(any(TrackingCommand.class))).thenReturn(CompletableFuture.completedFuture(null));

        GuildRoleRule existingRule1 = mock(GuildRoleRule.class);
        existingRule1.id = 10L;
        existingRule1.guildId = "123456";
        existingRule1.roleId = "role-1";
        existingRule1.policyId = "policy-1";

        doReturn(List.of(existingRule1)).when(resource).getExistingRules("123456");
        doReturn(0L).when(resource).countPolicyInOtherGuilds("policy-1", "123456");
        doNothing().when(resource).deletePendingEvaluations(10L);
        doNothing().when(resource).deleteRule(existingRule1);
        doNothing().when(resource).flushSession();

        // Act
        Response response = resource.updateRules("123456", Collections.emptyList());

        // Assert
        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        RuleUpdateResponse body = (RuleUpdateResponse) response.getEntity();

        assertNotNull(body);
        assertTrue(body.policies().adds().isEmpty());
        assertEquals(List.of("policy-1"), body.policies().removals());
        assertTrue(body.roles().applying().isEmpty());
        assertEquals(List.of("role-1"), body.roles().remove());

        // Verify deletions called
        verify(resource, times(1)).deletePendingEvaluations(10L);
        verify(resource, times(1)).deleteRule(existingRule1);

        // Verify REMOVE_POLICY Kafka message was sent
        ArgumentCaptor<TrackingCommand> captor = ArgumentCaptor.forClass(TrackingCommand.class);
        verify(emitter, times(1)).send(captor.capture());
        TrackingCommand sentCommand = captor.getValue();
        assertEquals(TrackingCommand.Action.REMOVE_POLICY, sentCommand.action());
        assertEquals("policy-1", sentCommand.policyId());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testUpdateRulesAddAndRemove() {
        // Arrange
        RuleResource resource = spy(new RuleResource());
        Emitter<TrackingCommand> emitter = mock(Emitter.class);
        resource.trackingEmitter = emitter;

        when(emitter.send(any(TrackingCommand.class))).thenReturn(CompletableFuture.completedFuture(null));

        GuildRoleRule existingRule1 = mock(GuildRoleRule.class);
        existingRule1.id = 10L;
        existingRule1.guildId = "123456";
        existingRule1.roleId = "role-1";
        existingRule1.policyId = "policy-1";

        doReturn(List.of(existingRule1)).when(resource).getExistingRules("123456");
        doReturn(0L).when(resource).countPolicyGlobally("policy-2");
        doReturn(0L).when(resource).countPolicyInOtherGuilds("policy-1", "123456");
        doNothing().when(resource).deletePendingEvaluations(10L);
        doNothing().when(resource).deleteRule(existingRule1);
        doNothing().when(resource).persistRule(any(GuildRoleRule.class));
        doNothing().when(resource).persistPendingEvaluation(any(PendingRuleEvaluation.class));
        doNothing().when(resource).flushSession();

        // New rules: has policy-2 with role-2
        RuleRequest newRule = new RuleRequest(
                "role-2",
                "policy-2",
                2L,
                List.of(new CriteriaRequest("key1", "val1"))
        );

        // Act
        Response response = resource.updateRules("123456", List.of(newRule));

        // Assert
        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        RuleUpdateResponse body = (RuleUpdateResponse) response.getEntity();

        assertNotNull(body);
        assertEquals(List.of("policy-2"), body.policies().adds());
        assertEquals(List.of("policy-1"), body.policies().removals());
        assertEquals(List.of("role-2"), body.roles().applying());
        assertEquals(List.of("role-1"), body.roles().remove());

        // Verify deletions called
        verify(resource, times(1)).deletePendingEvaluations(10L);
        verify(resource, times(1)).deleteRule(existingRule1);

        // Verify new rule persisted
        ArgumentCaptor<GuildRoleRule> ruleCaptor = ArgumentCaptor.forClass(GuildRoleRule.class);
        verify(resource, times(1)).persistRule(ruleCaptor.capture());
        GuildRoleRule persisted = ruleCaptor.getValue();
        assertEquals("123456", persisted.guildId);
        assertEquals("role-2", persisted.roleId);
        assertEquals("policy-2", persisted.policyId);
        assertEquals(2L, persisted.minQuantity);
        assertEquals(1, persisted.criteria.size());
        assertEquals("key1", persisted.criteria.get(0).traitKey);
        assertEquals("val1", persisted.criteria.get(0).traitValue);

        // Verify pending evaluation persisted
        verify(resource, times(1)).persistPendingEvaluation(any(PendingRuleEvaluation.class));

        // Verify two tracking commands sent (ADD_POLICY for policy-2, REMOVE_POLICY for policy-1)
        ArgumentCaptor<TrackingCommand> captor = ArgumentCaptor.forClass(TrackingCommand.class);
        verify(emitter, times(2)).send(captor.capture());
        List<TrackingCommand> commands = captor.getAllValues();

        assertTrue(commands.stream().anyMatch(c -> c.action() == TrackingCommand.Action.ADD_POLICY && "policy-2".equals(c.policyId())));
        assertTrue(commands.stream().anyMatch(c -> c.action() == TrackingCommand.Action.REMOVE_POLICY && "policy-1".equals(c.policyId())));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testGetRules() {
        // Arrange
        RuleResource resource = spy(new RuleResource());

        GuildRoleRule rule = new GuildRoleRule();
        rule.id = 10L;
        rule.guildId = "123456";
        rule.roleId = "role-1";
        rule.policyId = "policy-1";
        rule.minQuantity = 5L;

        dk.panos.promofacie.db.RuleTraitCriteria criteria = new dk.panos.promofacie.db.RuleTraitCriteria();
        criteria.traitKey = "trait-k";
        criteria.traitValue = "trait-v";
        rule.addCriteria(criteria);

        doReturn(List.of(rule)).when(resource).getExistingRules("123456");

        // Act
        Response response = resource.getRules("123456");

        // Assert
        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        List<RuleRequest> body = (List<RuleRequest>) response.getEntity();

        assertNotNull(body);
        assertEquals(1, body.size());
        RuleRequest requestRule = body.get(0);
        assertEquals("role-1", requestRule.roleId());
        assertEquals("policy-1", requestRule.policyId());
        assertEquals(5L, requestRule.minQuantity());
        assertEquals(1, requestRule.criteria().size());
        assertEquals("trait-k", requestRule.criteria().get(0).traitKey());
        assertEquals("trait-v", requestRule.criteria().get(0).traitValue());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testUpdateRulesSyncsExistingAddresses() {
        // Arrange
        RuleResource resource = spy(new RuleResource());
        Emitter<TrackingCommand> emitter = mock(Emitter.class);
        resource.trackingEmitter = emitter;

        net.dv8tion.jda.api.JDA mockJda = mock(net.dv8tion.jda.api.JDA.class);
        net.dv8tion.jda.api.entities.Guild mockGuild = mock(net.dv8tion.jda.api.entities.Guild.class);
        net.dv8tion.jda.api.entities.Member mockMember = mock(net.dv8tion.jda.api.entities.Member.class);

        resource.jda = mockJda;
        when(mockJda.getGuildById("123456")).thenReturn(mockGuild);
        when(mockGuild.getMembers()).thenReturn(List.of(mockMember));
        when(mockMember.getId()).thenReturn("discord-user-1");

        when(emitter.send(any(TrackingCommand.class))).thenReturn(CompletableFuture.completedFuture(null));

        doReturn(Collections.emptyList()).when(resource).getExistingRules("123456");
        doReturn(0L).when(resource).countPolicyGlobally("policy-new");
        doNothing().when(resource).persistRule(any(GuildRoleRule.class));
        doNothing().when(resource).persistPendingEvaluation(any(PendingRuleEvaluation.class));
        doNothing().when(resource).flushSession();

        dk.panos.promofacie.db.Wallet mockWallet = new dk.panos.promofacie.db.Wallet();
        mockWallet.setAddress("addr123");
        mockWallet.setDiscordId("discord-user-1");
        doReturn(List.of(mockWallet)).when(resource).getWalletsForDiscordIds(List.of("discord-user-1"));

        RuleRequest newRule = new RuleRequest("role-new", "policy-new", 1L, Collections.emptyList());

        // Act
        Response response = resource.updateRules("123456", List.of(newRule));

        // Assert
        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());

        // Verify tracking commands sent: ADD_POLICY for policy-new and ADD_ADDRESS for addr123
        ArgumentCaptor<TrackingCommand> captor = ArgumentCaptor.forClass(TrackingCommand.class);
        verify(emitter, times(2)).send(captor.capture());
        List<TrackingCommand> sentCommands = captor.getAllValues();

        assertTrue(sentCommands.stream().anyMatch(c -> c.action() == TrackingCommand.Action.ADD_POLICY && "policy-new".equals(c.policyId())));
        assertTrue(sentCommands.stream().anyMatch(c -> c.action() == TrackingCommand.Action.ADD_ADDRESS && "addr123".equals(c.stakeAddress())));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testUpdateRulesWithCustomGroup() {
        // Arrange
        RuleResource resource = spy(new RuleResource());
        Emitter<TrackingCommand> emitter = mock(Emitter.class);
        resource.trackingEmitter = emitter;

        when(emitter.send(any(TrackingCommand.class))).thenReturn(CompletableFuture.completedFuture(null));

        doReturn(Collections.emptyList()).when(resource).getExistingRules("123456");
        doReturn(0L).when(resource).countPolicyGlobally("policy-group");
        doNothing().when(resource).persistRule(any(GuildRoleRule.class));
        doNothing().when(resource).persistPendingEvaluation(any(PendingRuleEvaluation.class));
        doNothing().when(resource).flushSession();

        RuleRequest newRule = new RuleRequest(
                "role-group",
                "policy-group",
                1L,
                Collections.emptyList(),
                3 // custom group
        );

        // Act
        Response response = resource.updateRules("123456", List.of(newRule));

        // Assert
        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());

        ArgumentCaptor<GuildRoleRule> ruleCaptor = ArgumentCaptor.forClass(GuildRoleRule.class);
        verify(resource, times(1)).persistRule(ruleCaptor.capture());
        GuildRoleRule persisted = ruleCaptor.getValue();
        assertEquals("123456", persisted.guildId);
        assertEquals("role-group", persisted.roleId);
        assertEquals("policy-group", persisted.policyId);
        assertEquals(3, persisted.ruleGroup);
    }

    @Test
    @SuppressWarnings("unchecked")
    void testGetRulesWithGroups() {
        // Arrange
        RuleResource resource = spy(new RuleResource());

        GuildRoleRule rule1 = new GuildRoleRule();
        rule1.id = 10L;
        rule1.guildId = "123456";
        rule1.roleId = "role-1";
        rule1.policyId = "policy-1";
        rule1.minQuantity = 5L;
        rule1.ruleGroup = 2; // custom group

        // rule2 is in the same group, so count > 1
        GuildRoleRule rule2 = new GuildRoleRule();
        rule2.id = 11L;
        rule2.guildId = "123456";
        rule2.roleId = "role-1";
        rule2.policyId = "policy-2";
        rule2.minQuantity = 10L;
        rule2.ruleGroup = 2; // custom group

        // rule3 is in its own group (standalone), so count == 1
        GuildRoleRule rule3 = new GuildRoleRule();
        rule3.id = 12L;
        rule3.guildId = "123456";
        rule3.roleId = "role-1";
        rule3.policyId = "policy-3";
        rule3.minQuantity = 1L;
        rule3.ruleGroup = 3; // standalone group

        doReturn(List.of(rule1, rule2, rule3)).when(resource).getExistingRules("123456");

        // Act
        Response response = resource.getRules("123456");

        // Assert
        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        List<RuleRequest> body = (List<RuleRequest>) response.getEntity();

        assertNotNull(body);
        assertEquals(3, body.size());

        // rule1 (part of group 2 of size 2) -> returns 2
        RuleRequest requestRule1 = body.stream().filter(r -> "policy-1".equals(r.policyId())).findFirst().orElseThrow();
        assertEquals(2, requestRule1.group());

        // rule2 (part of group 2 of size 2) -> returns 2
        RuleRequest requestRule2 = body.stream().filter(r -> "policy-2".equals(r.policyId())).findFirst().orElseThrow();
        assertEquals(2, requestRule2.group());

        // rule3 (part of group 3 of size 1) -> returns null
        RuleRequest requestRule3 = body.stream().filter(r -> "policy-3".equals(r.policyId())).findFirst().orElseThrow();
        assertNull(requestRule3.group());
    }
}
