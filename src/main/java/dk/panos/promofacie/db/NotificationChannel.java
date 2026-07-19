package dk.panos.promofacie.db;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;

@Entity
@Table(name = "notification_channels")
public class NotificationChannel extends PanacheEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "notification_id")
    @JsonIgnore
    public Notification notification;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public ChannelType type;

    @Column(name = "guild_id")
    @JsonProperty("guildId")
    @JsonAlias("guild_id")
    public String guildId;

    @Column(name = "channel_id")
    @JsonProperty("channelId")
    @JsonAlias("channel_id")
    public String channelId;

    public enum ChannelType {
        DISCORD
    }
}
