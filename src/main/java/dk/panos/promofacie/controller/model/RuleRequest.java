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
        Boolean isAnd
) {
    public RuleRequest(String roleId, String policyId, Long minQuantity, List<CriteriaRequest> criteria) {
        this(roleId, policyId, minQuantity, null, criteria, null, null);
    }

    public RuleRequest(String roleId, String policyId, Long minQuantity, List<CriteriaRequest> criteria, Integer group) {
        this(roleId, policyId, minQuantity, null, criteria, group, null);
    }

    public RuleRequest(String roleId, String policyId, Long minQuantity, Long maxQuantity, List<CriteriaRequest> criteria, Integer group) {
        this(roleId, policyId, minQuantity, maxQuantity, criteria, group, null);
    }
}
