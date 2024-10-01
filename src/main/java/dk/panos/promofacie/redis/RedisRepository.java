package dk.panos.promofacie.redis;

import dk.panos.promofacie.redis.redis_model.Verification;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.hash.ReactiveHashCommands;
import io.quarkus.redis.datasource.set.ReactiveSetCommands;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Set;

@ApplicationScoped
public class RedisRepository implements VerificationRepository {
    private final String JOB_KEY = "WALLET_VERIFICATION";
    ReactiveSetCommands<String, Verification> set;
    ReactiveHashCommands<String, String, String> hset;
    ReactiveSetCommands<String, String> passFailSet;

    @Inject
    public RedisRepository(ReactiveRedisDataSource reactive) {
        set = reactive.set(Verification.class);
        hset = reactive.hash(String.class, String.class, String.class);
        passFailSet = reactive.set(String.class);
    }

    public Uni<Integer> store(Verification pending) {
        return set.sadd(JOB_KEY, pending);
    }

    public Uni<Set<Verification>> pop() {
        return set.spop(JOB_KEY, 10);
    }

//    @Override
//    public Uni<Void> passwordResetSuccess(String stakeAddress, String username) {
//        return hset.hset(PASSWORD_SUCCESS_KEY + ":" + stakeAddress, Map.of("username", username))
//                .replaceWithVoid();
//    }

//    @Override
//    public Uni<Void> passwordResetFailure(String stakeAddress) {
//        return passFailSet.sadd(PASSWORD_FAIL_KEY, stakeAddress).replaceWithVoid();
//    }

//    public Uni<Status> status(String stakeAddress) {
//        return passFailSet.sismember(PASSWORD_FAIL_KEY, stakeAddress)
//                .onItem().transformToUni(isMember -> {
//                    if (isMember)
//                        return Uni.createFrom().<Map<String, String>>failure(new IllegalArgumentException("Verification has failed"))
//                                .onFailure().call(() -> passFailSet.srem(PASSWORD_FAIL_KEY, stakeAddress));
//                    else {
//                        return hset.hgetall(PASSWORD_SUCCESS_KEY + ":" + stakeAddress)
//                                .onItem().transformToUni(map-> {
//                                    if(map.isEmpty())
//                                        return Uni.createFrom().failure(new RetryException());
//                                    else
//                                        return Uni.createFrom().item(map);
//                                })
//                                .onItem().call(() -> hset.hdel(PASSWORD_SUCCESS_KEY + ":" + stakeAddress,"username"));
//                    }
//                }).onItem().transformToUni(map -> {
//                    return Uni.createFrom().item(new Status(map.get("username")));
//                });
//    }
}
