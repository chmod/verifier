package dk.panos.promofacie.service;

import dk.panos.promofacie.controller.model.RuleRequest;
import dk.panos.promofacie.db.GuildRoleRule;
import dk.panos.promofacie.db.RuleTraitCriteria;
import dk.panos.promofacie.db.UserAssetInventory;
import org.junit.jupiter.api.Test;
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

    @Test
    void testEvaluateRoleEligibilityTieredRoles() {
        RoleEvaluationService service = spy(new RoleEvaluationService());

        // Role X: min = 1, max = null (unbounded)
        GuildRoleRule roleX = new GuildRoleRule();
        roleX.id = 1L;
        roleX.guildId = "guild-1";
        roleX.roleId = "role-x";
        roleX.policyId = "policy-y";
        roleX.minQuantity = 1L;
        roleX.maxQuantity = null;

        // Role X': min = 100, max = 999 (tiered intermediate)
        GuildRoleRule roleXPrime = new GuildRoleRule();
        roleXPrime.id = 2L;
        roleXPrime.guildId = "guild-1";
        roleXPrime.roleId = "role-x-prime";
        roleXPrime.policyId = "policy-y";
        roleXPrime.minQuantity = 100L;
        roleXPrime.maxQuantity = 999L;

        // Role X'': min = 1000, max = null (high tier)
        GuildRoleRule roleXDoublePrime = new GuildRoleRule();
        roleXDoublePrime.id = 3L;
        roleXDoublePrime.guildId = "guild-1";
        roleXDoublePrime.roleId = "role-x-double-prime";
        roleXDoublePrime.policyId = "policy-y";
        roleXDoublePrime.minQuantity = 1000L;
        roleXDoublePrime.maxQuantity = null;

        doReturn(List.of(roleX)).when(service).getRulesForRole("guild-1", "role-x");
        doReturn(List.of(roleXPrime)).when(service).getRulesForRole("guild-1", "role-x-prime");
        doReturn(List.of(roleXDoublePrime)).when(service).getRulesForRole("guild-1", "role-x-double-prime");

        // User has 1000 of policy-y
        doReturn(1000L).when(service).getRuleMatchingQuantity("user-1", List.of("addr-1"), roleX);
        doReturn(1000L).when(service).getRuleMatchingQuantity("user-1", List.of("addr-1"), roleXPrime);
        doReturn(1000L).when(service).getRuleMatchingQuantity("user-1", List.of("addr-1"), roleXDoublePrime);

        // Role X: 1000 >= 1 -> TRUE
        assertTrue(service.evaluateRoleEligibility("user-1", List.of("addr-1"), "guild-1", "role-x"));

        // Role X': 1000 > 999 max -> FALSE
        assertFalse(service.evaluateRoleEligibility("user-1", List.of("addr-1"), "guild-1", "role-x-prime"));

        // Role X'': 1000 >= 1000 -> TRUE
        assertTrue(service.evaluateRoleEligibility("user-1", List.of("addr-1"), "guild-1", "role-x-double-prime"));
    }

    @Test
    void testIsQuantityCompliantWithMaxQuantity() {
        RoleEvaluationService service = new RoleEvaluationService();

        // min 10, max 50
        assertTrue(service.isQuantityCompliant(10, 10L, 50L));
        assertTrue(service.isQuantityCompliant(30, 10L, 50L));
        assertTrue(service.isQuantityCompliant(50, 10L, 50L));
        assertFalse(service.isQuantityCompliant(9, 10L, 50L));
        assertFalse(service.isQuantityCompliant(51, 10L, 50L));

        // min 10, max null
        assertTrue(service.isQuantityCompliant(1000, 10L, null));
        assertFalse(service.isQuantityCompliant(5, 10L, null));
    }

    @Test
    void testRuleRequestNormalizePolicyId() {
        // EVM mixed case and 0X
        assertEquals("0x63ffd4aa844f4befcee1b4606e467d1d209a926b",
                RuleRequest.normalizePolicyId("0x63FFd4aA844f4BEFCee1B4606E467D1D209A926B"));
        assertEquals("0x63ffd4aa844f4befcee1b4606e467d1d209a926b",
                RuleRequest.normalizePolicyId("0X63FFD4AA844F4BEFCEE1B4606E467D1D209A926B"));

        // Cardano 56-hex chars
        assertEquals("79371b11f083d53ba85ec2171f45bdd1d503d1b43f02c5f29dc3ab37",
                RuleRequest.normalizePolicyId("79371B11F083D53BA85EC2171F45BDD1D503D1B43F02C5F29DC3AB37"));

        // Solana Base58 - case MUST be preserved
        assertEquals("n54ZwXEcLnc3o7zK48nhrLV4KTU5wWD4iq7Gvdt5tik",
                RuleRequest.normalizePolicyId("n54ZwXEcLnc3o7zK48nhrLV4KTU5wWD4iq7Gvdt5tik"));

        // Null and whitespace
        assertNull(RuleRequest.normalizePolicyId(null));
        assertEquals("0x63ffd4aa844f4befcee1b4606e467d1d209a926b",
                RuleRequest.normalizePolicyId("  0x63FFd4aA844f4BEFCee1B4606E467D1D209A926B  "));
    }

    @Test
    void testRuleRequestConstructorNormalizesPolicyId() {
        RuleRequest req = new RuleRequest("role-1", "0x63FFd4aA844f4BEFCee1B4606E467D1D209A926B", 1000L, null);
        assertEquals("0x63ffd4aa844f4befcee1b4606e467d1d209a926b", req.policyId());
    }

    @Test
    void testGetRuleMatchingQuantityCaseInsensitiveForEvm() {
        RoleEvaluationService service = spy(new RoleEvaluationService());

        GuildRoleRule rule = new GuildRoleRule();
        rule.id = 2366L;
        rule.policyId = "0x63FFd4aA844f4BEFCee1B4606E467D1D209A926B";
        rule.minQuantity = 1000L;

        UserAssetInventory item = new UserAssetInventory();
        item.id = new UserAssetInventory.InventoryId("addr-1", "0x63ffd4aa844f4befcee1b4606e467d1d209a926b", "");
        item.quantity = 5000L;

        doReturn(List.of(item)).when(service).findInventoryForPolicy(
                eq(List.of("addr-1")),
                eq("0x63FFd4aA844f4BEFCee1B4606E467D1D209A926B"),
                eq("0x63ffd4aa844f4befcee1b4606e467d1d209a926b")
        );

        long qty = service.getRuleMatchingQuantity("user-1", List.of("addr-1"), rule);
        assertEquals(5000L, qty);
    }

    // --- Equivalence: in-memory bulk path vs DB-backed per-user path -------------------------------

    private static GuildRoleRule rule(long id, String roleId, String policyId, long min, Long max, int group, boolean isAnd) {
        GuildRoleRule r = new GuildRoleRule();
        r.id = id;
        r.guildId = "guild-1";
        r.roleId = roleId;
        r.policyId = policyId;
        r.minQuantity = min;
        r.maxQuantity = max;
        r.ruleGroup = group;
        r.isAnd = isAnd;
        return r;
    }

    private static UserAssetInventory item(String address, String policyId, String asset, long qty, Map<String, String> traits) {
        UserAssetInventory i = new UserAssetInventory();
        i.id = new UserAssetInventory.InventoryId(address, policyId, asset);
        i.quantity = qty;
        i.traits = traits;
        return i;
    }

    @Test
    void testInMemoryEvaluationMatchesDbBackedEvaluation() {
        String cardanoUpper = "D620AEF3DE3244CDBA016CB9616A6516679C0A1FE560FFD9152452B5";
        String cardanoLower = cardanoUpper.toLowerCase();
        String evmMixed = "0x63FFd4aA844f4BEFCee1B4606E467D1D209A926B";
        String evmLower = evmMixed.toLowerCase();

        // role-and: AND group (cardano upper-case rule + trait-gated NFT rule)
        GuildRoleRule andCardano = rule(1, "role-and", cardanoUpper, 100, null, 1, true);
        GuildRoleRule andTrait = rule(2, "role-and", "policy-nft", 2, null, 1, true);
        RuleTraitCriteria golden = new RuleTraitCriteria();
        golden.traitKey = "Background";
        golden.traitValue = "Golden";
        andTrait.addCriteria(golden);
        // role-sum: SUM group with a max, OR'ed with a single EVM rule group
        GuildRoleRule sumA = rule(3, "role-sum", "policy-a", 5, 10L, 2, false);
        GuildRoleRule sumB = rule(4, "role-sum", "policy-b", 5, 10L, 2, false);
        GuildRoleRule evm = rule(5, "role-sum", evmMixed, 1000, null, 3, false);
        Map<String, List<GuildRoleRule>> rulesByRole = Map.of(
                "role-and", List.of(andCardano, andTrait),
                "role-sum", List.of(sumA, sumB, evm));

        // Inventory rows as stored (policy ids as the indexers write them)
        List<UserAssetInventory> all = List.of(
                item("addr-1", cardanoLower, "snek", 150, Map.of()),
                item("addr-1", "policy-nft", "nft-1", 1, Map.of("Background", "Golden")),
                item("addr-2", "policy-nft", "nft-2", 1, Map.of("Background", "golden")),
                item("addr-2", "policy-nft", "nft-3", 1, Map.of("Background", "Blue")),
                item("addr-2", "policy-a", "", 8, Map.of()),
                item("addr-3", "policy-a", "", 12, Map.of()),
                item("addr-3", "policy-b", "", 12, Map.of()),
                item("addr-4", evmLower, "", 1500, Map.of()),
                item("addr-5", "policy-b", "", 3, Map.of()));

        // Users with differing wallet sets, including one with no wallets
        Map<String, List<String>> users = Map.of(
                "u-and-pass", List.of("addr-1", "addr-2"),
                "u-and-fail", List.of("addr-1"),
                "u-sum-over-max", List.of("addr-3"),
                "u-evm", List.of("addr-4"),
                "u-sum-pass", List.of("addr-2", "addr-5"),
                "u-none", List.of());

        RoleEvaluationService service = spy(new RoleEvaluationService());
        // Simulate the DB query of findInventoryForPolicy: exact equality on raw or normalized policy id
        doAnswer(inv -> {
            List<String> addrs = inv.getArgument(0);
            String raw = inv.getArgument(1);
            String norm = inv.getArgument(2);
            return all.stream()
                    .filter(i -> addrs.contains(i.id.stakeAddress))
                    .filter(i -> i.id.policyId.equals(raw) || i.id.policyId.equals(norm))
                    .toList();
        }).when(service).findInventoryForPolicy(any(), any(), any());
        doAnswer(inv -> rulesByRole.get((String) inv.getArgument(1))).when(service).getRulesForRole(eq("guild-1"), any());

        int eligibleCount = 0;
        for (Map.Entry<String, List<String>> user : users.entrySet()) {
            List<UserAssetInventory> userInventory = all.stream()
                    .filter(i -> user.getValue().contains(i.id.stakeAddress))
                    .toList();
            for (String roleId : rulesByRole.keySet()) {
                boolean dbBacked = service.evaluateRoleEligibility(user.getKey(), user.getValue(), "guild-1", roleId);
                boolean inMemory = service.evaluateRules(user.getKey(), rulesByRole.get(roleId),
                        r -> service.matchingQuantityInMemory(userInventory, r));
                assertEquals(dbBacked, inMemory, "Mismatch for " + user.getKey() + " / " + roleId);
                if (inMemory) eligibleCount++;
            }
        }
        // Sanity: the fixture exercises both outcomes (u-and-pass, u-evm, u-sum-pass are eligible)
        assertEquals(3, eligibleCount);
    }

    @Test
    void testOutboxDecision() {
        RoleEvaluationService service = new RoleEvaluationService();
        dk.panos.promofacie.db.RoleSyncOutbox pendingPresent = new dk.panos.promofacie.db.RoleSyncOutbox();
        pendingPresent.status = "PENDING";
        pendingPresent.targetState = dk.panos.promofacie.db.TargetState.PRESENT;

        // Already in sync, nothing queued -> skip
        assertFalse(service.isOutboxTaskNeeded(false, dk.panos.promofacie.db.TargetState.ABSENT, null));
        assertFalse(service.isOutboxTaskNeeded(true, dk.panos.promofacie.db.TargetState.PRESENT, null));
        // Out of sync -> write
        assertTrue(service.isOutboxTaskNeeded(true, dk.panos.promofacie.db.TargetState.ABSENT, null));
        // Stale pending PRESENT for a member who no longer qualifies (and doesn't hold the role) -> overwrite
        assertTrue(service.isOutboxTaskNeeded(false, dk.panos.promofacie.db.TargetState.ABSENT, pendingPresent));
        // Pending task already targets the same state -> skip
        assertFalse(service.isOutboxTaskNeeded(false, dk.panos.promofacie.db.TargetState.PRESENT, pendingPresent));
    }
}
