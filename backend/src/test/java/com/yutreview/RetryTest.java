package com.yutreview;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

/** 한판 더: 서버가 정한 게임에서만, 공개 뒤 한 번, 쿠폰을 늘리지 않고, 결정 후엔 되돌릴 수 없다. */
@SpringBootTest @Transactional class RetryTest {
    @Autowired StoreRepository stores; @Autowired QrRepository qrs; @Autowired GameConfigService config; @Autowired PasswordEncoder encoder;
    @Autowired GameService games; @Autowired GameRepository gameRepository; @Autowired CouponRepository coupons; @Autowired StoreEventSettingsService settings;
    Store store; String qr; int seq;

    @BeforeEach void setup(){
        Instant now=Instant.now();store=new Store();store.name="retry";store.phone="0200000000";store.staffPinHash=encoder.encode("123456");
        store.status=StoreStatus.ACTIVE;store.createdAt=now;store.updatedAt=now;stores.save(store);
        StoreQrCode q=new StoreQrCode();q.store=store;q.publicToken="qr-"+System.nanoTime();q.status=QrStatus.ACTIVE;q.createdAt=now;qrs.save(q);qr=q.publicToken;
        config.save(store,GameConfigService.defaults());
    }
    private GamePlay play(){seq++;return games.create(qr,"손님","0101234"+String.format("%04d",seq),"retry-"+System.nanoTime()+"-"+seq);}
    private static boolean conflict(Runnable r){try{r.run();return false;}catch(AppException e){return e.code.equals("RETRY_NOT_AVAILABLE");}}

    @Test void offDefaultNeverOffers(){
        assertFalse(settings.retry(store.id).enabled());
        GamePlay g=play();Coupon c=games.reveal(g.publicId);
        assertFalse(games.retryAvailable(g,c));
        assertTrue(conflict(()->games.retry(g.publicId)));
    }

    @Test void everyNthGameGetsTheOffer(){
        settings.saveRetry(store,true,RetryMode.EVERY_N,3);
        assertFalse(Boolean.TRUE.equals(play().retryOffered));assertFalse(Boolean.TRUE.equals(play().retryOffered));
        assertTrue(play().retryOffered);
        assertFalse(Boolean.TRUE.equals(play().retryOffered));
        assertThrows(AppException.class,()->settings.saveRetry(store,true,RetryMode.RANDOM,1));
    }

    @Test void retryRerollsOnceAndReissuesTheSameCoupon(){
        settings.saveRetry(store,true,RetryMode.EVERY_N,2);
        play();GamePlay g=play();assertTrue(g.retryOffered);
        // 결과를 보기 전에는 고를 수 없다.
        assertTrue(conflict(()->games.retry(g.publicId)));
        Coupon first=games.reveal(g.publicId);String oldToken=first.couponToken;YutResult oldResult=g.yutResult;Long couponId=first.id;
        assertTrue(games.retryAvailable(g,first));

        GamePlay again=games.retry(g.publicId);String seed=again.animationSeed;
        assertEquals(oldResult,again.retryFromResult);assertNotNull(again.retryDecidedAt);assertEquals(GameStatus.CREATED,again.status);
        Coupon c=coupons.findByGamePlayId(g.id).orElseThrow();
        assertEquals(couponId,c.id);assertNotEquals(oldToken,c.couponToken);assertTrue(coupons.findByCouponToken(oldToken).isEmpty());
        assertEquals(again.prizeRank,c.prizeRankSnapshot);
        // 두 번 눌러도 다시 뽑지 않는다.
        assertEquals(seed,games.retry(g.publicId).animationSeed);
        Coupon revealed=games.reveal(g.publicId);
        assertFalse(games.retryAvailable(g,revealed));
        assertEquals(2,coupons.countByStoreIdAndStatus(store.id,CouponStatus.ISSUED));
    }

    @Test void keepingOrWaitingTooLongClosesTheOffer(){
        settings.saveRetry(store,true,RetryMode.EVERY_N,2);
        play();GamePlay kept=play();games.reveal(kept.publicId);
        games.keep(kept.publicId);games.keep(kept.publicId);
        assertTrue(conflict(()->games.retry(kept.publicId)));

        play();GamePlay late=play();games.reveal(late.publicId);
        late.revealedAt=Instant.now().minus(GameService.RETRY_WINDOW).minus(Duration.ofSeconds(1));gameRepository.save(late);
        assertTrue(conflict(()->games.retry(late.publicId)));
    }
}
