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
    @Autowired PasswordEncoder encoder; @Autowired StaffVerificationService verification; @Autowired GameService games;
    @Autowired CouponRepository coupons; @Autowired CouponService couponService; @Autowired ParticipationService participation; @Autowired PhoneService personalData;
    Store store; String qr;
    @BeforeEach void setup(){Instant now=Instant.now();store=new Store();store.name="test";store.phone="0200000000";store.staffPinHash=encoder.encode("123456");store.status=StoreStatus.ACTIVE;store.createdAt=now;store.updatedAt=now;stores.save(store);StoreQrCode q=new StoreQrCode();q.store=store;q.publicToken="qr-"+System.nanoTime();q.status=QrStatus.ACTIVE;q.createdAt=now;qrs.save(q);qr=q.publicToken;for(Tier t:Tier.values()){Prize p=new Prize();p.store=store;p.tier=t;p.name=t.name();p.redeemPolicy=RedeemPolicy.ANYTIME;p.active=true;p.createdAt=now;p.updatedAt=now;prizes.save(p);}}
    @Test void probabilityBoundaries(){assertEquals(YutResult.DO,GameResultGenerator.from(0));assertEquals(YutResult.GAE,GameResultGenerator.from(.325));assertEquals(YutResult.GEOL,GameResultGenerator.from(.65));assertEquals(YutResult.YUT,GameResultGenerator.from(.775));assertEquals(YutResult.MO,GameResultGenerator.from(.9));}
    @Test void oneGameOneCouponAndIdempotentReveal(){String v=verification.verify(qr,"123456","127.0.0.1");GamePlay first=games.create(qr,"홍길동","010-1234-5678",v,"request-1");GamePlay retry=games.create(qr,"홍길동","01012345678","already-used","request-1");assertEquals(first.id,retry.id);Coupon a=games.reveal(first.publicId);Coupon b=games.reveal(first.publicId);assertEquals(a.id,b.id);assertEquals(1,coupons.findByStoreIdOrderByIssuedAtDesc(store.id).size());}
    @Test void activeCouponPrecedesCooldownAndRedeemsOnce(){String v=verification.verify(qr,"123456","127.0.0.2");GamePlay game=games.create(qr,"홍길동","01012345678",v,"request-2");assertEquals("HAS_ACTIVE_COUPON",participation.state(store.id,"01012345678").state());Coupon c=games.reveal(game.publicId);assertEquals(CouponStatus.REDEEMED,couponService.redeem(c.couponToken,"123456","127.0.0.2").status);AppException e=assertThrows(AppException.class,()->couponService.redeem(c.couponToken,"123456","127.0.0.2"));assertEquals("COUPON_ALREADY_REDEEMED",e.code);assertEquals("COOLDOWN",participation.state(store.id,"01012345678").state());game.playedDate=LocalDate.now().minusDays(2);assertEquals("CAN_PLAY",participation.state(store.id,"01012345678").state());}
    @Test void verificationIsOneUseAndStoreBound(){String v=verification.verify(qr,"123456","127.0.0.3");games.create(qr,"A","01011112222",v,"request-3");AppException e=assertThrows(AppException.class,()->games.create(qr,"B","01033334444",v,"request-4"));assertEquals("STAFF_VERIFICATION_EXPIRED",e.code);}
    @Test void nextDayExpiryAndPinAreEnforced(){prizes.findByStoreIdOrderByTier(store.id).forEach(p->p.redeemPolicy=RedeemPolicy.NEXT_DAY);String v=verification.verify(qr,"123456","127.0.0.4");Coupon c=games.reveal(games.create(qr,"A","01055556666",v,"request-5").publicId);assertEquals("COUPON_NOT_YET_VALID",assertThrows(AppException.class,()->couponService.redeem(c.couponToken,"123456","127.0.0.4")).code);c.validFrom=Instant.now().minusSeconds(1);assertEquals("STAFF_PIN_INVALID",assertThrows(AppException.class,()->couponService.redeem(c.couponToken,"654321","127.0.0.4")).code);c.expiresAt=Instant.now().minusSeconds(1);assertEquals("COUPON_EXPIRED",assertThrows(AppException.class,()->couponService.redeem(c.couponToken,"123456","127.0.0.4")).code);}
    @Test void participationIsPerStoreAndInactiveStoreIsBlocked(){Instant now=Instant.now();Store other=new Store();other.name="other";other.phone="0200000001";other.staffPinHash=encoder.encode("654321");other.status=StoreStatus.ACTIVE;other.createdAt=now;other.updatedAt=now;stores.save(other);String v=verification.verify(qr,"123456","127.0.0.5");games.create(qr,"A","01088889999",v,"request-6");assertEquals("HAS_ACTIVE_COUPON",participation.state(store.id,"01088889999").state());assertEquals("CAN_PLAY",participation.state(other.id,"01088889999").state());store.status=StoreStatus.INACTIVE;assertEquals("STORE_INACTIVE",assertThrows(AppException.class,()->verification.verify(qr,"123456","127.0.0.6")).code);}
    @Test void personalDataUsesAuthenticatedEncryption(){String value="홍길동/01012345678",cipher=personalData.encrypt(value);assertNotEquals(value,cipher);assertEquals(value,personalData.decrypt(cipher));assertThrows(IllegalStateException.class,()->personalData.decrypt(cipher.substring(0,cipher.length()-2)+"AA"));}
}
