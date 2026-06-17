package dk.panos.promofacie.service;

import dk.panos.promofacie.db.Chain;
import dk.panos.promofacie.db.Wallet;
import io.blockfrost.sdk.api.AccountService;
import io.blockfrost.sdk.api.model.AccountAsset;
import io.blockfrost.sdk.api.util.Constants;
import io.blockfrost.sdk.impl.AccountServiceImpl;
import io.quarkus.panache.common.Parameters;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.ThreadFactory;
import java.util.stream.Collectors;

@ApplicationScoped

public class CardanoRoleService {

    private static final org.slf4j.Logger log = LoggerFactory.getLogger(CardanoRoleService.class);
    private static final String POLICY_ID = "3425a3471d59da30590aa476659f41055668be3f45c9523de15bdbff";
    private static final long GUILD_ID = 979895619210584085L; // replace
    private static final long ROLE_ID = 1509640183472197824L;

    private final Guild guild;
    private final Role holderRole;
    private final AccountService accountService;

    @Inject
    public CardanoRoleService(JDA discordAPI, BlockfrostConfig blockfrostConfig) {
        this.guild = discordAPI.getGuildById(GUILD_ID);
        this.holderRole = guild.getRoleById(ROLE_ID);
        this.accountService = new AccountServiceImpl(Constants.BLOCKFROST_MAINNET_URL, blockfrostConfig.projectId());
    }

    public void applyRoles() throws InterruptedException {
        List<Wallet> wallets = Wallet.list("chain = :chain", Parameters.with("chain", Chain.CARDANO));

        Map<String, List<Wallet>> walletsByUser = wallets.stream()
                .collect(Collectors.groupingBy(Wallet::getDiscordId));

        ThreadFactory vthreadFactory = Thread.ofVirtual().name("cardano-role-worker-", 0).factory();

        try (var scope = new StructuredTaskScope.ShutdownOnFailure("CardanoRoleComputation", vthreadFactory)) {
            for (Map.Entry<String, List<Wallet>> entry : walletsByUser.entrySet()) {
                scope.fork(() -> {
                    processUser(entry.getKey(), entry.getValue());
                    return null;
                });
            }
            scope.join();
            scope.throwIfFailed();
        } catch (ExecutionException e) {
            throw new RuntimeException("Failed processing Cardano wallets", e);
        }

        log.info("Cardano role updates complete.");
    }

    private void processUser(String discordId, List<Wallet> wallets) {
        try {
            Member member = guild.getMemberById(discordId);
            if (member == null) return;

            boolean holds = wallets.stream().anyMatch(w -> holdsNft(w.getAddress()));
            boolean hasRole = member.getRoles().contains(holderRole);

            if (holds && !hasRole) {
                guild.addRoleToMember(member, holderRole).submit();
                log.info("Added holder role to {}", discordId);
            } else if (!holds && hasRole) {
                guild.removeRoleFromMember(member, holderRole).submit();
                log.info("Removed holder role from {}", discordId);
            }
        } catch (Exception e) {
            log.error("Failed processing user {}", discordId, e);
        }
    }

    private boolean holdsNft(String stakeAddress) {
        try {
            List<AccountAsset> allAccountAssets = accountService.getAllAccountAssets(stakeAddress);
            return allAccountAssets.stream().anyMatch(accountAsset -> accountAsset.getUnit().startsWith(POLICY_ID));
        } catch (Exception e) {
            log.error("Blockfrost check failed for {}", stakeAddress, e);
            return false;
        }
    }
}