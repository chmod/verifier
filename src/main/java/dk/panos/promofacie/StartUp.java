package dk.panos.promofacie;

import dk.panos.promofacie.db.GuildRoleRule;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import net.dv8tion.jda.api.JDA;

@ApplicationScoped
public class StartUp {

    @Inject
    JDA discordAPI;

    @Inject
    CommandListener commandListener;

    @Transactional // Required to perform write operations during application startup
    void onStart(@Observes StartupEvent ev) {
        // 1. Register your Discord event listener
        discordAPI.addEventListener(commandListener);

        // 2. Define your target configuration constants
        String targetGuild = "979895619210584085";
        String targetRole = "1509640183472197824";
        String targetPolicy = "3425a3471d59da30590aa476659f41055668be3f45c9523de15bdbff";

        // 3. Query the DB using Panache parameters to check if the rule is already provisioned
        long ruleExists = GuildRoleRule.count(
                "guildId = ?1 and roleId = ?2 and policyId = ?3",
                targetGuild, targetRole, targetPolicy
        );

        // 4. Seed the rule if the count is zero
        if (ruleExists == 0) {
            GuildRoleRule defaultRule = new GuildRoleRule();
            defaultRule.guildId = targetGuild;
            defaultRule.roleId = targetRole;
            defaultRule.policyId = targetPolicy;
            defaultRule.minQuantity = 1;

            // Persist the entity directly using Active Record pattern
            defaultRule.persist();
        }
    }
}