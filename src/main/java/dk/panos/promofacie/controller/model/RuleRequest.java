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

        @JsonProperty("criteria")
        List<CriteriaRequest> criteria,

        @JsonProperty("group")
        Integer group
) {
    public RuleRequest(String roleId, String policyId, Long minQuantity, List<CriteriaRequest> criteria) {
        this(roleId, policyId, minQuantity, criteria, null);
    }
}
