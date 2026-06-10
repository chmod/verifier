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

    @Transactional
    void onStart(@Observes StartupEvent ev) {
        discordAPI.addEventListener(commandListener);
    }
}