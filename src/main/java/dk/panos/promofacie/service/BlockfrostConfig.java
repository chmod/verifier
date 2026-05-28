package dk.panos.promofacie.service;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class BlockfrostConfig {
    @ConfigProperty(name = "blockfrost_api_key")
    String projectId;

    public String projectId() { return projectId; }
}