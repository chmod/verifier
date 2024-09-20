package dk.promofacie.wallet_verification.service;

import dk.promofacie.wallet_verification.db.Wallet;
import io.quarkus.hibernate.reactive.panache.PanacheEntityBase;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

import java.util.List;

@Path("/")
@ApplicationScoped
@WithTransaction
public class FooResource {
    @GET
    public Uni<Void> foo(){
        Wallet wallet1 = new Wallet();
        wallet1.setDiscordId("1060005379662155886");
        wallet1.setAddress("account_rdx12y8ej0ydrf6eepnyq84ff4gqwev93y75x3lq90rzqs8yscdaf95lyw");

        Wallet wallet2 = new Wallet();
        wallet2.setDiscordId("807203463695826945");
        wallet2.setAddress("account_rdx128qtm27c6wlk8spgkeu4sy3vgzk5y4z0wdxp2dhpjwkw2umfn0yxr7");

        Wallet wallet3 = new Wallet();
        wallet3.setDiscordId("799094734190149662");
        wallet3.setAddress("account_rdx16xweeznl0c7pjsas9d0l4g9pmclhlghhv8ef8efsq5njffxrkfl5hn");

        Wallet wallet4 = new Wallet();
        wallet4.setDiscordId("968226679963148318");
        wallet4.setAddress("account_rdx12xezxlnrz6x3vsmcaqmjhylz4cgzvd50pu2lhgrkdwpw2phzgvhm2u");

        Wallet wallet5 = new Wallet();
        wallet5.setDiscordId("906306252806684722");
        wallet5.setAddress("account_rdx129d9qn9e990wm0a3yuzwh3ccx370r23hezrmdkytv8wsh7z5rukjde");

        Wallet wallet6 = new Wallet();
        wallet6.setDiscordId("773975933337862184");
        wallet6.setAddress("account_rdx12yv30tgc7hhq9wx9spvh27mxpg0jkmvrdgsgp3elxjavydvjf0npre");

        Wallet wallet7 = new Wallet();
        wallet7.setDiscordId("953795586136612874");
        wallet7.setAddress("account_rdx12yeltda3cx3kkch6sddnu4nrllrvevnzpm8w5cea4ceamyk92k5lt3");

        Wallet wallet8 = new Wallet();
        wallet8.setDiscordId("431937069959348225");
        wallet8.setAddress("account_rdx128yh3q9nuaaahaty2n2mpfv6cfv9sza2krqlp2nd75wy56ppt0ggqk");

        Wallet wallet9 = new Wallet();
        wallet9.setDiscordId("218055125967568897");
        wallet9.setAddress("account_rdx12yvenv3629xx90h9sp0l2d7ky67ezj3m82yxkwktvr28zeukrtd9wa");

        Wallet wallet10 = new Wallet();
        wallet10.setDiscordId("1207217357907435530");
        wallet10.setAddress("account_rdx12992pr7euc2pajcxnafygrfvpsxtt9ckgs8wesmlx2qmkjldsrzjh9");

        Wallet wallet11 = new Wallet();
        wallet11.setDiscordId("929058777628573758");
        wallet11.setAddress("account_rdx1298evmams06tefvzf06pa75u00l4wtsn75s8c9ek4q0hk0thmr2lx2");

        Wallet wallet12 = new Wallet();
        wallet12.setDiscordId("889961700248395847");
        wallet12.setAddress("account_rdx129q0rjud54skukm05yuts99qmnr3pndyn4ngczkxe8kx8647wjtk75");

        Wallet wallet13 = new Wallet();
        wallet13.setDiscordId("355358074304856064");
        wallet13.setAddress("account_rdx168kcedu0hnxh58zuu0cch55ccg3e9asjrzp8fkey3wnzrr7kjayr7p");

        Wallet wallet14 = new Wallet();
        wallet14.setDiscordId("377530855981187072");
        wallet14.setAddress("account_rdx12xls804dfvrlk53eptpfsafm8x7ha2m8unrel8w455sfwuz9z44dux");

        Wallet wallet15 = new Wallet();
        wallet15.setDiscordId("819037678867447862");
        wallet15.setAddress("account_rdx168ztxfd9rxu4qajna7hhc59zryef6p4d8v9k0ntxy7sgw52qcujg9e");

        Wallet wallet16 = new Wallet();
        wallet16.setDiscordId("970018581704245259");
        wallet16.setAddress("account_rdx129cpudkgjvkpkqnmz8drengdn5y89crhdq9mskfc7xcgum83rnfhh2");

        Wallet wallet17 = new Wallet();
        wallet17.setDiscordId("1032252665712885780");
        wallet17.setAddress("account_rdx128fxz7pv6mv805gpp7y7v7xn9chg2zmsh4vmhwx36z3gj49zrzc25y");

        Wallet wallet18 = new Wallet();
        wallet18.setDiscordId("1121211291235790868");
        wallet18.setAddress("account_rdx12xvqywxggal5nrqp8snm0rfw6a3l27yu2wgskakhgr9xza5s94vh6y");

        Wallet wallet19 = new Wallet();
        wallet19.setDiscordId("1172144556314202196");
        wallet19.setAddress("account_rdx128qkc480tmz2kyp79y73jw3d5rduz7z2800f94mk9de4hhvclfe3gp");

        Wallet wallet20 = new Wallet();
        wallet20.setDiscordId("1187072336843784336");
        wallet20.setAddress("account_rdx1280wne3m90dmq4j6sa0uzjemmymduwrzjvhzl6ll79hzr9kexm06ur");

        Wallet wallet21 = new Wallet();
        wallet21.setDiscordId("764537188024713247");
        wallet21.setAddress("account_rdx168tdsqxvf5wggvgs9l5kzw2s2nv05auu6sefg7zskldr4zen502tah");

        Wallet wallet22 = new Wallet();
        wallet22.setDiscordId("809562274708062269");
        wallet22.setAddress("account_rdx12yvd96vh4hezgv8y8f75xu5cz29etkta5a7fu7yhjjzf9d2ccncsz4");

        Wallet wallet23 = new Wallet();
        wallet23.setDiscordId("817063252936228895");
        wallet23.setAddress("account_rdx1694ullvfs94thnzrl6dplw3mxw5dyalsdv978t74ycp6zwayecenw5");

        Wallet wallet24 = new Wallet();
        wallet24.setDiscordId("454128723860652034");
        wallet24.setAddress("account_rdx12yzd4msaal5jtks4r2y20ve3chemwf6am9qwlld7zhgw7qzl380fu0");

        Wallet wallet25 = new Wallet();
        wallet25.setDiscordId("701067531904876564");
        wallet25.setAddress("account_rdx128trv0npx7z9h2nw8zh6zhxh0vduzgp2s4y4a9gsdg2ahev0set944");

        Wallet wallet26 = new Wallet();
        wallet26.setDiscordId("1113046289983209515");
        wallet26.setAddress("account_rdx12xrl845lyk949uqkvze6gjlsx6jjd7zdz5sgx7z4n2fe0ee7xm6e0w");

        Wallet wallet27 = new Wallet();
        wallet27.setDiscordId("421713229035732992");
        wallet27.setAddress("account_rdx12xcl6t9l8hwrs84uueauakmm8u4e9dqywut97mp86n0awj2ak3ruc3");

        Wallet wallet28 = new Wallet();
        wallet28.setDiscordId("1029620182315974657");
        wallet28.setAddress("account_rdx128hz8rx63xns0qq6ptjyrcrz9na2dwnt0adc0gh75ktdq4sfepr0je");

        Wallet wallet29 = new Wallet();
        wallet29.setDiscordId("1193978254772686929");
        wallet29.setAddress("account_rdx1287gkrgn30atw2w22zl4fjf2esj554kd20jdfnh7g35lnvrc98m2aj");

        Wallet wallet30 = new Wallet();
        wallet30.setDiscordId("904455012833439814");
        wallet30.setAddress("account_rdx12x0gs5upxxlruks7pxgcacrx0wpef3yjg5tdv4fxwegfrd4czshmem");

        Wallet wallet31 = new Wallet();
        wallet31.setDiscordId("436942579335036929");
        wallet31.setAddress("account_rdx16yte04k8qwdw3l49humul5wpnesfyg2ws8nea8ezceuq90nr506au0");

        Wallet wallet32 = new Wallet();
        wallet32.setDiscordId("715031105493008475");
        wallet32.setAddress("account_rdx12yc9kjp3va47905d2090d2wph8490hlgm5u3frny39kuyg4athvyre");

        Wallet wallet33 = new Wallet();
        wallet33.setDiscordId("746763261734813737");
        wallet33.setAddress("account_rdx169wmrcer4zzgrndvkfr5fspm6s2t7cuuad9l2gqdzq397rsu3n2dyd");

        Wallet wallet34 = new Wallet();
        wallet34.setDiscordId("798123897881886761");
        wallet34.setAddress("account_rdx129j7lx5m2agt9mdewh3kk6vct87nus6ncst2qr6tkj7r6gpzuwv4l6");

        Wallet wallet36 = new Wallet();
        wallet36.setDiscordId("1193978254772686929");
        wallet36.setAddress("account_rdx1287gkrgn30atw2w22zl4fjf2esj554kd20jdfnh7g35lnvrc98m2aj");

        Wallet wallet37 = new Wallet();
        wallet37.setDiscordId("1193978254772686929");
        wallet37.setAddress("account_rdx1287gkrgn30atw2w22zl4fjf2esj554kd20jdfnh7g35lnvrc98m2aj");

        Wallet wallet38 = new Wallet();
        wallet38.setDiscordId("433401635323248640");
        wallet38.setAddress("account_rdx12yxx6sdsf63m5zks7k80dksygyjmx3kls2atsxe4jddunkrwwv0v6x");

        Wallet wallet39 = new Wallet();
        wallet39.setDiscordId("819003243836604416");
        wallet39.setAddress("account_rdx12yuxfqqa3nhvx43plx2pywq9zcy956yd5sys9vmv3sffmnphdz87tp");
        List<Wallet> walletList = List.of(
                wallet1,
                wallet2,
                wallet3,
                wallet4,
                wallet5,
                wallet6,
                wallet7,
                wallet8,
                wallet9,
                wallet10,
                wallet11,
                wallet12,
                wallet13,
                wallet14,
                wallet15,
                wallet16,
                wallet17,
                wallet18,
                wallet19,
                wallet20,
                wallet21,
                wallet22,
                wallet23,
                wallet24,
                wallet25,
                wallet26,
                wallet27,
                wallet28,
                wallet29,
                wallet30,
                wallet31,
                wallet32,
                wallet33,
                wallet34,
                wallet36,
                wallet37,
                wallet38,
                wallet39
        );
        List<Uni<Wallet>> list = walletList.stream().map(w -> w.<Wallet>persist()).toList();
        return Uni.combine().all().unis(list).usingConcurrencyOf(1).discardItems();
    }
}
