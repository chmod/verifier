package dk.panos.promofacie;

import dk.panos.promofacie.db.GuildRoleRule;
import dk.panos.promofacie.kafka.model.TrackingCommand;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class StartUp {

    private static final Logger log = LoggerFactory.getLogger(StartUp.class);

    @Inject
    @Channel("wallet-tracking-out")
    Emitter<TrackingCommand> trackingEmitter;

    @Transactional
    void onStart(@Observes StartupEvent ev) {
        String targetGuild = "979324485792567357";
        String targetPolicy = "79371b11f083d53ba85ec2171f45bdd1d503d1b43f02c5f29dc3ab37";
        String targetRoleId = "1515413108997493007";

        long count = GuildRoleRule.count("guildId = ?1 and policyId = ?2 and roleId = ?3",
                targetGuild, targetPolicy, targetRoleId);

        if (count == 0) {
            log.info("[StartUp] Creating new GuildRoleRule for guild={}, policy={}", targetGuild, targetPolicy);
            GuildRoleRule rule = new GuildRoleRule();
            rule.guildId = targetGuild;
            rule.policyId = targetPolicy;
            rule.roleId = targetRoleId;
            rule.minQuantity = 1L;
            rule.persist();

            // Notify the Cardano indexer reactively to start tracking this policy ID
            trackingEmitter.send(new TrackingCommand(TrackingCommand.Action.ADD_POLICY, null, targetPolicy))
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.error("[StartUp] Failed to send ADD_POLICY command for policy={}", targetPolicy, ex);
                        } else {
                            log.info("[StartUp] Successfully broadcast ADD_POLICY command for policy={}", targetPolicy);
                        }
                    });
        } else {
            log.info("[StartUp] GuildRoleRule already exists for guild={}, policy={}, skipping creation", targetGuild, targetPolicy);
        }

        String eikoTargetPolicy = "0a109e4c024759806827258f6e3f316fe94584ecd1f85eb18bbce0d9";
        String eikoTargetRoleId = "1517558406695620659";
        count = GuildRoleRule.count("guildId = ?1 and policyId = ?2 and roleId = ?3",
                eikoTargetRoleId, eikoTargetPolicy, targetRoleId);

        if (count == 0) {
            log.info("[StartUp] Creating new GuildRoleRule for guild={}, policy={}", targetGuild, eikoTargetPolicy);
            GuildRoleRule rule = new GuildRoleRule();
            rule.guildId = targetGuild;
            rule.policyId = eikoTargetPolicy;
            rule.roleId = eikoTargetRoleId;
            rule.minQuantity = 1L;
            rule.persist();

            // Notify the Cardano indexer reactively to start tracking this policy ID
            trackingEmitter.send(new TrackingCommand(TrackingCommand.Action.ADD_POLICY, null, eikoTargetPolicy))
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.error("[StartUp] Failed to send ADD_POLICY command for policy={}", eikoTargetPolicy, ex);
                        } else {
                            log.info("[StartUp] Successfully broadcast ADD_POLICY command for policy={}", eikoTargetPolicy);
                        }
                    });
        } else {
            log.info("[StartUp] GuildRoleRule already exists for guild={}, policy={}, skipping creation", targetGuild, eikoTargetPolicy);
        }
    }
}