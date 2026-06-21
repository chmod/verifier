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
}
