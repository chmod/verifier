package dk.panos.promofacie;

import dk.panos.promofacie.redis.RedisVerificationService;
import dk.panos.promofacie.redis.redis_model.Verification;
import dk.panos.promofacie.service.RoleService;
import dk.panos.promofacie.task.Scheduler;
import dk.panos.promofacie.v2.AddressVerifiedConsumer;
import dk.panos.promofacie.v2.AddressVerifiedEvent;
import dk.panos.promofacie.v2.CacheService;
import io.quarkus.arc.profile.IfBuildProfile;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.vertx.VertxContextSupport;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import net.dv8tion.jda.api.JDA;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

@ApplicationScoped
@IfBuildProfile("dev")
public class StartupTester {
    @Inject
    JDA discordAPI;
    @Inject
    CacheService cacheService;

    @Inject
    AddressVerifiedConsumer addressVerifiedConsumer;
    @Inject
    RoleService roleService;
    void onStart(@Observes StartupEvent ev) throws Throwable {

    }

}
