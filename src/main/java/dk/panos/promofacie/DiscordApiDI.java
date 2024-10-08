package dk.panos.promofacie;

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

public class DiscordApiDI {

    @ConfigProperty(name = "discord_api_key")
    String apiKey;

    @Produces
    @Singleton
    public JDA discordAPI() {
        try {
            SlashCommandData verifyCommand = Commands.slash("verify", "Verify your address")
                    .addOption(OptionType.STRING, "address", "Your address", true);

            JDA jda = JDABuilder.create(apiKey, GatewayIntent.GUILD_MEMBERS).setChunkingFilter(ChunkingFilter.ALL).setEventPassthrough(true).setMemberCachePolicy(MemberCachePolicy.ALL).setActivity(Activity.playing("Wallet verifier"))
                    .build()
                    .awaitReady();
            jda.updateCommands().addCommands(verifyCommand).queue();
            return jda;
        } catch (InterruptedException e) {
            throw new RuntimeException(e.getMessage());
        }
    }

}
