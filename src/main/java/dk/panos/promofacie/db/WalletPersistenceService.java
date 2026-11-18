package dk.panos.promofacie.db;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dk.panos.promofacie.util.EvmAddress;

import java.util.List;

@ApplicationScoped
public class WalletPersistenceService {

    private static final Logger log = LoggerFactory.getLogger(WalletPersistenceService.class);

    @Transactional
    public void persist(String stakeAddress, String discordId) {
        persist(stakeAddress, discordId, Chain.CARDANO);
    }

    /**
     * Links the wallet to {@code discordId}. A wallet already linked to a different Discord account is never moved:
     * the owner must unlink it there first. EVM addresses are matched case-insensitively, since the same address
     * verifies under any hex casing.
     *
     * @throws WalletAlreadyLinkedException if another Discord account holds this wallet (the transaction rolls back)
     */
    @Transactional
    public void persist(String address, String discordId, Chain chain) {
        Chain targetChain = chain != null ? chain : Chain.CARDANO;
        List<Wallet> existing = targetChain == Chain.ROBINHOOD
                ? Wallet.list("lower(address) = lower(?1)", address)
                : Wallet.list("address = ?1", address);

        for (Wallet w : existing) {
            if (!discordId.equals(w.getDiscordId())) {
                log.warn("Refusing to reassign wallet address={} from discordId={} to discordId={}",
                        address, w.getDiscordId(), discordId);
                throw new WalletAlreadyLinkedException(address);
            }
        }

        Wallet wallet = existing.isEmpty() ? new Wallet() : existing.get(0);
        if (existing.isEmpty()) {
            // EVM: store lowercase like the indexer reports it; role evaluation matches addresses exactly.
            wallet.setAddress(EvmAddress.normalize(address));
        }
        wallet.setDiscordId(discordId);
        wallet.setChain(targetChain);
        wallet.persist();
        log.info("Wallet persisted address={} discordId={} chain={}", wallet.getAddress(), discordId, targetChain);
    }

    @Transactional
    public List<Wallet> findByDiscordId(String discordId) {
        return Wallet.list("discordId", discordId);
    }

    @Transactional
    public Wallet findByAddressAndDiscordId(String stakeAddress, String discordId) {
        return Wallet.find("discordId = ?1 and address = ?2", discordId, EvmAddress.normalize(stakeAddress)).firstResult();
    }

    @Transactional
    public boolean deleteByAddressAndDiscordId(String stakeAddress, String discordId) {
        long deletedCount = Wallet.delete("discordId = ?1 and address = ?2", discordId, EvmAddress.normalize(stakeAddress));
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
