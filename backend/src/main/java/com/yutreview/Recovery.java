package com.yutreview;

import jakarta.persistence.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 전화번호로 쿠폰을 되찾는 한 번짜리 통로.
 *
 * 예전에는 매장 토큰과 전화번호만 맞으면 상태 조회 응답이 쿠폰 bearer token을 그대로 돌려줬다.
 * 번호를 아는 사람이면 누구나 남의 쿠폰 토큰을 손에 넣었다는 뜻이다. 지금은 5분·1회용 티켓만 주고,
 * 티켓을 실제로 들고 온 요청에만 쿠폰을 연다. 평문 티켓은 저장하지 않는다.
 *
 * MVP에 SMS 인증은 없다. 이 장치는 인증이 아니라 <b>노출 창을 좁히는 것</b>이 목적이다.
 */
@Entity @Table(name="coupon_recovery_sessions",indexes=@Index(columnList="expires_at"))
class CouponRecoverySession {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) Long id;
    @ManyToOne(optional=false) @JoinColumn(name="store_id",nullable=false) Store store;
    @ManyToOne(optional=false) @JoinColumn(name="coupon_id",nullable=false) Coupon coupon;
    @Column(name="phone_hash",nullable=false,length=64) String phoneHash;
    /** 평문 티켓의 SHA-256. 이 테이블이 새도 티켓은 재구성되지 않는다. */
    @Column(name="ticket_hash",nullable=false,unique=true,length=64) String ticketHash;
    @Column(name="created_at",nullable=false) Instant createdAt;
    @Column(name="expires_at",nullable=false) Instant expiresAt;
    @Column(name="used_at") Instant usedAt;
}

interface CouponRecoverySessionRepository extends JpaRepository<CouponRecoverySession,Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from CouponRecoverySession s join fetch s.coupon where s.ticketHash=:hash")
    Optional<CouponRecoverySession> findForUpdate(@Param("hash") String hash);

    @Modifying(flushAutomatically=true,clearAutomatically=true)
    @Query("delete from CouponRecoverySession s where s.expiresAt<:now")
    int purge(@Param("now") Instant now);
}

@Service class CouponRecoveryService {
    static final Duration TTL=Duration.ofMinutes(5);
    private final CouponRecoverySessionRepository sessions;private final Clock clock;
    CouponRecoveryService(CouponRecoverySessionRepository sessions,Clock clock){this.sessions=sessions;this.clock=clock;}

    /** 평문 티켓은 이 반환값이 유일한 사본이다. 로그에도 남기지 않는다. */
    @Transactional String issue(Store store,Coupon coupon,String phoneHash){
        Instant now=clock.instant();
        String ticket="rt_"+Tokens.random();
        CouponRecoverySession s=new CouponRecoverySession();
        s.store=store;s.coupon=coupon;s.phoneHash=phoneHash;s.ticketHash=hash(ticket);
        s.createdAt=now;s.expiresAt=now.plus(TTL);
        sessions.save(s);
        return ticket;
    }

    /**
     * 티켓 1회 사용.
     *
     * 만료·재사용·타 매장·미존재를 구분해 알려주지 않는다. 구분해 주면 티켓 하나로 "이 매장에
     * 이 번호의 쿠폰이 있는지"를 되물어 볼 수 있다.
     */
    @Transactional Coupon redeem(Long storeId,String ticket){
        if(ticket==null||ticket.isBlank())throw invalid();
        CouponRecoverySession s=sessions.findForUpdate(hash(ticket)).orElseThrow(CouponRecoveryService::invalid);
        Instant now=clock.instant();
        if(s.usedAt!=null||now.isAfter(s.expiresAt)||!s.store.id.equals(storeId))throw invalid();
        s.usedAt=now;
        return s.coupon;
    }

    @Scheduled(fixedDelay=900_000,initialDelay=900_000)
    @Transactional void scheduledPurge(){sessions.purge(clock.instant());}

    private static AppException invalid(){
        return new AppException("RECOVERY_TICKET_INVALID","쿠폰 확인 시간이 지났습니다. 번호를 다시 입력해 주세요.");
    }
    private static String hash(String ticket){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(ticket.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
}
