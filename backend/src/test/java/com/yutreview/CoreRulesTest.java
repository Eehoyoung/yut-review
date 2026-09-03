package com.yutreview;

import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest @Transactional class CoreRulesTest {
    @Autowired StoreRepository stores; @Autowired QrRepository qrs; @Autowired PrizeRepository prizes;
    @Autowired PasswordEncoder encoder; @Autowired GameService games;
    @Autowired CouponRepository coupons; @Autowired AdminSignupService signup; @Autowired AdminUserRepository admins; @Autowired MembershipRepository memberships; @Autowired CouponService couponService; @Autowired ParticipationService participation; @Autowired PhoneService personalData; @Autowired GameConfigService config; @Autowired StoreOutcomeRepository outcomes;
    Store store; String qr;
    @BeforeEach void setup(){Instant now=Instant.now();store=new Store();store.name="test";store.phone="0200000000";store.staffPinHash=encoder.encode("123456");store.status=StoreStatus.ACTIVE;store.createdAt=now;store.updatedAt=now;stores.save(store);StoreQrCode q=new StoreQrCode();q.store=store;q.publicToken="qr-"+System.nanoTime();q.status=QrStatus.ACTIVE;q.createdAt=now;qrs.save(q);qr=q.publicToken;config.save(store,GameConfigService.defaults());}
    /** Weights indexed by YutResult.ordinal, mapped one rank per outcome so the awarded rank identifies the throw. */
    private List<GameConfigService.Setting> settings(int[] weights,int[] ranks){List<GameConfigService.Setting> list=new ArrayList<>();for(YutResult y:YutResult.values())list.add(new GameConfigService.Setting(y,weights[y.ordinal()],ranks[y.ordinal()]));return list;}
    @Test void defaultWeightBoundariesMatchTheOldFixedProbabilities(){int[] w={325,325,125,125,100};
        assertEquals(YutResult.DO,GameResultGenerator.from(0,w));assertEquals(YutResult.GAE,GameResultGenerator.from(.325,w));assertEquals(YutResult.GEOL,GameResultGenerator.from(.65,w));assertEquals(YutResult.YUT,GameResultGenerator.from(.775,w));assertEquals(YutResult.MO,GameResultGenerator.from(.9,w));
        assertEquals(YutResult.MO,GameResultGenerator.from(.999999,w));}
    @Test void zeroWeightOutcomeNeverOccurs(){int[] w={0,0,1,0,0};
        for(int i=0;i<200;i++)assertEquals(YutResult.GEOL,GameResultGenerator.from(i/200.0,w));
        assertThrows(IllegalStateException.class,()->GameResultGenerator.from(.5,new int[]{0,0,0,0,0}));}
    @Test void invalidConfigIsRejected(){
        assertEquals("INVALID_WEIGHT",assertThrows(AppException.class,()->config.save(store,settings(new int[]{1001,1,1,1,1},new int[]{3,3,2,2,1}))).code);
        assertEquals("INVALID_WEIGHT",assertThrows(AppException.class,()->config.save(store,settings(new int[]{-1,1,1,1,1},new int[]{3,3,2,2,1}))).code);
        assertEquals("ZERO_WEIGHT_SUM",assertThrows(AppException.class,()->config.save(store,settings(new int[]{0,0,0,0,0},new int[]{3,3,2,2,1}))).code);
        assertEquals("INVALID_RANK_SEQUENCE",assertThrows(AppException.class,()->config.save(store,settings(new int[]{1,1,1,1,1},new int[]{4,4,2,2,1}))).code);
        assertEquals("INVALID_RANK_SEQUENCE",assertThrows(AppException.class,()->config.save(store,settings(new int[]{1,1,1,1,1},new int[]{5,4,3,2,6}))).code);
        assertEquals("INVALID_REQUEST",assertThrows(AppException.class,()->config.save(store,List.of(new GameConfigService.Setting(YutResult.DO,1,1)))).code);}
    @Test void storesCanRunDifferentRankCounts(){
        config.save(store,settings(new int[]{1000,0,0,0,0},new int[]{5,4,3,2,1}));
        assertEquals(5,GameConfigService.rankCount(config.load(store.id)));
        assertEquals(5,prizes.findByStoreIdOrderByRank(store.id).stream().filter(p->p.active).count());
        Coupon five=coupons.findByGamePlayId(games.create(qr,"A","01011112222","rank-5").id).orElseThrow();
        assertEquals(5,five.prizeRankSnapshot);assertEquals("5등 상품",five.prizeNameSnapshot);
        config.save(store,settings(new int[]{0,0,0,0,1000},new int[]{4,4,3,2,1}));
        assertEquals(4,GameConfigService.rankCount(config.load(store.id)));
        Coupon top=coupons.findByGamePlayId(games.create(qr,"B","01033334444","rank-4").id).orElseThrow();
        assertEquals(1,top.prizeRankSnapshot);
        assertFalse(prizes.findByStoreIdAndRank(store.id,5).orElseThrow().active,"드롭된 등급은 비활성화되고 삭제되지 않는다");}
    @Test void issuedCouponSurvivesLaterConfigChanges(){
        Coupon issued=coupons.findByGamePlayId(games.create(qr,"A","01099998888","freeze-1").id).orElseThrow();
        int rank=issued.prizeRankSnapshot;String name=issued.prizeNameSnapshot;
        Prize prize=prizes.findByStoreIdAndRank(store.id,rank).orElseThrow();prize.name="바뀐 상품";
        config.save(store,settings(new int[]{200,200,200,200,200},new int[]{5,4,3,2,1}));
        assertEquals(rank,issued.prizeRankSnapshot);assertEquals(name,issued.prizeNameSnapshot);}
    @Test void oddsAreServerComputedPercentages(){
        config.save(store,settings(new int[]{325,325,125,125,100},new int[]{3,3,2,2,1}));
        List<StoreOutcome> loaded=config.load(store.id);
        assertEquals(65.0,GameConfigService.odds(loaded,o->o.prizeRank==3));
        assertEquals(25.0,GameConfigService.odds(loaded,o->o.prizeRank==2));
        assertEquals(10.0,GameConfigService.odds(loaded,o->o.prizeRank==1));
        assertEquals(5,outcomes.findByStoreId(store.id).size());}
    @Test void oneGameOneCouponAndIdempotentReveal(){GamePlay first=games.create(qr,"홍길동","010-1234-5678","request-1");GamePlay retry=games.create(qr,"홍길동","01012345678","request-1");assertEquals(first.id,retry.id);Coupon a=games.reveal(first.publicId);Coupon b=games.reveal(first.publicId);assertEquals(a.id,b.id);assertEquals(1,coupons.findByStoreIdOrderByIssuedAtDesc(store.id).size());}
    @Test void activeCouponPrecedesCooldownAndRedeemsOnce(){GamePlay game=games.create(qr,"홍길동","01012345678","request-2");assertEquals("HAS_ACTIVE_COUPON",participation.state(store.id,"01012345678").state());Coupon c=games.reveal(game.publicId);assertEquals(CouponStatus.REDEEMED,couponService.redeem(c.couponToken,"123456","127.0.0.2").status);AppException e=assertThrows(AppException.class,()->couponService.redeem(c.couponToken,"123456","127.0.0.2"));assertEquals("COUPON_ALREADY_REDEEMED",e.code);assertEquals("COOLDOWN",participation.state(store.id,"01012345678").state());game.playedDate=LocalDate.now().minusDays(2);assertEquals("CAN_PLAY",participation.state(store.id,"01012345678").state());}
    @Test void nextDayExpiryAndPinAreEnforced(){prizes.findByStoreIdOrderByRank(store.id).forEach(p->p.redeemPolicy=RedeemPolicy.NEXT_DAY);Coupon c=games.reveal(games.create(qr,"A","01055556666","request-5").publicId);assertEquals("COUPON_NOT_YET_VALID",assertThrows(AppException.class,()->couponService.redeem(c.couponToken,"123456","127.0.0.4")).code);c.validFrom=Instant.now().minusSeconds(1);assertEquals("STAFF_PIN_INVALID",assertThrows(AppException.class,()->couponService.redeem(c.couponToken,"654321","127.0.0.4")).code);c.expiresAt=Instant.now().minusSeconds(1);assertEquals("COUPON_EXPIRED",assertThrows(AppException.class,()->couponService.redeem(c.couponToken,"123456","127.0.0.4")).code);}
    @Test void participationIsPerStoreAndInactiveStoreIsBlocked(){Instant now=Instant.now();Store other=new Store();other.name="other";other.phone="0200000001";other.staffPinHash=encoder.encode("654321");other.status=StoreStatus.ACTIVE;other.createdAt=now;other.updatedAt=now;stores.save(other);games.create(qr,"A","01088889999","request-6");assertEquals("HAS_ACTIVE_COUPON",participation.state(store.id,"01088889999").state());assertEquals("CAN_PLAY",participation.state(other.id,"01088889999").state());store.status=StoreStatus.INACTIVE;assertEquals("STORE_INACTIVE",assertThrows(AppException.class,()->games.create(qr,"B","01077778888","request-inactive")).code);}
    @Test void signUpProvisionsStoreForTheOwnerAndRejectsDuplicates(){AdminSignupService.Request r=new AdminSignupService.Request("secret1234","secret1234","owner@test.com","홍대표","010-2222-3333","테스트상회","123-45-67890");
        StoreProvisioningService.Provisioned p=signup.signUp(r);
        assertEquals("테스트상회",p.store().name);assertTrue(p.staffPin().matches("\\d{6}"));assertEquals(3,prizes.findByStoreIdOrderByRank(p.store().id).size());
        assertEquals("3등 상품",prizes.findByStoreIdAndRank(p.store().id,3).orElseThrow().name);
        assertEquals(3,GameConfigService.rankCount(config.load(p.store().id)));
        AdminUser owner=admins.findByEmail("owner@test.com").orElseThrow();assertEquals(AdminRole.STORE_ADMIN,owner.role);assertEquals("1234567890",p.store().businessNumber);
        assertEquals("01022223333",owner.phone,"전화번호는 숫자만 남긴다");
        assertTrue(memberships.existsByAdminIdAndStoreId(owner.id,p.store().id));
        assertEquals("DUPLICATE_EMAIL",assertThrows(AppException.class,()->signup.signUp(new AdminSignupService.Request("secret1234","secret1234","owner@test.com","김대표","01022223334","다른상회","1234567891"))).code);
        assertEquals("DUPLICATE_BUSINESS_NUMBER",assertThrows(AppException.class,()->signup.signUp(new AdminSignupService.Request("secret1234","secret1234","other@test.com","김대표","01022223334","다른상회","1234567890"))).code);
        assertEquals("PASSWORD_MISMATCH",assertThrows(AppException.class,()->signup.signUp(new AdminSignupService.Request("secret1234","secret9999","new@test.com","김대표","01022223334","다른상회","1234567892"))).code);
        assertEquals("WEAK_PASSWORD",assertThrows(AppException.class,()->signup.signUp(new AdminSignupService.Request("short","short","new2@test.com","김대표","01022223334","다른상회","1234567893"))).code);}
    @Test void handWrittenInputIsNormalizedAndBadInputIsRejected(){
        assertEquals("01012345678",Inputs.phone("010-1234-5678"));
        assertEquals("01012345678",Inputs.phone(" 010 1234 5678 "));
        assertEquals("1234567890",Inputs.businessNumber("123-45-67890"));
        assertEquals("owner@test.com",Inputs.email("  Owner@Test.COM "));
        // 011은 더 이상 받지 않고, 자릿수가 넘치면 잘라내지 않고 거부한다
        assertEquals("INVALID_PHONE",assertThrows(AppException.class,()->Inputs.phone("01112345678")).code);
        assertEquals("INVALID_PHONE",assertThrows(AppException.class,()->Inputs.phone("0101234567")).code);
        assertEquals("INVALID_PHONE",assertThrows(AppException.class,()->Inputs.phone("010123456789")).code);
        assertEquals("INVALID_BUSINESS_NUMBER",assertThrows(AppException.class,()->Inputs.businessNumber("12345")).code);
        assertEquals("INVALID_EMAIL",assertThrows(AppException.class,()->Inputs.email("not-an-email")).code);}
    @Test void personalDataUsesAuthenticatedEncryption(){String value="홍길동/01012345678",cipher=personalData.encrypt(value);assertNotEquals(value,cipher);assertEquals(value,personalData.decrypt(cipher));assertThrows(IllegalStateException.class,()->personalData.decrypt(cipher.substring(0,cipher.length()-2)+"AA"));}
}
