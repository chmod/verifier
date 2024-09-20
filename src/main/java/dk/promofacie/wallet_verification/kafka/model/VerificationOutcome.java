package dk.promofacie.wallet_verification.kafka.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class VerificationOutcome{
    private final String address;
    private final String purpose;
    private final boolean outcome;
    private final String userId;

    @JsonCreator
    public VerificationOutcome(@JsonProperty("address") String address,
                               @JsonProperty("discordId") String userId,
                               @JsonProperty("purpose") String purpose,
                               @JsonProperty("outcome") boolean outcome) {
        this.address = address;
        this.purpose = purpose;
        this.outcome = outcome;
        this.userId = userId;
    }

    public String getAddress() {
        return address;
    }

    public String getPurpose() {
        return purpose;
    }

    public boolean isOutcome() {
        return outcome;
    }

    public String getUserId() {
        return userId;
    }
}