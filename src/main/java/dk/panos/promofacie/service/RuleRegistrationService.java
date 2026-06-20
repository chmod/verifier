package dk.panos.promofacie.service;

import dk.panos.promofacie.db.GuildRoleRule;
import dk.panos.promofacie.kafka.model.TrackingCommand;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class RuleRegistrationService {
    private static final Logger log = LoggerFactory.getLogger(RuleRegistrationService.class);

    @Inject
    @Channel("wallet-tracking-out")
    Emitter<TrackingCommand> trackingEmitter;

    @Transactional
    public void registerRuleAndSync(GuildRoleRule rule) {
        log.info("[RuleRegistration] Registering rule for guild={}, policy={}, role={}",
                rule.guildId, rule.policyId, rule.roleId);

        rule.persist();

        // Broadcast ADD_POLICY to Cardano indexer so it tracks this policy for future blocks
        trackingEmitter.send(new TrackingCommand(TrackingCommand.Action.ADD_POLICY, null, rule.policyId))
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("[RuleRegistration] Failed to send ADD_POLICY for policy={}", rule.policyId, ex);
                    } else {
                        log.info("[RuleRegistration] Successfully sent ADD_POLICY for policy={}", rule.policyId);
                    }
                });

        // Enqueue local rule re-evaluation task for the scheduler
        dk.panos.promofacie.db.PendingRuleEvaluation pending = new dk.panos.promofacie.db.PendingRuleEvaluation();
        pending.guildId = rule.guildId;
        pending.ruleId = rule.id;
        pending.status = "PENDING";
        pending.retryCount = 0;
        pending.createdAt = java.time.Instant.now();
        pending.updatedAt = java.time.Instant.now();
        pending.persist();
        log.info("[RuleRegistration] Enqueued pending rule evaluation for guild={}, ruleId={}", rule.guildId, rule.id);
    }
}
