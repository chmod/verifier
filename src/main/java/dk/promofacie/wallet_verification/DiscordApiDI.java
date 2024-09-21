package dk.promofacie.wallet_verification;

import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.Produces;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.ChunkingFilter;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.context.ManagedExecutor;

import java.util.concurrent.CompletionStage;

@ApplicationScoped
public class DiscordApiDI {

    @Inject
    ManagedExecutor executor;

    @ConfigProperty(name = "discord_api_key")
    String apiKey;

    @Produces
    @Singleton
    public CompletionStage<JDA> discordAPI() {
        return executor.supplyAsync(() -> {
            try {
                SlashCommandData verifyCommand = Commands.slash("verify", "Verify your address")
                        .addOption(OptionType.STRING, "address", "Your address", true);

                JDA jda = JDABuilder.create(apiKey, GatewayIntent.GUILD_MEMBERS)
                        .setChunkingFilter(ChunkingFilter.ALL)
                        .setEventPassthrough(true)
                        .setMemberCachePolicy(MemberCachePolicy.ALL)
                        .setActivity(Activity.playing("Wallet verifier"))
                        .build()
                        .awaitReady();
                jda.updateCommands().addCommands(verifyCommand).queue();
                return jda;
            } catch (InterruptedException e) {
                throw new RuntimeException(e.getMessage());
            }
        });
    }

}
