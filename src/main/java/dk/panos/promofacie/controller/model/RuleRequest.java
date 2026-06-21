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
        List<CriteriaRequest> criteria
) {}
