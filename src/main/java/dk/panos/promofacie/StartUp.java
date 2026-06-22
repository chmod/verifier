package dk.panos.promofacie;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class StartUp {
    private static final Logger log = LoggerFactory.getLogger(StartUp.class);

    @Inject
    EntityManager entityManager;

    @Transactional
    void onStart(@Observes StartupEvent ev) {
        try {
            log.info("[StartUp] Dropping legacy constraint uq_guild_role_policy if exists...");
            entityManager.createNativeQuery("ALTER TABLE guild_role_rules DROP CONSTRAINT IF EXISTS uq_guild_role_policy").executeUpdate();
            log.info("[StartUp] Legacy constraint dropped successfully.");
        } catch (Exception e) {
            log.warn("[StartUp] Failed to drop legacy constraint: {}", e.getMessage());
        }
    }
}