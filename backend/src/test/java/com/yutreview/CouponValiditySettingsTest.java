package com.yutreview;

import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

/**
 * 매장별 쿠폰 사용 기한.
 *
 * 이 테스트가 지키는 것은 두 가지다. 하나는 만료 시각이 달력상 정확히 어디에 떨어지는가(off-by-one,
 * 월말·윤년·시간대), 다른 하나는 설정을 바꿔도 <b>이미 발급된 쿠폰은 움직이지 않는다</b>는 것이다.
 * 뒤쪽이 깨지면 손님이 손에 든 쿠폰의 만료일이 사장의 설정 변경으로 뒤바뀐다.
 */
@SpringBootTest @Transactional class CouponValiditySettingsTest {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Autowired StoreRepository stores; @Autowired QrRepository qrs; @Autowired PasswordEncoder encoder;
    @Autowired GameConfigService config; @Autowired GameService games; @Autowired CouponRepository coupons;
    @Autowired PrizeRepository prizes; @Autowired StoreEventSettingsService eventSettings;
    @Autowired StoreAccessService access; @Autowired AdminSignupService signup; @Autowired AdminUserRepository admins;

    Store store; String qr;

    @BeforeEach void setup(){ Store s=newStore("test"); store=s; qr=tokenOf(s); }

    private Store newStore(String name){
        Instant now=Instant.now();
        Store s=new Store();s.name=name;s.phone="0200000000";s.staffPinHash=encoder.encode("123456");
        s.status=StoreStatus.ACTIVE;s.createdAt=now;s.updatedAt=now;stores.save(s);
        StoreQrCode q=new StoreQrCode();q.store=s;q.publicToken="qr-"+UUID.randomUUID();q.status=QrStatus.ACTIVE;q.createdAt=now;qrs.save(q);
        config.save(s,GameConfigService.defaults());
        return s;
    }

    private String tokenOf(Store s){ return qrs.findFirstByStoreIdAndStatus(s.id,QrStatus.ACTIVE).orElseThrow().publicToken; }

    /** 참여마다 번호가 달라야 2일 쿨타임에 걸리지 않는다. */
    private Coupon play(String token,String phone){
        GamePlay g=games.create(token,"손님",phone,UUID.randomUUID().toString());
        return coupons.findByGamePlayId(g.id).orElseThrow();
    }

    private LocalDate expiryDate(Coupon c){ return c.expiresAt.atZone(SEOUL).toLocalDate(); }
    private LocalDate validFromDate(Coupon c){ return c.validFrom.atZone(SEOUL).toLocalDate(); }

    // ---------- 기본값과 범위 ----------

    @Test void storesWithoutASettingsRowFallBackToNinetyDays(){
        // 기존 매장을 백필하지 않아도 되게 하는 규칙이다. 행이 없으면 곧 90일이다.
        assertTrue(eventSettings.find(store.id).isEmpty());
        assertEquals(90,eventSettings.couponValidityDays(store.id));
        assertEquals(90,StoreEventSettings.DEFAULT_COUPON_VALIDITY_DAYS);
    }

    @Test void everyAllowedValueIsAccepted(){
        for(int days:new int[]{1,7,30,90,180,365}){
            eventSettings.save(store,days);
            assertEquals(days,eventSettings.couponValidityDays(store.id),days+"일 저장 실패");
        }
    }

    @Test void outOfRangeIsRejectedByTheServer(){
        // 화면 검증만 믿지 않는다. 서버가 마지막 선이다.
        for(Integer bad:new Integer[]{0,366,-1,-90,null,Integer.MAX_VALUE,Integer.MIN_VALUE})
            assertEquals("INVALID_COUPON_VALIDITY_DAYS",
                    assertThrows(AppException.class,()->eventSettings.save(store,bad)).code,
                    bad+"이(가) 거부되지 않았다");
        // 거부된 뒤에도 설정은 오염되지 않는다.
        assertEquals(90,eventSettings.couponValidityDays(store.id));
    }

    @Test void theBoundariesThemselvesAreAllowed(){
        eventSettings.save(store,StoreEventSettings.MIN_COUPON_VALIDITY_DAYS);
        assertEquals(1,eventSettings.couponValidityDays(store.id));
        eventSettings.save(store,StoreEventSettings.MAX_COUPON_VALIDITY_DAYS);
        assertEquals(365,eventSettings.couponValidityDays(store.id));
    }

    // ---------- 만료 시각 계산 (off-by-one) ----------

    @Test void oneDayMeansUntilMidnightOfTheSameDay(){
        // 사장이 "1일"로 읽는 것은 오늘 자정까지다. 내일까지가 아니다.
        Instant from=LocalDate.of(2026,3,10).atTime(14,30).atZone(SEOUL).toInstant();
        assertEquals(LocalDate.of(2026,3,10).atTime(23,59,59).atZone(SEOUL).toInstant(),
                StoreEventSettingsService.expiresAt(from,1,SEOUL));
    }

    @Test void theNthDayIsTheLastUsableDay(){
        LocalDate start=LocalDate.of(2026,1,1);
        Instant from=start.atTime(9,0).atZone(SEOUL).toInstant();
        for(int days:new int[]{1,7,30,90,180,365}){
            ZonedDateTime end=StoreEventSettingsService.expiresAt(from,days,SEOUL).atZone(SEOUL);
            assertEquals(start.plusDays(days-1L),end.toLocalDate(),days+"일의 마지막 날이 어긋났다");
            assertEquals(LocalTime.of(23,59,59),end.toLocalTime(),days+"일의 만료 시각이 어긋났다");
            // 실제로 쓸 수 있는 달력일 수가 정확히 days다.
            assertEquals(days,start.datesUntil(end.toLocalDate().plusDays(1)).count());
        }
    }

    @Test void monthEndAndLeapDayAreHandledByTheCalendarNotByArithmetic(){
        // 1월 31일 + 30일 = 3월 1일(2026년은 평년이라 2월이 28일)
        assertEquals(LocalDate.of(2026,3,1),
                StoreEventSettingsService.expiresAt(LocalDate.of(2026,1,31).atTime(12,0).atZone(SEOUL).toInstant(),30,SEOUL)
                        .atZone(SEOUL).toLocalDate());
        // 윤년: 2028년 2월 1일 + 30일은 2월 29일을 지나 3월 1일이 된다.
        assertEquals(LocalDate.of(2028,3,1),
                StoreEventSettingsService.expiresAt(LocalDate.of(2028,2,1).atTime(12,0).atZone(SEOUL).toInstant(),30,SEOUL)
                        .atZone(SEOUL).toLocalDate());
        // 윤년 2월 29일에 발급된 1일짜리는 그날 자정까지다.
        assertEquals(LocalDate.of(2028,2,29),
                StoreEventSettingsService.expiresAt(LocalDate.of(2028,2,29).atTime(20,0).atZone(SEOUL).toInstant(),1,SEOUL)
                        .atZone(SEOUL).toLocalDate());
    }

    @Test void theDayIsDecidedInSeoulNotInUtc(){
        // KST 2026-03-10 00:30 = UTC 2026-03-09 15:30. UTC로 날짜를 세면 하루가 밀린다.
        Instant justAfterSeoulMidnight=LocalDate.of(2026,3,10).atTime(0,30).atZone(SEOUL).toInstant();
        assertEquals(LocalDate.of(2026,3,9),justAfterSeoulMidnight.atZone(ZoneOffset.UTC).toLocalDate());
        assertEquals(LocalDate.of(2026,3,10),
                StoreEventSettingsService.expiresAt(justAfterSeoulMidnight,1,SEOUL).atZone(SEOUL).toLocalDate());
    }

    @Test void aCouponIssuedInTheLastSecondOfTheDayIsStillUsable(){
        // 23:59:59.5에 발급된 1일짜리는 그날 23:59:59가 이미 지났다. 죽은 쿠폰을 주지 않는다.
        Instant lastSecond=LocalDate.of(2026,3,10).atTime(23,59,59).plusNanos(500_000_000).atZone(SEOUL).toInstant();
        Instant expires=StoreEventSettingsService.expiresAt(lastSecond,1,SEOUL);
        assertTrue(expires.isAfter(lastSecond),"만료가 발급 시각보다 앞선다");
        assertEquals(LocalDate.of(2026,3,11),expires.atZone(SEOUL).toLocalDate());
    }

    // ---------- 발급 시 반영 ----------

    @Test void anIssuedCouponUsesTheStoreSettingAtThatMoment(){
        eventSettings.save(store,30);
        Coupon c=play(qr,"01011112222");
        assertEquals(validFromDate(c).plusDays(29),expiryDate(c));
        assertEquals(LocalTime.of(23,59,59),c.expiresAt.atZone(SEOUL).toLocalTime());
    }

    @Test void twoStoresIssueDifferentExpiriesAndDoNotLeakIntoEachOther(){
        Store b=newStore("다른매장");
        eventSettings.save(store,30);
        eventSettings.save(b,180);

        Coupon fromA=play(qr,"01011112222");
        Coupon fromB=play(tokenOf(b),"01033334444");
        assertEquals(validFromDate(fromA).plusDays(29),expiryDate(fromA));
        assertEquals(validFromDate(fromB).plusDays(179),expiryDate(fromB));

        // A를 바꿔도 B는 그대로다.
        eventSettings.save(store,7);
        assertEquals(7,eventSettings.couponValidityDays(store.id));
        assertEquals(180,eventSettings.couponValidityDays(b.id));
    }

    @Test void changingTheSettingNeverMovesACouponAlreadyInACustomersHand(){
        eventSettings.save(store,90);
        Coupon issued=play(qr,"01011112222");
        Instant frozen=issued.expiresAt;
        assertEquals(validFromDate(issued).plusDays(89),expiryDate(issued));

        // 줄여도 그대로.
        eventSettings.save(store,30);
        assertEquals(frozen,coupons.findById(issued.id).orElseThrow().expiresAt);
        // 늘려도 그대로.
        eventSettings.save(store,180);
        assertEquals(frozen,coupons.findById(issued.id).orElseThrow().expiresAt);

        // 변경 이후 새로 발급되는 쿠폰에만 적용된다.
        Coupon next=play(qr,"01055556666");
        assertEquals(validFromDate(next).plusDays(179),expiryDate(next));
        assertEquals(frozen,coupons.findById(issued.id).orElseThrow().expiresAt);
    }

    @Test void nextDayCouponsGetTheFullPeriodAfterTheyBecomeUsable(){
        // NEXT_DAY는 다음 날부터 쓸 수 있다. 발급일을 기준으로 기한을 세면 하루를 손해 본다.
        prizes.findByStoreIdOrderByRank(store.id).forEach(p->p.redeemPolicy=RedeemPolicy.NEXT_DAY);
        eventSettings.save(store,7);
        Coupon c=play(qr,"01011112222");

        assertEquals(c.gamePlay.playedDate.plusDays(1),validFromDate(c));
        assertEquals(validFromDate(c).plusDays(6),expiryDate(c));
        assertTrue(c.expiresAt.isAfter(c.validFrom),"expiresAt은 언제나 validFrom보다 뒤여야 한다");
    }

    @Test void expiryAlwaysFollowsValidFromEvenAtTheShortestSetting(){
        prizes.findByStoreIdOrderByRank(store.id).forEach(p->p.redeemPolicy=RedeemPolicy.NEXT_DAY);
        eventSettings.save(store,1);
        Coupon c=play(qr,"01011112222");
        assertTrue(c.expiresAt.isAfter(c.validFrom));
        assertEquals(validFromDate(c),expiryDate(c));
    }

    // ---------- 권한 ----------

    @Test void anotherAdminCannotTouchThisStoresSettings(){
        AdminSignupService.Request r=new AdminSignupService.Request("secret1234","secret1234",
                "validity-other@test.com","김대표","01044445555","다른가게","5556667778");
        StoreProvisioningService.Provisioned other=signup.signUp(r);
        AdminUser owner=admins.findByEmail("validity-other@test.com").orElseThrow();

        // 관리자 API는 저장 전에 멤버십부터 본다. 남의 매장은 존재 여부도 알려주지 않는다.
        assertEquals("FORBIDDEN",assertThrows(AppException.class,()->access.member(owner.id,store.id)).code);
        // 자기 매장은 통과하고, 저장도 자기 매장에만 적용된다.
        access.member(owner.id,other.store().id);
        eventSettings.save(other.store(),14);
        assertEquals(14,eventSettings.couponValidityDays(other.store().id));
        assertEquals(90,eventSettings.couponValidityDays(store.id));
    }
}
