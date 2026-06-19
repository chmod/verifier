package dk.panos.promofacie.db;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "role_sync_outbox", uniqueConstraints = {
        @UniqueConstraint(name = "uq_outbox_user_role", columnNames = {"discord_id", "guild_id", "role_id"})
}, indexes = {
        @Index(name = "idx_outbox_status_guild", columnList = "status, guild_id")
})
public class RoleSyncOutbox extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "discord_id", nullable = false)
    public String discordId;

    @Column(name = "guild_id", nullable = false)
    public String guildId;

    @Column(name = "role_id", nullable = false)
    public String roleId;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_state", nullable = false)
    public TargetState targetState; // PRESENT, ABSENT

    @Column(name = "event_slot", nullable = false)
    public Long eventSlot;

    @Column(name = "retry_count", nullable = false)
    public Integer retryCount = 0;

    @Column(name = "status", nullable = false)
    public String status = "PENDING"; // PENDING, PROCESSING, DONE, FAILED

    @Column(name = "created_at", nullable = false)
    public Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt = Instant.now();
}
