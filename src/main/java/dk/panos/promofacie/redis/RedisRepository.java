package dk.panos.promofacie.redis;

import dk.panos.promofacie.redis.redis_model.Verification;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.hash.HashCommands;
import io.quarkus.redis.datasource.set.SetCommands;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Set;

@ApplicationScoped
public class RedisRepository implements VerificationRepository {
    private final String JOB_KEY = "WALLET_VERIFICATION";
    SetCommands<String, Verification> set;
    HashCommands<String, String, String> hset;
    SetCommands<String, String> passFailSet;

    @Inject
    public RedisRepository(RedisDataSource redis) {
        set = redis.set(Verification.class);
        hset = redis.hash(String.class, String.class, String.class);
        passFailSet = redis.set(String.class);
    }

    public Integer store(Verification pending) {
        long added = set.sadd(JOB_KEY, pending);
        return (int) added;
    }

    public Set<Verification> pop() {
        return set.spop(JOB_KEY, 10);
    }

//    @Override
//    public void passwordResetSuccess(String stakeAddress, String username) {
//        hset.hset(PASSWORD_SUCCESS_KEY + ":" + stakeAddress, Map.of("username", username));
//    }

//    @Override
//    public void passwordResetFailure(String stakeAddress) {
//        passFailSet.sadd(PASSWORD_FAIL_KEY, stakeAddress);
//    }

//    public Status status(String stakeAddress) {
//        boolean isMember = passFailSet.sismember(PASSWORD_FAIL_KEY, stakeAddress);
//        if (isMember) {
//            throw new IllegalArgumentException("Verification has failed");
//        }
//        Map<String, String> map = hset.hgetall(PASSWORD_SUCCESS_KEY + ":" + stakeAddress);
//        if (map.isEmpty()) {
//            throw new RetryException();
//        }
//        hset.hdel(PASSWORD_SUCCESS_KEY + ":" + stakeAddress, "username");
//        return new Status(map.get("username"));
//    }
}
