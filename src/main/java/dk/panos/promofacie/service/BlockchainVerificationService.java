package dk.panos.promofacie.service;

import io.smallrye.mutiny.Uni;

import java.time.ZonedDateTime;

public interface BlockchainVerificationService {
    Uni<Boolean> transactionExists(String address, String userId, ZonedDateTime zdt);
}
