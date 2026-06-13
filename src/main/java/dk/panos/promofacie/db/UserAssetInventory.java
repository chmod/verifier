package dk.panos.promofacie.db;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.io.Serializable;
import java.util.Map;
import java.util.Objects;

@Entity
@Table(name = "user_asset_inventory", indexes = {
        @Index(name = "idx_inventory_stake_policy", columnList = "stake_address, policyId")
})
public class UserAssetInventory extends PanacheEntityBase {

    @EmbeddedId
    public InventoryId id;

    @Column(nullable = false)
    public Long quantity = 1L;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "traits", columnDefinition = "jsonb", nullable = false)
    public Map<String, String> traits;

    @Column(name = "last_updated_slot", nullable = false)
    public Long lastUpdatedSlot;

    // --- Composite Key Definition ---
    @Embeddable
    public static class InventoryId implements Serializable {
        @Column(name = "stake_address")
        public String stakeAddress;

        @Column(name = "policy_id", length = 64)
        public String policyId;

        @Column(name = "asset_name_hex")
        public String assetNameHex;

        public InventoryId() {}

        public InventoryId(String stakeAddress, String policyId, String assetNameHex) {
            this.stakeAddress = stakeAddress;
            this.policyId = policyId;
            this.assetNameHex = assetNameHex;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            InventoryId that = (InventoryId) o;
            return Objects.equals(stakeAddress, that.stakeAddress) &&
                    Objects.equals(policyId, that.policyId) &&
                    Objects.equals(assetNameHex, that.assetNameHex);
        }

        @Override
        public int hashCode() {
            return Objects.hash(stakeAddress, policyId, assetNameHex);
        }
    }
}