package dk.panos.promofacie.v2;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.keys.KeyCommands;
import io.quarkus.redis.datasource.set.SetCommands;
import io.quarkus.redis.datasource.value.ValueCommands;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@ApplicationScoped
public class CacheService {
    RedisDataSource redis;
    ValueCommands<String, String> valueCommands;
    KeyCommands<String> keyCommands;
    ObjectMapper objectMapper;
    SetCommands<String, String> setCommands;

    @Inject
    public CacheService(RedisDataSource redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.valueCommands = redis.value(String.class);
        this.keyCommands = redis.key(String.class);
        this.setCommands = redis.set(String.class);
    }


    public void linkAddress(String discordId, String address) {
        valueCommands.set("discord:" + discordId + ":address", address);
        valueCommands.set("address:" + address + ":discord", discordId);
    }

    public String getAddress(String discordId) {
        return valueCommands.get("discord:" + discordId + ":address");
    }

    public void linkUserToGuild(String discordId, String guildId) {
        setCommands.sadd("guild:" + guildId + ":users", discordId);
    }

    public void unlinkUserFromGuild(String discordId, String guildId) {
        setCommands.srem("guild:" + guildId + ":users", discordId);
    }


    public void cacheUserNFTs(String discordId, List<NFTMetadata> nfts) {
        try {
            var json = objectMapper.writeValueAsString(nfts);
            valueCommands.setex("user:" + discordId + ":nfts", 3600, json);
        } catch (Exception e) {
            throw new RuntimeException("Failed to cache NFTs", e);
        }
    }

    public List<NFTMetadata> getUserNFTs(String discordId) {
        try {
            var json = valueCommands.get("user:" + discordId + ":nfts");
            if (json == null) return null;

            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, NFTMetadata.class));
        } catch (Exception e) {
            throw new RuntimeException("Failed to retrieve cached NFTs", e);
        }
    }

    public void cacheRoleConfig(String guildId, RoleConfig config) {
        try {
            var json = objectMapper.writeValueAsString(config);
            valueCommands.setex("guild:" + guildId + ":roles", 86400, json);
            registerGuild(guildId);
        } catch (Exception e) {
            throw new RuntimeException("Failed to cache role config", e);
        }
    }

    public List<String> getAllVerifiedUsers(String guildId) {
        try {
            Set<String> users = setCommands.smembers("guild:" + guildId + ":users");
            return new ArrayList<>(users);
        } catch (Exception e) {
            throw new RuntimeException("Failed to get verified users for guild " + guildId, e);
        }
    }

    public List<String> getAllGuilds() {
        try {
            Set<String> guilds = setCommands.smembers("system:guilds");
            return new ArrayList<>(guilds);
        } catch (Exception e) {
            throw new RuntimeException("Failed to get guilds", e);
        }
    }

    public void registerGuild(String guildId) {
        setCommands.sadd("system:guilds", guildId);
    }

    public void cleanupStaleEntries() {
        // Remove NFT cache entries that have expired (TTL < 0)
        // Note: This is just for cleanup, main cache invalidation is handled by TTL
        SetCommands<String, String> setCommands = redis.set(String.class);

        // Clean up system:guilds if guild config has expired
        Set<String> guilds = setCommands.smembers("system:guilds");
        for (String guildId : guilds) {
            String configKey = "guild:" + guildId + ":roles";
            Long ttl = keyCommands.ttl(configKey);
            if (ttl != null && ttl < 0) {
                setCommands.srem("system:guilds", guildId);
            }
        }
    }

    public RoleConfig getRoleConfig(String guildId) {
        try {
            var json = valueCommands.get("guild:" + guildId + ":roles");
            if (json == null) return null;

            return objectMapper.readValue(json, RoleConfig.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to retrieve role config", e);
        }
    }
}