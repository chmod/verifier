package dk.panos.promofacie.service;

import java.time.ZonedDateTime;

public interface BlockchainVerificationService {
    boolean transactionExists(String address, String userId, ZonedDateTime zdt);
}
