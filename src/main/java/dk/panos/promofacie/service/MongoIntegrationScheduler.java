package dk.panos.promofacie.service;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import dk.panos.promofacie.db.TargetState;
import dk.panos.promofacie.db.Wallet;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

@ApplicationScoped
public class MongoIntegrationScheduler {

    private static final Logger log = LoggerFactory.getLogger(MongoIntegrationScheduler.class);
    private static final String GUILD_ID = "1140351511780671620";
    
    @Inject
    MongoClient mongoClient;
    
    @Inject
    RoleEvaluationService roleEvaluationService;
    
    @ConfigProperty(name = "quarkus.mongodb.database", defaultValue = "<PLACEHOLDER_DB>")
    String databaseName;
    
    @ConfigProperty(name = "mongodb.integration.collection", defaultValue = "<PLACEHOLDER_COLLECTION>")
    String collectionName;

    @Scheduled(every = "1h")
    public void processThirdPartyRoles() {
        log.info("[MongoIntegrationScheduler] Starting third-party role sync from MongoDB");
        
        try {
            MongoDatabase database = mongoClient.getDatabase(databaseName);
            MongoCollection<Document> collection = database.getCollection(collectionName);
            
            long eventSlot = System.currentTimeMillis() / 1000;
            
            collection.find().forEach((Consumer<Document>) doc -> {
                String stakeAddress = doc.getString("stakeAddress");
                String roleId = doc.getString("roleId");
                
                if (stakeAddress == null || roleId == null) {
                    log.warn("[MongoIntegrationScheduler] Skipping invalid document (missing stakeAddress or roleId): {}", doc.toJson());
                    return;
                }
                
                Wallet wallet = Wallet.find("address = ?1", stakeAddress).firstResult();
                
                if (wallet == null) {
                    log.warn("[MongoIntegrationScheduler] Stake address {} is not linked to any user — skipping outbox enqueue", stakeAddress);
                    return;
                }
                
                String discordId = wallet.getDiscordId();
                
                log.info("[MongoIntegrationScheduler] Enqueuing role update from MongoDB for user {} roleId {} guildId {}", 
                         discordId, roleId, GUILD_ID);
                
                roleEvaluationService.upsertOutboxTask(discordId, GUILD_ID, roleId, TargetState.PRESENT, eventSlot);
            });
            
            log.info("[MongoIntegrationScheduler] Finished third-party role sync from MongoDB");
        } catch (Exception e) {
            log.error("[MongoIntegrationScheduler] Error during MongoDB sync", e);
        }
    }
}
