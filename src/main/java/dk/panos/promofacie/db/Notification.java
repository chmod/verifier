package dk.panos.promofacie.db;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "notifications")
public class Notification extends PanacheEntity {

    @Column(name = "policy_id", nullable = false, unique = true)
    public String policyId;

    @OneToMany(mappedBy = "notification", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    public List<NotificationChannel> channels = new ArrayList<>();
}
