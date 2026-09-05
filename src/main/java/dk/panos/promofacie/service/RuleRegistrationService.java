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

    @Inject
    @Channel("robinhood-tracking-out")
    Emitter<TrackingCommand> robinhoodTrackingEmitter;

    @Transactional
    public void registerRuleAndSync(GuildRoleRule rule) {
        log.info("[RuleRegistration] Registering rule for guild={}, policy={}, role={}, chain={}",
                rule.guildId, rule.policyId, rule.roleId, rule.getResolvedChain());

        rule.persist();

        // Broadcast ADD_POLICY to appropriate indexer (Ponder for Robinhood, Yaci for Cardano)
        boolean isRobinhood = rule.getResolvedChain() == dk.panos.promofacie.db.Chain.ROBINHOOD;
        TrackingCommand cmd = new TrackingCommand(TrackingCommand.Action.ADD_POLICY, null, rule.policyId);

        if (isRobinhood && robinhoodTrackingEmitter != null) {
            log.info("[RuleRegistration] Broadcasting ADD_POLICY to robinhood for policy={}", rule.policyId);
            robinhoodTrackingEmitter.send(cmd)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.error("[RuleRegistration] Failed to send ADD_POLICY to robinhood for policy={}", rule.policyId, ex);
                        } else {
                            log.info("[RuleRegistration] Successfully sent ADD_POLICY to robinhood for policy={}", rule.policyId);
                        }
                    });
        } else if (trackingEmitter != null) {
            log.info("[RuleRegistration] Broadcasting ADD_POLICY to cardano for policy={}", rule.policyId);
            trackingEmitter.send(cmd)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.error("[RuleRegistration] Failed to send ADD_POLICY to cardano for policy={}", rule.policyId, ex);
                        } else {
                            log.info("[RuleRegistration] Successfully sent ADD_POLICY to cardano for policy={}", rule.policyId);
                        }
                    });
        }

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

    @Transactional
    public void deleteRule(Long ruleId) {
        GuildRoleRule rule = GuildRoleRule.findById(ruleId);
        if (rule != null) {
            String policyId = rule.policyId;
            boolean isRobinhood = rule.getResolvedChain() == dk.panos.promofacie.db.Chain.ROBINHOOD;
            rule.delete();
            
            // Check if any other rules exist for this policyId
            long count = GuildRoleRule.count("policyId = ?1", policyId);
            if (count == 0) {
                log.info("[RuleRegistration] No rules remaining for policyId={} — broadcasting REMOVE_POLICY", policyId);
                TrackingCommand cmd = new TrackingCommand(TrackingCommand.Action.REMOVE_POLICY, null, policyId);
                if (isRobinhood && robinhoodTrackingEmitter != null) {
                    log.info("[RuleRegistration] Broadcasting REMOVE_POLICY to robinhood for policy={}", policyId);
                    robinhoodTrackingEmitter.send(cmd)
                            .whenComplete((result, ex) -> {
                                if (ex != null) {
                                    log.error("[RuleRegistration] Failed to send REMOVE_POLICY to robinhood for policy={}", policyId, ex);
                                } else {
                                    log.info("[RuleRegistration] Successfully sent REMOVE_POLICY to robinhood for policy={}", policyId);
                                }
                            });
                } else if (trackingEmitter != null) {
                    log.info("[RuleRegistration] Broadcasting REMOVE_POLICY to cardano for policy={}", policyId);
                    trackingEmitter.send(cmd)
                            .whenComplete((result, ex) -> {
                                if (ex != null) {
                                    log.error("[RuleRegistration] Failed to send REMOVE_POLICY to cardano for policy={}", policyId, ex);
                                } else {
                                    log.info("[RuleRegistration] Successfully sent REMOVE_POLICY to cardano for policy={}", policyId);
                                }
                            });
                }
            }
        }
    }
}
