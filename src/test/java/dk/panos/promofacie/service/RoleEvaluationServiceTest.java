package dk.panos.promofacie.service;

import dk.panos.promofacie.db.GuildRoleRule;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RoleEvaluationServiceTest {

    @Test
    void testEvaluateRoleEligibilityAggregated() {
        RoleEvaluationService service = spy(new RoleEvaluationService());

        // Setup mock rules
        GuildRoleRule rule1 = new GuildRoleRule();
        rule1.id = 1L;
        rule1.guildId = "guild-1";
        rule1.roleId = "role-vip";
        rule1.policyId = "policy-a";
        rule1.minQuantity = 5L;
        rule1.ruleGroup = 1;

        GuildRoleRule rule2 = new GuildRoleRule();
        rule2.id = 2L;
        rule2.guildId = "guild-1";
        rule2.roleId = "role-vip";
        rule2.policyId = "policy-b";
        rule2.minQuantity = 4L;
        rule2.ruleGroup = 1;

        // Total required for group 1 is 5 + 4 = 9 combined
        doReturn(List.of(rule1, rule2)).when(service).getRulesForRole("guild-1", "role-vip");

        // Scenario 1: User has 7 of policy-a and 2 of policy-b. Total = 9. Required = 9. Satisfied.
        doReturn(7L).when(service).getRuleMatchingQuantity("user-1", List.of("addr-1"), rule1);
        doReturn(2L).when(service).getRuleMatchingQuantity("user-1", List.of("addr-1"), rule2);

        boolean result1 = service.evaluateRoleEligibility("user-1", List.of("addr-1"), "guild-1", "role-vip");
        assertTrue(result1);

        // Scenario 2: User has 5 of policy-a and 3 of policy-b. Total = 8. Required = 9. Failed.
        doReturn(5L).when(service).getRuleMatchingQuantity("user-1", List.of("addr-1"), rule1);
        doReturn(3L).when(service).getRuleMatchingQuantity("user-1", List.of("addr-1"), rule2);

        boolean result2 = service.evaluateRoleEligibility("user-1", List.of("addr-1"), "guild-1", "role-vip");
        assertFalse(result2);
    }
}
