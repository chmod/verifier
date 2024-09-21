package dk.promofacie.wallet_verification;

import io.quarkus.runtime.annotations.RegisterForReflection;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.ScheduledEvent;
import net.dv8tion.jda.api.managers.AudioManager;

@RegisterForReflection(targets = {Guild[].class, AudioManager[].class})
public class ReflectionConfig {
}