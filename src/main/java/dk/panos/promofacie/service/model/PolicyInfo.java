package dk.panos.promofacie.service.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PolicyInfo(
        @JsonProperty("friendly_name")
        String friendlyName,

        @JsonProperty("blockchain")
        String blockchain
) {
}
