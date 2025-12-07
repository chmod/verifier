package dk.panos.promofacie.v2;

import io.quarkus.scheduler.Scheduled;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.concurrent.StructuredTaskScope;

@ApplicationScoped
public class RoleSyncScheduler {

    @Inject
    RoleSyncService roleSyncService;

    @Inject
    CacheService cacheService;

    private static final Logger LOG = Logger.getLogger(RoleSyncScheduler.class);

    /**
     * Hourly job: Re-verify all users and update roles
     * Catches NFT transfers, sales, purchases
     */
    @Scheduled(every = "1h", identity = "role-sync-job")
    @RunOnVirtualThread
    public void syncAllGuilds() {
        LOG.info("Starting scheduled role sync for all guilds");

        List<String> guilds = cacheService.getAllGuilds();

        try (StructuredTaskScope.ShutdownOnFailure scope = new StructuredTaskScope.ShutdownOnFailure()) {
            List<StructuredTaskScope.Subtask<Map<String, Object>>> futures = guilds.stream()
                    .map(guildId -> scope.fork(() -> {
                        LOG.info("Syncing guild: " + guildId);
                        return roleSyncService.syncGuild(guildId);
                    }))
                    .toList();

            scope.join();

            LOG.info("Completed role sync for " + guilds.size() + " guilds");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.error("Role sync interrupted", e);
        } catch (Exception e) {
            LOG.error("Role sync failed", e);
        }
    }

    /**
     * Daily job: Clean up stale cache entries
     */
    @Scheduled(cron = "0 0 3 * * ?", identity = "cache-cleanup-job")
    @RunOnVirtualThread
    public void cleanupStaleCache() {
        LOG.info("Starting cache cleanup");
        cacheService.cleanupStaleEntries();
        LOG.info("Cache cleanup completed");
    }
}