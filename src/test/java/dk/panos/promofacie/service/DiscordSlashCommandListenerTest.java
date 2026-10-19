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

    @Test
    void testTriggerWalletRefreshBroadcastsToAllEmitters() {
        DiscordSlashCommandListener listener = new DiscordSlashCommandListener();

        dk.panos.promofacie.db.WalletPersistenceService walletService = mock(dk.panos.promofacie.db.WalletPersistenceService.class);
        org.eclipse.microprofile.reactive.messaging.Emitter<dk.panos.promofacie.kafka.model.TrackingCommand> cardanoEmitter = mock(org.eclipse.microprofile.reactive.messaging.Emitter.class);
        org.eclipse.microprofile.reactive.messaging.Emitter<dk.panos.promofacie.kafka.model.TrackingCommand> robinhoodEmitter = mock(org.eclipse.microprofile.reactive.messaging.Emitter.class);
        org.eclipse.microprofile.reactive.messaging.Emitter<dk.panos.promofacie.kafka.model.TrackingCommand> solanaEmitter = mock(org.eclipse.microprofile.reactive.messaging.Emitter.class);

        when(cardanoEmitter.send(any(dk.panos.promofacie.kafka.model.TrackingCommand.class))).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(null));
        when(robinhoodEmitter.send(any(dk.panos.promofacie.kafka.model.TrackingCommand.class))).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(null));
        when(solanaEmitter.send(any(dk.panos.promofacie.kafka.model.TrackingCommand.class))).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(null));

        listener.walletPersistenceService = walletService;
        listener.trackingEmitter = cardanoEmitter;
        listener.robinhoodTrackingEmitter = robinhoodEmitter;
        listener.solanaTrackingEmitter = solanaEmitter;

        dk.panos.promofacie.db.Wallet cardanoWallet = new dk.panos.promofacie.db.Wallet();
        cardanoWallet.setAddress("stake1u9dgfs9emfdah75lw2gxrrq8gjlq2q9n7r3qhkyjvjhsfwqzrashg");
        cardanoWallet.setChain(dk.panos.promofacie.db.Chain.CARDANO);

        dk.panos.promofacie.db.Wallet rhWallet = new dk.panos.promofacie.db.Wallet();
        rhWallet.setAddress("0xa81c87cfd4d0ce36aae9c1bda543ba0ae55a085a");
        rhWallet.setChain(dk.panos.promofacie.db.Chain.ROBINHOOD);

        dk.panos.promofacie.db.Wallet solWallet = new dk.panos.promofacie.db.Wallet();
        solWallet.setAddress("sol1111111111111111111111111111111111111111");
        solWallet.setChain(dk.panos.promofacie.db.Chain.SOLANA);

        when(walletService.findByDiscordId("user-123")).thenReturn(java.util.List.of(cardanoWallet, rhWallet, solWallet));

        listener.triggerWalletRefresh("user-123");

        org.mockito.ArgumentCaptor<dk.panos.promofacie.kafka.model.TrackingCommand> cardanoCaptor = org.mockito.ArgumentCaptor.forClass(dk.panos.promofacie.kafka.model.TrackingCommand.class);
        org.mockito.ArgumentCaptor<dk.panos.promofacie.kafka.model.TrackingCommand> rhCaptor = org.mockito.ArgumentCaptor.forClass(dk.panos.promofacie.kafka.model.TrackingCommand.class);
        org.mockito.ArgumentCaptor<dk.panos.promofacie.kafka.model.TrackingCommand> solCaptor = org.mockito.ArgumentCaptor.forClass(dk.panos.promofacie.kafka.model.TrackingCommand.class);

        verify(cardanoEmitter).send(cardanoCaptor.capture());
        verify(robinhoodEmitter).send(rhCaptor.capture());
        verify(solanaEmitter).send(solCaptor.capture());

        org.junit.jupiter.api.Assertions.assertEquals(dk.panos.promofacie.kafka.model.TrackingCommand.Action.ADD_ADDRESS, cardanoCaptor.getValue().action());
        org.junit.jupiter.api.Assertions.assertEquals("stake1u9dgfs9emfdah75lw2gxrrq8gjlq2q9n7r3qhkyjvjhsfwqzrashg", cardanoCaptor.getValue().stakeAddress());

        org.junit.jupiter.api.Assertions.assertEquals(dk.panos.promofacie.kafka.model.TrackingCommand.Action.ADD_ADDRESS, rhCaptor.getValue().action());
        org.junit.jupiter.api.Assertions.assertEquals("0xa81c87cfd4d0ce36aae9c1bda543ba0ae55a085a", rhCaptor.getValue().stakeAddress());

        org.junit.jupiter.api.Assertions.assertEquals(dk.panos.promofacie.kafka.model.TrackingCommand.Action.ADD_ADDRESS, solCaptor.getValue().action());
        org.junit.jupiter.api.Assertions.assertEquals("sol1111111111111111111111111111111111111111", solCaptor.getValue().stakeAddress());
    }
}
