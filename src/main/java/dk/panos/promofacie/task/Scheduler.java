package dk.panos.promofacie.task;

import dk.panos.promofacie.service.RoleService;
import io.quarkus.arc.profile.IfBuildProfile;
import io.quarkus.scheduler.Scheduled;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
@IfBuildProfile("prod")
public class Scheduler {
    private static final Logger log = LoggerFactory.getLogger(Scheduler.class);

    @Inject
    RoleService roleService;

    @Scheduled(every = "15m")
    Uni<Void> cronJobWithExpressionInConfig() {
        log.info("Scheduling with cron job");
        return roleService.applyRoles().onItem()
                .invoke(any -> log.info("Finished. Next in 5m")).onFailure()
                .invoke(err -> {
                    log.error("Error during assignment", err);
                })
                .onFailure()
                .recoverWithNull();
    }
}
