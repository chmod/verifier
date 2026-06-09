package dk.panos.promofacie.db;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;

@Entity
@Table(name = "rule_trait_criteria", uniqueConstraints = {
        @UniqueConstraint(name = "uq_rule_trait", columnNames = {"rule_id", "trait_key", "trait_value"})
}, indexes = {
        @Index(name = "idx_criteria_lookup", columnList = "trait_key, trait_value")
})
public class RuleTraitCriteria extends PanacheEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rule_id", nullable = false)
    public GuildRoleRule rule;

    @Column(name = "trait_key", nullable = false)
    public String traitKey;

    @Column(name = "trait_value", nullable = false)
    public String traitValue;
}