package dk.panos.promofacie.service;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.mockito.Mockito.*;

class DiscordSlashCommandListenerTest {

    @Test
    void testIgnoreUnrelatedCommand() {
        DiscordSlashCommandListener listener = new DiscordSlashCommandListener();
        SlashCommandInteractionEvent event = mock(SlashCommandInteractionEvent.class);
        when(event.getName()).thenReturn("other-command");

        listener.onSlashCommandInteraction(event);

        verify(event, never()).getGuild();
    }

    @Test
    void testCommandOutsideGuild() {
        DiscordSlashCommandListener listener = new DiscordSlashCommandListener();
        SlashCommandInteractionEvent event = mock(SlashCommandInteractionEvent.class);
        ReplyCallbackAction replyAction = mock(ReplyCallbackAction.class);

        when(event.getName()).thenReturn(DiscordSlashCommandListener.COMMAND_RECALCULATE);
        when(event.getGuild()).thenReturn(null);
        when(event.reply(anyString())).thenReturn(replyAction);
        when(replyAction.setEphemeral(true)).thenReturn(replyAction);

        listener.onSlashCommandInteraction(event);

        verify(event).reply("❌ This command can only be used within a server.");
        verify(replyAction).queue();
    }

    @Test
    void testRecalculateForSelfQueuesTask() {
        DiscordSlashCommandListener listener = Mockito.spy(new DiscordSlashCommandListener());
        doNothing().when(listener).enqueueUserEvaluation(anyString(), anyString());

        SlashCommandInteractionEvent event = mock(SlashCommandInteractionEvent.class);
        ReplyCallbackAction replyAction = mock(ReplyCallbackAction.class);
        Guild guild = mock(Guild.class);
        User selfUser = mock(User.class);

        when(event.getName()).thenReturn(DiscordSlashCommandListener.COMMAND_RECALCULATE);
        when(event.getGuild()).thenReturn(guild);
        when(guild.getId()).thenReturn("guild-1");
        when(event.getUser()).thenReturn(selfUser);
        when(selfUser.getId()).thenReturn("user-self");
        when(selfUser.getAsMention()).thenReturn("<@user-self>");
        when(event.getOption("user")).thenReturn(null);
        when(event.reply(anyString())).thenReturn(replyAction);
        when(replyAction.setEphemeral(true)).thenReturn(replyAction);

        listener.onSlashCommandInteraction(event);

        verify(listener).enqueueUserEvaluation("guild-1", "user-self");
        verify(event).reply("⏳ Role recalculation has been queued for <@user-self>.");
        verify(replyAction).queue();
    }

    @Test
    void testRecalculateForOtherWithoutPermissionBlocked() {
        DiscordSlashCommandListener listener = Mockito.spy(new DiscordSlashCommandListener());

        SlashCommandInteractionEvent event = mock(SlashCommandInteractionEvent.class);
        ReplyCallbackAction replyAction = mock(ReplyCallbackAction.class);
        Guild guild = mock(Guild.class);
        User selfUser = mock(User.class);
        User otherUser = mock(User.class);
        Member callerMember = mock(Member.class);
        OptionMapping option = mock(OptionMapping.class);

        when(event.getName()).thenReturn(DiscordSlashCommandListener.COMMAND_RECALCULATE);
        when(event.getGuild()).thenReturn(guild);
        when(event.getUser()).thenReturn(selfUser);
        when(selfUser.getId()).thenReturn("user-self");

        when(event.getOption("user")).thenReturn(option);
        when(option.getAsUser()).thenReturn(otherUser);
        when(otherUser.getId()).thenReturn("user-other");

        when(event.getMember()).thenReturn(callerMember);
        when(callerMember.hasPermission(Permission.MANAGE_ROLES)).thenReturn(false);
        when(callerMember.hasPermission(Permission.ADMINISTRATOR)).thenReturn(false);

        when(event.reply(anyString())).thenReturn(replyAction);
        when(replyAction.setEphemeral(true)).thenReturn(replyAction);

        listener.onSlashCommandInteraction(event);

        verify(event).reply("❌ You need **Manage Roles** permissions to recalculate roles for another member.");
        verify(listener, never()).enqueueUserEvaluation(anyString(), anyString());
    }

    @Test
    void testRecalculateForOtherWithPermissionQueuesTask() {
        DiscordSlashCommandListener listener = Mockito.spy(new DiscordSlashCommandListener());
        doNothing().when(listener).enqueueUserEvaluation(anyString(), anyString());

        SlashCommandInteractionEvent event = mock(SlashCommandInteractionEvent.class);
        ReplyCallbackAction replyAction = mock(ReplyCallbackAction.class);
        Guild guild = mock(Guild.class);
        User selfUser = mock(User.class);
        User otherUser = mock(User.class);
        Member callerMember = mock(Member.class);
        OptionMapping option = mock(OptionMapping.class);

        when(event.getName()).thenReturn(DiscordSlashCommandListener.COMMAND_RECALCULATE);
        when(event.getGuild()).thenReturn(guild);
        when(guild.getId()).thenReturn("guild-1");
        when(event.getUser()).thenReturn(selfUser);
        when(selfUser.getId()).thenReturn("user-self");

        when(event.getOption("user")).thenReturn(option);
        when(option.getAsUser()).thenReturn(otherUser);
        when(otherUser.getId()).thenReturn("user-other");
        when(otherUser.getAsMention()).thenReturn("<@user-other>");

        when(event.getMember()).thenReturn(callerMember);
        when(callerMember.hasPermission(Permission.MANAGE_ROLES)).thenReturn(true);

        when(event.reply(anyString())).thenReturn(replyAction);
        when(replyAction.setEphemeral(true)).thenReturn(replyAction);

        listener.onSlashCommandInteraction(event);

        verify(listener).enqueueUserEvaluation("guild-1", "user-other");
        verify(event).reply("⏳ Role recalculation has been queued for <@user-other>.");
        verify(replyAction).queue();
    }
}
