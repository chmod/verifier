package dk.panos.promofacie;

import dk.panos.promofacie.db.GuildRoleRule;
import dk.panos.promofacie.service.RuleRegistrationService;
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
    RuleRegistrationService ruleRegistrationService;

    @Transactional
    void onStart(@Observes StartupEvent ev) {
//        log.info("[StartUp] Cleaning pending_rule_evaluations table on startup");
//        dk.panos.promofacie.db.PendingRuleEvaluation.deleteAll();
//
//        String targetGuild = "979324485792567357";
//        String targetPolicy = "79371b11f083d53ba85ec2171f45bdd1d503d1b43f02c5f29dc3ab37";
//        String targetRoleId = "1515413108997493007";
//
//        log.info("[StartUp] Deleting existing GuildRoleRule for guild={}, policy={} if present", targetGuild, targetPolicy);
//        GuildRoleRule.delete("guildId = ?1 and policyId = ?2 and roleId = ?3", targetGuild, targetPolicy, targetRoleId);
//
//        log.info("[StartUp] Creating new GuildRoleRule for guild={}, policy={}", targetGuild, targetPolicy);
//        GuildRoleRule rule = new GuildRoleRule();
//        rule.guildId = targetGuild;
//        rule.policyId = targetPolicy;
//        rule.roleId = targetRoleId;
//        rule.minQuantity = 1L;
//
//        ruleRegistrationService.registerRuleAndSync(rule);
//
//        String eikoTargetPolicy = "0a109e4c024759806827258f6e3f316fe94584ecd1f85eb18bbce0d9";
//        String eikoTargetRoleId = "1517558406695620659";
//
//        log.info("[StartUp] Deleting existing GuildRoleRule for guild={}, policy={} if present", targetGuild, eikoTargetPolicy);
//        GuildRoleRule.delete("guildId = ?1 and policyId = ?2 and roleId = ?3", targetGuild, eikoTargetPolicy, eikoTargetRoleId);
//
//        log.info("[StartUp] Creating new GuildRoleRule for guild={}, policy={}", targetGuild, eikoTargetPolicy);
//        GuildRoleRule eikoRule = new GuildRoleRule();
//        eikoRule.guildId = targetGuild;
//        eikoRule.policyId = eikoTargetPolicy;
//        eikoRule.roleId = eikoTargetRoleId;
//        eikoRule.minQuantity = 1L;
//
//        ruleRegistrationService.registerRuleAndSync(eikoRule);
    }
}