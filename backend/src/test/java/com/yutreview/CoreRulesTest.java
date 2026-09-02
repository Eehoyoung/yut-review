package com.yutreview;

import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest @Transactional class CoreRulesTest {
    @Autowired StoreRepository stores; @Autowired QrRepository qrs; @Autowired PrizeRepository prizes;
    @Autowired PasswordEncoder encoder; @Autowired GameService games;
    @Autowired CouponRepository coupons; @Autowired AdminSignupService signup; @Autowired AdminUserRepository admins; @Autowired MembershipRepository memberships; @Autowired CouponService couponService; @Autowired ParticipationService participation; @Autowired PhoneService personalData;
    Store store; String qr;
    @BeforeEach void setup(){Instant now=Instant.now();store=new Store();store.name="test";store.phone="0200000000";store.staffPinHash=encoder.encode("123456");store.status=StoreStatus.ACTIVE;store.createdAt=now;store.updatedAt=now;stores.save(store);StoreQrCode q=new StoreQrCode();q.store=store;q.publicToken="qr-"+System.nanoTime();q.status=QrStatus.ACTIVE;q.createdAt=now;qrs.save(q);qr=q.publicToken;for(Tier t:Tier.values()){Prize p=new Prize();p.store=store;p.tier=t;p.name=t.name();p.redeemPolicy=RedeemPolicy.ANYTIME;p.active=true;p.createdAt=now;p.updatedAt=now;prizes.save(p);}}
    @Test void probabilityBoundaries(){assertEquals(YutResult.DO,GameResultGenerator.from(0));assertEquals(YutResult.GAE,GameResultGenerator.from(.325));assertEquals(YutResult.GEOL,GameResultGenerator.from(.65));assertEquals(YutResult.YUT,GameResultGenerator.from(.775));assertEquals(YutResult.MO,GameResultGenerator.from(.9));}
    @Test void oneGameOneCouponAndIdempotentReveal(){GamePlay first=games.create(qr,"홍길동","010-1234-5678","request-1");GamePlay retry=games.create(qr,"홍길동","01012345678","request-1");assertEquals(first.id,retry.id);Coupon a=games.reveal(first.publicId);Coupon b=games.reveal(first.publicId);assertEquals(a.id,b.id);assertEquals(1,coupons.findByStoreIdOrderByIssuedAtDesc(store.id).size());}
    @Test void activeCouponPrecedesCooldownAndRedeemsOnce(){GamePlay game=games.create(qr,"홍길동","01012345678","request-2");assertEquals("HAS_ACTIVE_COUPON",participation.state(store.id,"01012345678").state());Coupon c=games.reveal(game.publicId);assertEquals(CouponStatus.REDEEMED,couponService.redeem(c.couponToken,"123456","127.0.0.2").status);AppException e=assertThrows(AppException.class,()->couponService.redeem(c.couponToken,"123456","127.0.0.2"));assertEquals("COUPON_ALREADY_REDEEMED",e.code);assertEquals("COOLDOWN",participation.state(store.id,"01012345678").state());game.playedDate=LocalDate.now().minusDays(2);assertEquals("CAN_PLAY",participation.state(store.id,"01012345678").state());}
    @Test void nextDayExpiryAndPinAreEnforced(){prizes.findByStoreIdOrderByTier(store.id).forEach(p->p.redeemPolicy=RedeemPolicy.NEXT_DAY);Coupon c=games.reveal(games.create(qr,"A","01055556666","request-5").publicId);assertEquals("COUPON_NOT_YET_VALID",assertThrows(AppException.class,()->couponService.redeem(c.couponToken,"123456","127.0.0.4")).code);c.validFrom=Instant.now().minusSeconds(1);assertEquals("STAFF_PIN_INVALID",assertThrows(AppException.class,()->couponService.redeem(c.couponToken,"654321","127.0.0.4")).code);c.expiresAt=Instant.now().minusSeconds(1);assertEquals("COUPON_EXPIRED",assertThrows(AppException.class,()->couponService.redeem(c.couponToken,"123456","127.0.0.4")).code);}
    @Test void participationIsPerStoreAndInactiveStoreIsBlocked(){Instant now=Instant.now();Store other=new Store();other.name="other";other.phone="0200000001";other.staffPinHash=encoder.encode("654321");other.status=StoreStatus.ACTIVE;other.createdAt=now;other.updatedAt=now;stores.save(other);games.create(qr,"A","01088889999","request-6");assertEquals("HAS_ACTIVE_COUPON",participation.state(store.id,"01088889999").state());assertEquals("CAN_PLAY",participation.state(other.id,"01088889999").state());store.status=StoreStatus.INACTIVE;assertEquals("STORE_INACTIVE",assertThrows(AppException.class,()->games.create(qr,"B","01077778888","request-inactive")).code);}
    @Test void signUpProvisionsStoreForTheOwnerAndRejectsDuplicates(){AdminSignupService.Request r=new AdminSignupService.Request("owner01","secret1234","secret1234","owner@test.com","홍대표","010-2222-3333","테스트상회","123-45-67890");
        StoreProvisioningService.Provisioned p=signup.signUp(r);
        assertEquals("테스트상회",p.store().name);assertTrue(p.staffPin().matches("\\d{6}"));assertEquals(3,prizes.findByStoreIdOrderByTier(p.store().id).size());
        assertEquals("3등 상품",prizes.findByStoreIdAndTier(p.store().id,Tier.TIER_1).orElseThrow().name);
        AdminUser owner=admins.findByLoginId("owner01").orElseThrow();assertEquals(AdminRole.STORE_ADMIN,owner.role);assertEquals("1234567890",p.store().businessNumber);
        assertTrue(memberships.existsByAdminIdAndStoreId(owner.id,p.store().id));
        assertEquals("DUPLICATE_LOGIN_ID",assertThrows(AppException.class,()->signup.signUp(new AdminSignupService.Request("owner01","secret1234","secret1234","other@test.com","김대표","01022223334","다른상회","1234567891"))).code);
        assertEquals("DUPLICATE_BUSINESS_NUMBER",assertThrows(AppException.class,()->signup.signUp(new AdminSignupService.Request("owner02","secret1234","secret1234","other@test.com","김대표","01022223334","다른상회","1234567890"))).code);
        assertEquals("PASSWORD_MISMATCH",assertThrows(AppException.class,()->signup.signUp(new AdminSignupService.Request("owner03","secret1234","secret9999","new@test.com","김대표","01022223334","다른상회","1234567892"))).code);
        assertEquals("WEAK_PASSWORD",assertThrows(AppException.class,()->signup.signUp(new AdminSignupService.Request("owner04","short","short","new@test.com","김대표","01022223334","다른상회","1234567893"))).code);}
    @Test void personalDataUsesAuthenticatedEncryption(){String value="홍길동/01012345678",cipher=personalData.encrypt(value);assertNotEquals(value,cipher);assertEquals(value,personalData.decrypt(cipher));assertThrows(IllegalStateException.class,()->personalData.decrypt(cipher.substring(0,cipher.length()-2)+"AA"));}
}
