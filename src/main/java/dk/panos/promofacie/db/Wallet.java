package dk.panos.promofacie.db;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;

@Entity
@Table(indexes = {
        @Index(name = "idx_wallet_discord_chain", columnList = "discordId, chain"),
        @Index(name = "idx_wallet_chain", columnList = "chain")
})
public class Wallet extends PanacheEntity {
    private String address;
    private String discordId;
    @Enumerated(EnumType.STRING)
    @Column(name = "chain")
    private Chain chain;

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String getDiscordId() {
        return discordId;
    }

    public void setDiscordId(String discordId) {
        this.discordId = discordId;
    }

    public Chain getChain() {
        return chain;
    }

    public void setChain(Chain chain) {
        this.chain = chain;
    }
}
