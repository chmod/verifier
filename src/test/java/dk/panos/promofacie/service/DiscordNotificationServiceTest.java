package dk.panos.promofacie.service;

import dk.panos.promofacie.kafka.model.TransactionMessage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DiscordNotificationServiceTest {

    private static TransactionMessage message(String type, String platform) {
        return new TransactionMessage("509,788", "Squeak", "https://pool.pm/asset1", "097f37ef", "42.47 ADA",
                type, "0cd6", null, null, false, platform, null);
    }

    @Test
    void tokenTradeOnKnownDexNamesIt() {
        assertEquals("🏦 **DEX:** Minswap V2", DiscordNotificationService.dexLine(message("FT_BUY", "Minswap V2")));
    }

    @Test
    void tokenTradeOnUnknownPoolAsksToContactPromoFacie() {
        assertEquals("🏦 **DEX:** Unknown pool. Contact PromoFacie to get it added.",
                DiscordNotificationService.dexLine(message("FT_SELL", null)));
    }

    @Test
    void nftMessagesHaveNoDexLine() {
        assertNull(DiscordNotificationService.dexLine(message("SALE", "Wayup")));
        assertNull(DiscordNotificationService.dexLine(message("LISTING", null)));
    }
}
