package dk.panos.promofacie.service;

import dk.panos.promofacie.db.GuildRoleRule;
import dk.panos.promofacie.db.RuleTraitCriteria;
import dk.panos.promofacie.db.UserAssetInventory;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

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

    @Test
    void testEvaluateRoleEligibilityAndGroup() {
        RoleEvaluationService service = spy(new RoleEvaluationService());

        // Setup mock rules
        GuildRoleRule rule1 = new GuildRoleRule();
        rule1.id = 1L;
        rule1.guildId = "guild-1";
        rule1.roleId = "role-vip";
        rule1.policyId = "policy-a";
        rule1.minQuantity = 5L;
        rule1.ruleGroup = 1;
        rule1.isAnd = true;

        GuildRoleRule rule2 = new GuildRoleRule();
        rule2.id = 2L;
        rule2.guildId = "guild-1";
        rule2.roleId = "role-vip";
        rule2.policyId = "policy-b";
        rule2.minQuantity = 4L;
        rule2.ruleGroup = 1;
        rule2.isAnd = true;

        doReturn(List.of(rule1, rule2)).when(service).getRulesForRole("guild-1", "role-vip");

        // Scenario 1: User has 7 of policy-a (>=5) and 4 of policy-b (>=4). Satisfied.
        doReturn(7L).when(service).getRuleMatchingQuantity("user-1", List.of("addr-1"), rule1);
        doReturn(4L).when(service).getRuleMatchingQuantity("user-1", List.of("addr-1"), rule2);

        boolean result1 = service.evaluateRoleEligibility("user-1", List.of("addr-1"), "guild-1", "role-vip");
        assertTrue(result1);

        // Scenario 2: User has 7 of policy-a (>=5) but only 3 of policy-b (<4). Failed.
        doReturn(7L).when(service).getRuleMatchingQuantity("user-1", List.of("addr-1"), rule1);
        doReturn(3L).when(service).getRuleMatchingQuantity("user-1", List.of("addr-1"), rule2);

        boolean result2 = service.evaluateRoleEligibility("user-1", List.of("addr-1"), "guild-1", "role-vip");
        assertFalse(result2);
    }

    @Test
    void testEvaluateRoleEligibilityNonGroupAndMultipleGroupsOrPattern() {
        RoleEvaluationService service = spy(new RoleEvaluationService());

        // Setup mock rules:
        // Group 1: Individual non-group rule (Policy A, minQuantity 10)
        GuildRoleRule individualRule = new GuildRoleRule();
        individualRule.id = 1L;
        individualRule.guildId = "guild-1";
        individualRule.roleId = "role-vip";
        individualRule.policyId = "policy-a";
        individualRule.minQuantity = 10L;
        individualRule.ruleGroup = 1;
        individualRule.isAnd = false;

        // Group 2: AND group with two rules (Policy B minQuantity 2 AND Policy C minQuantity 3)
        GuildRoleRule group2Rule1 = new GuildRoleRule();
        group2Rule1.id = 2L;
        group2Rule1.guildId = "guild-1";
        group2Rule1.roleId = "role-vip";
        group2Rule1.policyId = "policy-b";
        group2Rule1.minQuantity = 2L;
        group2Rule1.ruleGroup = 2;
        group2Rule1.isAnd = true;

        GuildRoleRule group2Rule2 = new GuildRoleRule();
        group2Rule2.id = 3L;
        group2Rule2.guildId = "guild-1";
        group2Rule2.roleId = "role-vip";
        group2Rule2.policyId = "policy-c";
        group2Rule2.minQuantity = 3L;
        group2Rule2.ruleGroup = 2;
        group2Rule2.isAnd = true;

        doReturn(List.of(individualRule, group2Rule1, group2Rule2))
                .when(service).getRulesForRole("guild-1", "role-vip");

        // Scenario 1: User satisfies Group 1 (has 10 of policy-a) but NOT Group 2 (0 of policy-b/c).
        // Since it's an OR between groups, this should succeed.
        doReturn(10L).when(service).getRuleMatchingQuantity("user-1", List.of("addr-1"), individualRule);
        doReturn(0L).when(service).getRuleMatchingQuantity("user-1", List.of("addr-1"), group2Rule1);
        doReturn(0L).when(service).getRuleMatchingQuantity("user-1", List.of("addr-1"), group2Rule2);

        assertTrue(service.evaluateRoleEligibility("user-1", List.of("addr-1"), "guild-1", "role-vip"));

        // Scenario 2: User fails Group 1 (only 9 of policy-a) but satisfies Group 2 (has 2 of policy-b AND 3 of policy-c).
        // This should also succeed (OR pattern).
        doReturn(9L).when(service).getRuleMatchingQuantity("user-1", List.of("addr-1"), individualRule);
        doReturn(2L).when(service).getRuleMatchingQuantity("user-1", List.of("addr-1"), group2Rule1);
        doReturn(3L).when(service).getRuleMatchingQuantity("user-1", List.of("addr-1"), group2Rule2);

        assertTrue(service.evaluateRoleEligibility("user-1", List.of("addr-1"), "guild-1", "role-vip"));

        // Scenario 3: User fails Group 1 (9 of policy-a) AND fails Group 2 (only 1 of policy-b, 3 of policy-c).
        // This should fail.
        doReturn(9L).when(service).getRuleMatchingQuantity("user-1", List.of("addr-1"), individualRule);
        doReturn(1L).when(service).getRuleMatchingQuantity("user-1", List.of("addr-1"), group2Rule1);
        doReturn(3L).when(service).getRuleMatchingQuantity("user-1", List.of("addr-1"), group2Rule2);

        assertFalse(service.evaluateRoleEligibility("user-1", List.of("addr-1"), "guild-1", "role-vip"));
    }

    @Test
    void testSatisfiesCriteria() {
        RoleEvaluationService service = new RoleEvaluationService();

        GuildRoleRule rule = new GuildRoleRule();
        rule.policyId = "policy-abc";

        RuleTraitCriteria criteria1 = new RuleTraitCriteria();
        criteria1.traitKey = "Background";
        criteria1.traitValue = "Golden";
        rule.addCriteria(criteria1);

        RuleTraitCriteria criteria2 = new RuleTraitCriteria();
        criteria2.traitKey = "Type";
        criteria2.traitValue = "Robot";
        rule.addCriteria(criteria2);

        // Item 1: matches all criteria
        UserAssetInventory item1 = new UserAssetInventory();
        item1.id = new UserAssetInventory.InventoryId("addr-1", "policy-abc", "asset-1");
        item1.traits = Map.of("Background", "Golden", "Type", "Robot");
        assertTrue(service.satisfiesCriteria(item1, rule));

        // Item 2: matches Background but fails Type
        UserAssetInventory item2 = new UserAssetInventory();
        item2.id = new UserAssetInventory.InventoryId("addr-1", "policy-abc", "asset-2");
        item2.traits = Map.of("Background", "Golden", "Type", "Human");
        assertFalse(service.satisfiesCriteria(item2, rule));

        // Item 3: missing background trait
        UserAssetInventory item3 = new UserAssetInventory();
        item3.id = new UserAssetInventory.InventoryId("addr-1", "policy-abc", "asset-3");
        item3.traits = Map.of("Type", "Robot");
        assertFalse(service.satisfiesCriteria(item3, rule));

        // Item 4: Case insensitive check
        UserAssetInventory item4 = new UserAssetInventory();
        item4.id = new UserAssetInventory.InventoryId("addr-1", "policy-abc", "asset-4");
        item4.traits = Map.of("Background", "GOLDEN", "Type", "robot");
        assertTrue(service.satisfiesCriteria(item4, rule));
    }
}
