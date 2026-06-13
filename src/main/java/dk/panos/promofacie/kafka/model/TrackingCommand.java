package dk.panos.promofacie.kafka.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Generalized tracking command sent to the commands Kafka topic.
 * Supports adding/removing addresses and policy IDs.
 */
public record TrackingCommand(
        Action action,

        @JsonProperty("stake_address")
        String stakeAddress,

        @JsonProperty("policy_id")
        String policyId
) {
    public enum Action {
        ADD_ADDRESS,
        REMOVE_ADDRESS,
        ADD_POLICY,
        REMOVE_POLICY
    }
}
