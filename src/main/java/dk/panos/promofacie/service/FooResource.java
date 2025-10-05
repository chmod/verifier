//package dk.panos.promofacie.service;
//
//import com.cronutils.utils.StringUtils;
//import dk.panos.promofacie.db.Wallet;
//import dk.panos.promofacie.radix.RadixClient;
//import dk.panos.promofacie.radix.model.GetTransactionsStreamResponse;
//import jakarta.enterprise.context.ApplicationScoped;
//import jakarta.transaction.Transactional;
//import jakarta.ws.rs.GET;
//import jakarta.ws.rs.Path;
//import org.eclipse.microprofile.rest.client.inject.RestClient;
//
//import java.util.List;
//
//@Path("/")
//@ApplicationScoped
//public class FooResource {
//    @RestClient
//    RadixClient radixClient;
//
////    @GET
////    @Transactional
////    public void foo() {
////        GetTransactionsStreamResponse tx = radixClient.getTransactionsForAddress(
////                new GetTransactionsStreamRequestNoLimit(List.of("account_rdx129wl7trqd7ttwfd2nx9tf3qgag6n2e5tjs3t9cfgslpp3yxh526e2u"))
////        );
////        for (var item : tx.items()) {
////            String sourceAddr = item.affectedGlobalEntities().stream()
////                    .filter(str -> str.startsWith("account_rdx"))
////                    .filter(str -> !str.equalsIgnoreCase("account_rdx129wl7trqd7ttwfd2nx9tf3qgag6n2e5tjs3t9cfgslpp3yxh526e2u"))
////                    .findFirst().orElse(null);
////            if (sourceAddr == null) {
////                continue;
////            }
////            if (item.message() == null) {
////                continue;
////            }
////            String discordId = item.message().content().value();
////            if (!StringUtils.isNumeric(discordId)) {
////                continue;
////            }
////            Wallet wallet = new Wallet();
////            wallet.setDiscordId(discordId);
////            wallet.setAddress(sourceAddr);
////            wallet.persist();
////        }
////    }
//
//    public record GetTransactionsStreamRequestNoLimit(
//            List<String> manifestAccountsDepositedIntoFilter
//
//    ) {
//        public static final OptIns OPT_INS = new OptIns(
//                true
//        );
//
//        public OptIns getOptIns() {
//            return OPT_INS;
//        }
//    }
//
//    public record OptIns(
//            boolean affected_global_entities
//    ) {
//    }
//}
