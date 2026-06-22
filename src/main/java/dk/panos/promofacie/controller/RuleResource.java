package dk.panos.promofacie.controller;

import dk.panos.promofacie.controller.model.CriteriaRequest;
import dk.panos.promofacie.controller.model.RuleRequest;
import dk.panos.promofacie.controller.model.RuleUpdateResponse;
import dk.panos.promofacie.db.GuildRoleRule;
import dk.panos.promofacie.db.PendingRuleEvaluation;
import dk.panos.promofacie.db.RuleTraitCriteria;
import dk.panos.promofacie.kafka.model.TrackingCommand;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Path("/configuration/{guild}")
@ApplicationScoped
public class RuleResource {
    private static final Logger log = LoggerFactory.getLogger(RuleResource.class);

    @Inject
    @Channel("wallet-tracking-out")
    Emitter<TrackingCommand> trackingEmitter;

    @Inject
    net.dv8tion.jda.api.JDA jda;

    @POST
    @Path("/rules")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Transactional
    public Response updateRules(@PathParam("guild") String guild, List<RuleRequest> rules) {
        log.info("[RuleResource] Updating ruleset for guild={} and set {}", guild, rules);
        if (guild == null || guild.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Guild ID is required"))
                    .build();
        }

        log.info("[RuleResource] Updating ruleset for guild={}", guild);

        // 1. Fetch existing rules for this guild eagerly with criteria
        List<GuildRoleRule> existingRules = getExistingRules(guild);

        Set<String> existingPolicies = existingRules.stream()
                .map(r -> r.policyId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Set<String> existingRoles = existingRules.stream()
                .map(r -> r.roleId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        // Normalize rules input list
        List<RuleRequest> newRulesList = rules != null ? rules : Collections.emptyList();

        Set<String> newPolicies = newRulesList.stream()
                .map(RuleRequest::policyId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Set<String> newRoles = newRulesList.stream()
                .map(RuleRequest::roleId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        // 2. Identify policy changes (adds, removals)
        List<String> policyAdds = new ArrayList<>();
        List<String> policyRemovals = new ArrayList<>();

        // policyAdds: present in new rules but NOT present in the DB at all (globally)
        // before this change
        for (String policyId : newPolicies) {
            if (!existingPolicies.contains(policyId)) {
                long count = countPolicyGlobally(policyId);
                if (count == 0) {
                    policyAdds.add(policyId);
                }
            }
        }

        // policyRemovals: present in existingPolicies, but NOT in newPolicies, AND no
        // other rules exist for this policy in other guilds
        for (String policyId : existingPolicies) {
            if (!newPolicies.contains(policyId)) {
                long count = countPolicyInOtherGuilds(policyId, guild);
                if (count == 0) {
                    policyRemovals.add(policyId);
                }
            }
        }

        // 3. Identify role changes
        List<String> rolesApplying = new ArrayList<>(newRoles);
        List<String> rolesRemove = existingRoles.stream()
                .filter(r -> !newRoles.contains(r))
                .collect(Collectors.toList());

        // 4. Delete old rules (and their corresponding PendingRuleEvaluations)
        for (GuildRoleRule existingRule : existingRules) {
            deletePendingEvaluations(existingRule.id);
            deleteRule(existingRule);
        }
        flushSession();

        int maxGroup = newRulesList.stream()
                .map(RuleRequest::group)
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .max()
                .orElse(0);
        int autoGroupBase = maxGroup + 1;

        // 5. Persist new rules and enqueue PendingRuleEvaluations
        for (RuleRequest ruleReq : newRulesList) {
            GuildRoleRule rule = new GuildRoleRule();
            rule.guildId = guild;
            rule.roleId = ruleReq.roleId();
            rule.policyId = ruleReq.policyId();
            rule.minQuantity = ruleReq.minQuantity() != null ? ruleReq.minQuantity() : 1L;
            rule.ruleGroup = ruleReq.group() != null ? ruleReq.group() : autoGroupBase++;

            if (ruleReq.criteria() != null) {
                for (var critReq : ruleReq.criteria()) {
                    RuleTraitCriteria crit = new RuleTraitCriteria();
                    crit.traitKey = critReq.traitKey();
                    crit.traitValue = critReq.traitValue();
                    rule.addCriteria(crit);
                }
            }

            persistRule(rule);

            // Enqueue PendingRuleEvaluation task for the scheduler
            PendingRuleEvaluation pending = new PendingRuleEvaluation();
            pending.guildId = guild;
            pending.ruleId = rule.id;
            pending.status = "PENDING";
            pending.retryCount = 0;
            pending.createdAt = Instant.now();
            pending.updatedAt = Instant.now();
            persistPendingEvaluation(pending);
        }

        // 6. Broadcast Kafka messages for policy updates
        for (String policyId : policyAdds) {
            log.info("[RuleResource] Broadcasting ADD_POLICY tracking command for policyId={}", policyId);
            trackingEmitter.send(new TrackingCommand(TrackingCommand.Action.ADD_POLICY, null, policyId))
                    .whenComplete((res, ex) -> {
                        if (ex != null) {
                            log.error("[RuleResource] Failed to send ADD_POLICY for policyId={}", policyId, ex);
                        } else {
                            log.info("[RuleResource] Successfully sent ADD_POLICY for policyId={}", policyId);
                        }
                    });
        }

        if (!policyAdds.isEmpty() && jda != null) {
            log.info("[RuleResource] Policy additions detected: {}. Triggering sync for existing guild members.", policyAdds);
            try {
                net.dv8tion.jda.api.entities.Guild discordGuild = jda.getGuildById(guild);
                if (discordGuild != null) {
                    List<net.dv8tion.jda.api.entities.Member> members = discordGuild.getMembers();
                    List<String> memberIds = members.stream()
                            .map(net.dv8tion.jda.api.entities.Member::getId)
                            .toList();
                    log.info("[RuleResource] Found {} guild member(s) in JDA cache for guild {}", memberIds.size(), guild);
                    if (!memberIds.isEmpty()) {
                        // Find all verified Cardano addresses of these guild members
                        List<dk.panos.promofacie.db.Wallet> wallets = getWalletsForDiscordIds(memberIds);
                        log.info("[RuleResource] Found {} verified wallet(s) for the members of guild {}", wallets.size(), guild);
                        for (dk.panos.promofacie.db.Wallet wallet : wallets) {
                            log.info("[RuleResource] Broadcasting ADD_ADDRESS to trigger sync for stakeAddress={}", wallet.getAddress());
                            trackingEmitter.send(new TrackingCommand(TrackingCommand.Action.ADD_ADDRESS, wallet.getAddress(), null))
                                    .whenComplete((res, ex) -> {
                                        if (ex != null) {
                                            log.error("[RuleResource] Failed to broadcast ADD_ADDRESS for stakeAddress={}", wallet.getAddress(), ex);
                                        } else {
                                            log.info("[RuleResource] Successfully sent ADD_ADDRESS for stakeAddress={}", wallet.getAddress());
                                        }
                                    });
                        }
                    }
                } else {
                    log.warn("[RuleResource] Guild {} not found in JDA cache; cannot sync existing addresses", guild);
                }
            } catch (Exception e) {
                log.error("[RuleResource] Failed to trigger sync for existing addresses", e);
            }
        }

        for (String policyId : policyRemovals) {
            log.info("[RuleResource] Broadcasting REMOVE_POLICY tracking command for policyId={}", policyId);
            trackingEmitter.send(new TrackingCommand(TrackingCommand.Action.REMOVE_POLICY, null, policyId))
                    .whenComplete((res, ex) -> {
                        if (ex != null) {
                            log.error("[RuleResource] Failed to send REMOVE_POLICY for policyId={}", policyId, ex);
                        } else {
                            log.info("[RuleResource] Successfully sent REMOVE_POLICY for policyId={}", policyId);
                        }
                    });
        }

        // 7. Return 200 OK with the diff response
        RuleUpdateResponse responseBody = new RuleUpdateResponse(
                new RuleUpdateResponse.PoliciesDiff(policyAdds, policyRemovals),
                new RuleUpdateResponse.RolesDiff(rolesApplying, rolesRemove));

        return Response.ok(responseBody).build();
    }

    @GET
    @Path("/rules")
    @Produces(MediaType.APPLICATION_JSON)
    @Transactional
    public Response getRules(@PathParam("guild") String guild) {
        if (guild == null || guild.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Guild ID is required"))
                    .build();
        }

        log.info("[RuleResource] Fetching ruleset for guild={}", guild);

        List<GuildRoleRule> existingRules = getExistingRules(guild);

        // Count group occurrences per (roleId, ruleGroup) pair
        Map<String, Map<Integer, Long>> groupCounts = existingRules.stream()
                .collect(Collectors.groupingBy(
                        r -> r.roleId,
                        Collectors.groupingBy(
                                r -> r.ruleGroup,
                                Collectors.counting()
                        )
                ));

        List<RuleRequest> responseList = existingRules.stream()
                .map(rule -> {
                    List<CriteriaRequest> criteriaList = Collections.emptyList();
                    if (rule.criteria != null) {
                        criteriaList = rule.criteria.stream()
                                .map(c -> new CriteriaRequest(c.traitKey, c.traitValue))
                                .collect(Collectors.toList());
                    }
                    Long count = groupCounts.getOrDefault(rule.roleId, Collections.emptyMap())
                            .getOrDefault(rule.ruleGroup, 0L);
                    Integer responseGroup = count > 1 ? rule.ruleGroup : null;

                    return new RuleRequest(
                            rule.roleId,
                            rule.policyId,
                            rule.minQuantity,
                            criteriaList,
                            responseGroup);
                })
                .collect(Collectors.toList());

        return Response.ok(responseList).build();
    }

    // Database helper methods to support isolated unit testing via Spying
    List<GuildRoleRule> getExistingRules(String guild) {
        return GuildRoleRule.find("from GuildRoleRule r left join fetch r.criteria where r.guildId = ?1", guild).list();
    }

    long countPolicyGlobally(String policyId) {
        return GuildRoleRule.count("policyId = ?1", policyId);
    }

    long countPolicyInOtherGuilds(String policyId, String guild) {
        return GuildRoleRule.count("policyId = ?1 and guildId != ?2", policyId, guild);
    }

    void deletePendingEvaluations(Long ruleId) {
        PendingRuleEvaluation.delete("ruleId = ?1", ruleId);
    }

    void deleteRule(GuildRoleRule rule) {
        rule.delete();
    }

    void persistRule(GuildRoleRule rule) {
        rule.persist();
    }

    void persistPendingEvaluation(PendingRuleEvaluation pending) {
        pending.persist();
    }

    void flushSession() {
        GuildRoleRule.getEntityManager().flush();
    }

    List<dk.panos.promofacie.db.Wallet> getWalletsForDiscordIds(List<String> discordIds) {
        return dk.panos.promofacie.db.Wallet.list("discordId in ?1", discordIds);
    }
}
