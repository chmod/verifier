package dk.panos.promofacie.db;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "guild_role_rules", uniqueConstraints = {
        @UniqueConstraint(name = "uq_guild_role_policy", columnNames = {"guild_id", "role_id", "policy_id"})
}, indexes = {
        @Index(name = "idx_rules_policy_search", columnList = "policy_id")
})
public class GuildRoleRule extends PanacheEntity {

    @Column(name = "guild_id", nullable = false)
    public String guildId;

    @Column(name = "role_id", nullable = false)
    public String roleId;

    @Column(name = "policy_id", length = 64, nullable = false)
    public String policyId;

    @Column(name = "min_quantity", nullable = false)
    public Integer minQuantity = 1;

    @OneToMany(mappedBy = "rule", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    public List<RuleTraitCriteria> criteria = new ArrayList<>();

    // Helper method to keep bidirectional relationship in sync safely
    public void addCriteria(RuleTraitCriteria criterion) {
        criteria.add(criterion);
        criterion.rule = this;
    }
}