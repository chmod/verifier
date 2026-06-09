package dk.panos.promofacie.db;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class WalletPersistenceService {

    private static final Logger log = LoggerFactory.getLogger(WalletPersistenceService.class);

    @Transactional
    public void persist(String stakeAddress, String discordId) {
        Wallet wallet = new Wallet();
        wallet.setAddress(stakeAddress);
        wallet.setDiscordId(discordId);
        wallet.setChain(Chain.CARDANO);
        wallet.persist();
        log.info("Wallet persisted stakeAddress={} discordId={}", stakeAddress, discordId);
    }
}
