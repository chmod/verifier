package dk.panos.promofacie.redis;

import dk.panos.promofacie.redis.redis_model.Verification;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Set;

@ApplicationScoped
public class RedisVerificationService {
    @Inject
    RedisRepository redisRepository;

    public int queue(Verification verification) {
        if (verification.tries() >= 15) {
            return 0;
        } else {
            return redisRepository.store(verification.increaseTries());
        }
    }

    public Set<Verification> process() {
        return redisRepository.pop();
    }
}
