package dk.panos.promofacie;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

@ApplicationScoped
public class StartUp {

    void onStart(@Observes StartupEvent ev) {
    }
}