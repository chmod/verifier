package dk.panos.promofacie.controller.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record RuleUpdateResponse(
        @JsonProperty("policies")
        PoliciesDiff policies,

        @JsonProperty("roles")
        RolesDiff roles
) {
    public record PoliciesDiff(
            @JsonProperty("adds")
            List<String> adds,

            @JsonProperty("removals")
            List<String> removals
    ) {}

    public record RolesDiff(
            @JsonProperty("applying")
            List<String> applying,

            @JsonProperty("remove")
            List<String> remove
    ) {}
}
