package dk.panos.promofacie.db;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "pending_rule_evaluations", indexes = {
        @Index(name = "idx_pending_eval_status", columnList = "status")
})
public class PendingRuleEvaluation extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "guild_id", nullable = false)
    public String guildId;

    @Column(name = "rule_id", nullable = false)
    public Long ruleId;

    @Column(name = "status", nullable = false)
    public String status = "PENDING"; // PENDING, PROCESSING, DONE, FAILED

    @Column(name = "retry_count", nullable = false)
    public Integer retryCount = 0;

    @Column(name = "error_message", length = 1000)
    public String errorMessage;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt = Instant.now();
}
