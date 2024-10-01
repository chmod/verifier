package dk.panos.promofacie.service;

import dk.panos.promofacie.db.Wallet;
import dk.panos.promofacie.radix.RadixClient;
import dk.panos.promofacie.radix.model.AddressStateDetails;
import dk.panos.promofacie.radix.model.GetAddressDetails;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.tuples.Tuple2;
import io.smallrye.mutiny.tuples.Tuple3;
import io.vertx.mutiny.pgclient.PgPool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.requests.RestAction;
import net.dv8tion.jda.api.requests.restaction.AuditableRestAction;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@ApplicationScoped
public class RoleService {

    private static final Logger logger = Logger.getLogger(RoleService.class);
    private final long guildDANid = 1236323618338766938L;
    private final Guild danGuild;
    @RestClient
    RadixClient radixClient;
    private final Role holder;
    private final Role whale;
    private final Role fomo;
    private final Role hit;
    private final List<Role> allRoles;
    private final PgPool pgPool;
    private final BigDecimal holderBigInt = BigDecimal.valueOf(1_500_000L);
    private final BigDecimal whaleBigInt = BigDecimal.valueOf(1_000_000_000L);

    @Inject
    public RoleService(JDA discordAPI,
                       PgPool pgPool) {
        this.danGuild = discordAPI.getGuildById(guildDANid);
        holder = danGuild.getRoleById(1288145176828575775L);
        whale = danGuild.getRoleById(1288145181748625521L);
        fomo = danGuild.getRoleById(1288145171988353044L);
        hit = danGuild.getRoleById(1288145167102251029L);
        allRoles = List.of(holder, whale, fomo, hit);
        this.pgPool = pgPool;
    }


    @WithTransaction
    public Uni<Void> applyRoles() {
        return pgPool.withTransaction(conn -> conn.query("SELECT * FROM wallet")
                .execute()
                .onItem()
                .transformToMulti(rowset -> Multi.createFrom().iterable(rowset)).map(row -> {
                    Wallet wallet = new Wallet();
                    wallet.setAddress(row.getString("address"));
                    wallet.setDiscordId(row.getString("discordid"));
                    return wallet;
                })
                .select().when(wallet -> {
                    return Uni.createFrom().item(danGuild.getMemberById(wallet.getDiscordId())).onItem().transform(Objects::nonNull)
                            .onFailure().recoverWithItem(false);
                })
                .onItem()
                .transformToUni(wallet -> radixClient.getAddressStateDetails(new GetAddressDetails(List.of(wallet.getAddress())))
                        .onItem().transform(detail -> {
                            Optional<AddressStateDetails.ResourceItem> vaultHoldingDANOpt = detail.items().stream()
                                    .flatMap(item -> item.fungibleResources().items().stream())
                                    .filter(resourceItem -> resourceItem.explicitMetadata().items().stream()
                                            .anyMatch(metadataItem -> metadataItem.value().typed().value().equals("DAN")))
                                    .findFirst();
                            List<Role> rolesShouldHave = new ArrayList<>();
                            if (vaultHoldingDANOpt.isPresent()) {
                                BigDecimal danSum = vaultHoldingDANOpt.get().vaults().items().stream().map(vault -> new BigDecimal(vault.amount()))
                                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                                if (danSum.compareTo(holderBigInt) >= 0) {
                                    rolesShouldHave.add(holder);
                                    if (danSum.compareTo(whaleBigInt) >= 0) {
                                        rolesShouldHave.add(whale);
                                    }
                                }
                            }
                            Optional<AddressStateDetails.ResourceItem> vaultHoldingFOMOopt = detail.items().stream()
                                    .flatMap(item -> item.fungibleResources().items().stream())
                                    .filter(resourceItem -> resourceItem.explicitMetadata().items().stream()
                                            .anyMatch(metadataItem -> metadataItem.value().typed().value().equals("FOMO")))
                                    .findFirst();

                            Optional<AddressStateDetails.ResourceItem> vaultHoldingHITopt = detail.items().stream()
                                    .flatMap(item -> item.fungibleResources().items().stream())
                                    .filter(resourceItem -> resourceItem.explicitMetadata().items().stream()
                                            .anyMatch(metadataItem -> metadataItem.value().typed().value().equals("addix")))
                                    .findFirst();

                            vaultHoldingFOMOopt.ifPresent(any -> rolesShouldHave.add(fomo));
                            vaultHoldingHITopt.ifPresent(any -> rolesShouldHave.add(hit));

                            List<Role> rolesShouldntHave = allRoles.stream().filter(role -> !rolesShouldHave.contains(role)).toList();
                            Member member = danGuild.getMemberById(wallet.getDiscordId());
                            List<Role> alreadyHas = member.getRoles().stream()
                                    .filter(allRoles::contains).toList();
                            return Tuple3.of(member, rolesShouldHave.stream()
                                    .filter(role -> !alreadyHas.contains(role)).toList(), rolesShouldntHave.stream().filter(alreadyHas::contains).toList());

                        })
                ).concatenate().collect().asList().onItem().transform(list -> {
                    Map<Role, List<Member>> shouldHaveMapping = list.stream()
                            .flatMap(tuple -> tuple.getItem2().stream().map(l -> new AbstractMap.SimpleEntry<>(l, tuple.getItem1())))
                            .collect(Collectors.groupingBy(
                                    Map.Entry::getKey,
                                    Collectors.mapping(Map.Entry::getValue, Collectors.toList())
                            ));

                    Map<Role, List<Member>> shouldNotHaveMapping = list.stream()
                            .flatMap(tuple -> tuple.getItem3().stream().map(l -> new AbstractMap.SimpleEntry<>(l, tuple.getItem1())))
                            .collect(Collectors.groupingBy(
                                    Map.Entry::getKey,
                                    Collectors.mapping(Map.Entry::getValue, Collectors.toList())
                            ));
                    return Tuple2.of(shouldHaveMapping, shouldNotHaveMapping);
                })
                .onItem().transformToUni(tuple2 -> {
                    if (tuple2.getItem1().isEmpty() && tuple2.getItem2().isEmpty()) {
                        return Uni.createFrom().voidItem();
                    }
                    Map<Role, List<Member>> additions = tuple2.getItem1();
                    Map<Role, List<Member>> removals = tuple2.getItem2();
                    Set<Uni<Member>> addOperation = additions.entrySet().stream()
                            .flatMap(entry -> {
                                return entry.getValue().stream().map(member -> Uni
                                        .createFrom()
                                        .item(addRoles(entry.getKey(), member).get())
                                        .onItem()
                                        .delayIt().by(Duration.of(1, ChronoUnit.SECONDS))
                                        .onItem()
                                        .invoke(RestAction::queue)
                                        .onItem()
                                        .transform(any -> member));
                            }).collect(Collectors.toSet());

                    Set<Uni<Member>> removeOperation = removals.entrySet().stream()
                            .flatMap(entry -> {
                                return entry.getValue().stream().map(member -> Uni
                                        .createFrom()
                                        .item(removeRoles(entry.getKey(), member).get())
                                        .onItem()
                                        .delayIt().by(Duration.of(1, ChronoUnit.SECONDS))
                                        .onItem()
                                        .invoke(RestAction::queue)
                                        .onItem()
                                        .transform(any -> member));
                            }).collect(Collectors.toSet());


                    addOperation.addAll(removeOperation);
                    return Uni.combine().all().unis(addOperation)
                            .usingConcurrencyOf(1).discardItems();
                }));
    }


    @NotNull
    private Supplier<AuditableRestAction<Void>> removeRoles(
            net.dv8tion.jda.api.entities.Role discordRole,
            Member member) {
        return () -> danGuild
                .removeRoleFromMember(
                        member,
                        discordRole);
    }

    @NotNull
    private Supplier<AuditableRestAction<Void>> addRoles(
            net.dv8tion.jda.api.entities.Role discordRole,
            Member member) {
        return () -> danGuild
                .addRoleToMember(
                        member,
                        discordRole);
    }
}
