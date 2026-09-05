package dk.panos.promofacie.db;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

@ApplicationScoped
public class WalletPersistenceService {

    private static final Logger log = LoggerFactory.getLogger(WalletPersistenceService.class);

    @Transactional
    public void persist(String stakeAddress, String discordId) {
        persist(stakeAddress, discordId, Chain.CARDANO);
    }

    @Transactional
    public void persist(String address, String discordId, Chain chain) {
        Wallet wallet = new Wallet();
        wallet.setAddress(address);
        wallet.setDiscordId(discordId);
        wallet.setChain(chain != null ? chain : Chain.CARDANO);
        wallet.persist();
        log.info("Wallet persisted address={} discordId={} chain={}", address, discordId, wallet.getChain());
    }

    public List<Wallet> findByDiscordId(String discordId) {
        return Wallet.list("discordId", discordId);
    }

    public Wallet findByAddressAndDiscordId(String stakeAddress, String discordId) {
        return Wallet.find("discordId = ?1 and address = ?2", discordId, stakeAddress).firstResult();
    }

    @Transactional
    public boolean deleteByAddressAndDiscordId(String stakeAddress, String discordId) {
        long deletedCount = Wallet.delete("discordId = ?1 and address = ?2", discordId, stakeAddress);
        log.info("Wallet unlinked count={} stakeAddress={} discordId={}", deletedCount, stakeAddress, discordId);
        return deletedCount > 0;
    }

    @Transactional
    public long deleteAllByDiscordId(String discordId) {
        long deletedCount = Wallet.delete("discordId", discordId);
        log.info("All wallets unlinked count={} discordId={}", deletedCount, discordId);
        return deletedCount;
    }
}
