package dk.panos.promofacie.service;

import dk.panos.promofacie.db.Wallet;
import dk.panos.promofacie.radix.RadixClient;
import dk.panos.promofacie.radix.model.AddressStateDetails;
import dk.panos.promofacie.radix.model.GetNonFungibleVaultsRequest;
import dk.panos.promofacie.radix.model.GetNonFungibleVaultsResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.requests.restaction.AuditableRestAction;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;

@ApplicationScoped
public class RoleService {

    private static final org.slf4j.Logger log = LoggerFactory.getLogger(RoleService.class);
    private final long guildId = 1403833198814953492L;
    private final Guild guild;
    @RestClient
    RadixClient radixClient;

    private final Role holder;
    private final Role whale;
    private final List<Role> allRoles;

    @Inject
    public RoleService(JDA discordAPI) {
        this.guild = discordAPI.getGuildById(guildId);
        holder = guild.getRoleById(1424056936726532237L);
        whale = guild.getRoleById(1424057237273444543L);
        allRoles = List.of();
    }

    /**
     * Apply roles to all members based on wallet state.
     * Uses virtual threads and structured concurrency.
     */
    public void applyRoles() throws InterruptedException {
        List<Wallet> wallets = Wallet.listAll();

        // Thread-safe collections for concurrent updates
        Map<Role, Queue<Member>> additions = new ConcurrentHashMap<>();
        Map<Role, Queue<Member>> removals = new ConcurrentHashMap<>();

        // Custom virtual thread factory for naming
        ThreadFactory vthreadFactory = Thread.ofVirtual().name("role-worker-", 0).factory();

        // Step 1: Compute role differences in parallel
        try (var scope = new StructuredTaskScope.ShutdownOnFailure("RoleComputation", vthreadFactory)) {
            for (Wallet wallet : wallets) {
                scope.fork(() -> {
                    processWallet(wallet, additions, removals);
                    return null;
                });
            }

            scope.join();
            try {
                scope.throwIfFailed();
            } catch (ExecutionException e) {
                throw new RuntimeException("Failed processing wallets", e);
            }
        }

        log.info("Role computation finished. Submitting Discord updates...");

        // Step 2: Submit role changes to Discord in parallel (fire-and-forget)
        try (var scope = new StructuredTaskScope.ShutdownOnFailure("DiscordUpdates", vthreadFactory)) {
            additions.forEach((role, members) ->
                    members.forEach(member -> scope.fork(() -> {
                        addRole(role, member).submit();
                        return null;
                    }))
            );

            removals.forEach((role, members) ->
                    members.forEach(member -> scope.fork(() -> {
                        removeRole(role, member).submit();
                        return null;
                    }))
            );

            scope.join();
            try {
                scope.throwIfFailed();
            } catch (ExecutionException e) {
                throw new RuntimeException("Failed submitting Discord updates", e);
            }
        }

        log.info("All role updates submitted to JDA.");
    }

    // Process a single wallet and populate additions/removals maps
    private void processWallet(Wallet wallet,
                               Map<Role, Queue<Member>> additions,
                               Map<Role, Queue<Member>> removals) {
        try {
            Member member = guild.getMemberById(wallet.getDiscordId());
            if (member == null) return;

            GetNonFungibleVaultsResponse detail = radixClient.nonFungibleVaults(
                    new GetNonFungibleVaultsRequest(wallet.getAddress(), "resource_rdx1nfju0xwzc4nrz3pk6u8z3xqlv0d5nz6egalpfjzds4zkfn3w5fv44d")
            );

            Set<Role> rolesShouldHave = computeRoles(detail);

            List<Role> rolesShouldntHave = allRoles.stream()
                    .filter(role -> !rolesShouldHave.contains(role))
                    .toList();
            List<Role> alreadyHas = member.getRoles().stream()
                    .filter(allRoles::contains)
                    .toList();

            for (Role role : rolesShouldHave) {
                if (!alreadyHas.contains(role)) {
                    additions.computeIfAbsent(role, r -> new ConcurrentLinkedQueue<>()).add(member);
                }
            }
            for (Role role : rolesShouldntHave) {
                if (alreadyHas.contains(role)) {
                    removals.computeIfAbsent(role, r -> new ConcurrentLinkedQueue<>()).add(member);
                }
            }

        } catch (Exception e) {
            log.error("Failed processing wallet for roles", e);
        }
    }

    // Determine which roles a wallet should have based on on-chain state
    private Set<Role> computeRoles(GetNonFungibleVaultsResponse detail) {
        Set<Role> roles = new HashSet<>();
        if (detail.items().isEmpty()) {
            return roles;
        }
        int sum = detail.items()
                .stream().mapToInt(item -> item.totalCount()).sum();
        if (sum >= 1 && sum <= 19) {
            roles.add(holder);
        }
        if (sum >= 20) {
            roles.add(holder);
            roles.add(whale);
        }

        return roles;
    }

    // Discord role actions
    private AuditableRestAction<Void> addRole(Role role, Member member) {
        return guild.addRoleToMember(member, role);
    }

    private AuditableRestAction<Void> removeRole(Role role, Member member) {
        return guild.removeRoleFromMember(member, role);
    }
}


