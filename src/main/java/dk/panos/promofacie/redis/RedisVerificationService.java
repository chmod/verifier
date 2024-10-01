package dk.panos.promofacie.redis;

import dk.panos.promofacie.redis.redis_model.Verification;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Set;

@ApplicationScoped
public class RedisVerificationService {
    @Inject
    RedisRepository redisRepository;

    public Uni<Integer> queue(Verification verification) {
        if (verification.tries() >= 15) {
            return Uni.createFrom().item(0);
        } else {
            return redisRepository.store(verification.increaseTries());
        }
    }

    public Uni<Set<Verification>> process() {
        return redisRepository.pop();
    }
}
