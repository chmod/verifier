package dk.panos.promofacie.controller.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record CriteriaRequest(
        @JsonProperty("traitKey")
        String traitKey,

        @JsonProperty("traitValue")
        String traitValue
) {}
