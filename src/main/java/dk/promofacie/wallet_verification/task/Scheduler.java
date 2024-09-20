package dk.promofacie.wallet_verification.task;

import dk.promofacie.wallet_verification.CommandListener;
import dk.promofacie.wallet_verification.service.RoleService;
import io.quarkus.arc.profile.IfBuildProfile;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
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

    @Scheduled(every = "5m")
    @WithTransaction
    Uni<Void> cronJobWithExpressionInConfig() {
        log.debug("Scheduling with cron job");
        return roleService.applyRoles().onItem()
                .invoke(any -> log.debug("Finished. Next in 5m")).onFailure()
                .invoke(err -> {
                    log.error("Error during assignment", err);
                })
                .onFailure()
                .recoverWithNull();
    }
}
