package dk.panos.promofacie.controller.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record RuleRequest(
        @JsonProperty("roleId")
        String roleId,

        @JsonProperty("policyId")
        String policyId,

        @JsonProperty("minQuantity")
        Long minQuantity,

        @JsonProperty("maxQuantity")
        Long maxQuantity,

        @JsonProperty("criteria")
        List<CriteriaRequest> criteria,

        @JsonProperty("group")
        Integer group,

        @JsonProperty("isAnd")
        Boolean isAnd,

        @JsonProperty("blockchain")
        String blockchain,

        @JsonProperty("chain")
        String chain
) {
    public RuleRequest(String roleId, String policyId, Long minQuantity, List<CriteriaRequest> criteria) {
        this(roleId, policyId, minQuantity, null, criteria, null, null, null, null);
    }

    public RuleRequest(String roleId, String policyId, Long minQuantity, List<CriteriaRequest> criteria, Integer group) {
        this(roleId, policyId, minQuantity, null, criteria, group, null, null, null);
    }

    public RuleRequest(String roleId, String policyId, Long minQuantity, Long maxQuantity, List<CriteriaRequest> criteria, Integer group) {
        this(roleId, policyId, minQuantity, maxQuantity, criteria, group, null, null, null);
    }

    public RuleRequest(String roleId, String policyId, Long minQuantity, Long maxQuantity, List<CriteriaRequest> criteria, Integer group, Boolean isAnd) {
        this(roleId, policyId, minQuantity, maxQuantity, criteria, group, isAnd, null, null);
    }

    public dk.panos.promofacie.db.Chain getResolvedChain() {
        String c = blockchain != null && !blockchain.isBlank() ? blockchain : chain;
        if (c != null && !c.isBlank()) {
            try {
                return dk.panos.promofacie.db.Chain.valueOf(c.trim().toUpperCase());
            } catch (Exception ignored) {}
        }
        if (policyId != null && (policyId.startsWith("0x") || policyId.startsWith("0X"))) {
            return dk.panos.promofacie.db.Chain.ROBINHOOD;
        }
        return dk.panos.promofacie.db.Chain.CARDANO;
    }
}
