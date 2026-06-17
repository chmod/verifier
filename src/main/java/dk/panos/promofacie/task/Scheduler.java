package dk.panos.promofacie.task;

import dk.panos.promofacie.service.CardanoRoleService;
import io.quarkus.arc.profile.IfBuildProfile;
import io.quarkus.scheduler.Scheduled;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
@IfBuildProfile("prod")
public class Scheduler {
    private static final Logger log = LoggerFactory.getLogger(Scheduler.class);


    @Inject
    CardanoRoleService cardanoRoleService;

    @Scheduled(every = "1h")
    public void betskiCardano() {
        try {
            cardanoRoleService.applyRoles();
            log.info("Finished. Next in 15m");
        } catch (Exception err) {
            log.error("Error during assignment", err);
        }
    }
}