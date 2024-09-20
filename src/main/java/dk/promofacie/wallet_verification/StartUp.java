package dk.promofacie.wallet_verification;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import net.dv8tion.jda.api.JDA;

@ApplicationScoped
public class StartUp {

    @Inject
    JDA discordAPI;
    @Inject
    CommandListener commandListener;
    void onStart(@Observes StartupEvent ev) {
        discordAPI.addEventListener(commandListener);
    }


}
