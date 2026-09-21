package com.yutreview;

import jakarta.persistence.*;
import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 짧은 TTL 카운터 한 장.
 *
 * Redis를 새로 들이지 않는다는 제약 때문에 rate limit이 DB에 산다. 대신 행은 bucket 하나당 하나뿐이고
 * 증가는 조건부 UPDATE라 동시 요청이 한도를 넘겨 쓰지 못한다(AiMonthlyQuota와 같은 전략).
 *
 * 같은 테이블의 행을 게임 생성의 <b>고객 단위 잠금</b>으로도 쓴다. 예전에는 매장 행을 통째로
 * FOR UPDATE 잡아서, 한 손님의 게임이 그 매장의 다른 모든 손님을 줄 세웠다.
 */
@Entity @Table(name="rate_counters",indexes=@Index(columnList="expires_at"))
class RateCounter {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) Long id;
    @Column(nullable=false,unique=true,length=200) String bucket;
    @Column(name="window_start",nullable=false) Instant windowStart;
    @Column(nullable=false) int count;
    @Column(name="expires_at",nullable=false) Instant expiresAt;
}

interface RateCounterRepository extends JpaRepository<RateCounter,Long> {
    Optional<RateCounter> findByBucket(String bucket);

    /** 창이 아직 살아 있으면 1 올린다. 0이 돌아오면 행이 없거나 창이 지난 것이다. */
    @Modifying(flushAutomatically=true,clearAutomatically=true)
    @Query("update RateCounter c set c.count=c.count+1 where c.bucket=:bucket and c.windowStart>:floor")
    int increment(@Param("bucket") String bucket,@Param("floor") Instant floor);

    /** 창이 지났으면 새 창으로 갈아 끼운다. 이번 요청이 그 창의 1번이다. */
    @Modifying(flushAutomatically=true,clearAutomatically=true)
    @Query("update RateCounter c set c.windowStart=:now,c.count=1,c.expiresAt=:expiresAt "
        + "where c.bucket=:bucket and c.windowStart<=:floor")
    int reset(@Param("bucket") String bucket,@Param("now") Instant now,@Param("floor") Instant floor,
        @Param("expiresAt") Instant expiresAt);

    @Modifying(flushAutomatically=true,clearAutomatically=true)
    @Query("delete from RateCounter c where c.expiresAt<:now")
    int purge(@Param("now") Instant now);

    @Modifying(flushAutomatically=true,clearAutomatically=true)
    @Query("delete from RateCounter c where c.bucket=:bucket")
    int clear(@Param("bucket") String bucket);

    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select c from RateCounter c where c.bucket=:bucket")
    Optional<RateCounter> findForUpdate(@Param("bucket") String bucket);
}

/**
 * 공개 엔드포인트의 지속형 rate limit.
 *
 * 정상 QR 손님을 막지 않는 것이 첫 번째 조건이다. 그래서 매장 단위 quota와 고객 단위 제한을 분리한다.
 * 고객 한 명의 참여 제한은 여전히 2일 쿨타임이 authoritative이고, 여기 있는 숫자는 "한 매장/한 IP가
 * 서버를 통째로 밀어붙이는 것"만 끊는다.
 */
@Service class RateLimitService {
    /**
     * 행 수 상한. 넘으면 만료분을 먼저 치우고, 그래도 넘으면 새 bucket을 거절한다(fail closed).
     *
     * 한 행이 200바이트도 안 되므로 20만 행이라도 수십 MB다. 이 숫자가 빡빡하면 공격이 아니라
     * 정상 트래픽이 먼저 벽에 닿고, fail closed가 곧 자체 장애가 된다.
     */
    static final long MAX_ROWS=200_000;
    /**
     * 고객 단위 잠금 행의 수명.
     *
     * 잠금은 요청 하나가 끝날 때까지만 필요하다. 처음에 30일로 두었더니 (매장, 전화번호)마다
     * 행이 한 달을 살아서 `rate_counters`가 고객 수와 1:1로 자랐다. 부하 테스트에서 게임 8,367건에
     * 잠금 행 8,367개가 쌓였고, 목표 규모(50곳 × 30명/일 × 30일 ≈ 45,000)면 상한에 닿아
     * 정상 손님 전원이 429를 받는다. 1시간이면 동시 요청을 직렬화하기에 충분하고, 정리 주기(10분)가
     * 그 뒤를 따라간다. 행이 지워져도 다음 요청이 다시 만든다.
     */
    static final Duration LOCK_TTL=Duration.ofHours(1);
    /** 거절 집계용 bucket 접두사와 창. 한도가 아니라 관측값이라 절대 예외를 던지지 않는다. */
    private static final String REJECTION_BUCKET="rejected:";
    private static final Duration REJECTION_WINDOW=Duration.ofHours(24);
    private static final Logger log=LoggerFactory.getLogger(RateLimitService.class);

    private final RateCounterRepository counters;private final Clock clock;
    /**
     * 행 생성만 별도 트랜잭션으로 돌린다.
     *
     * 유니크 충돌은 동시 요청이 먼저 만들었다는 정상 신호인데, 호출부 트랜잭션 안에서 터지면
     * 영속성 컨텍스트가 오염돼 다음 flush에서 죽는다(AI 쿼터 행에서 똑같이 당했다).
     * `@Transactional` 자기호출은 프록시를 타지 않으므로 템플릿을 직접 쓴다.
     */
    private final org.springframework.transaction.support.TransactionTemplate newTransaction;
    RateLimitService(RateCounterRepository counters,Clock clock,
        org.springframework.transaction.PlatformTransactionManager transactions){
        this.counters=counters;this.clock=clock;
        this.newTransaction=new org.springframework.transaction.support.TransactionTemplate(transactions);
        this.newTransaction.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * 한 번의 시도를 세고, 창 안에서 limit을 넘었으면 429로 끊는다.
     *
     * 별도 트랜잭션으로 돈다. 가입이 실패해 바깥 트랜잭션이 롤백돼도 시도 횟수는 남아야 한다.
     * 남지 않으면 실패하는 요청을 무한히 반복해 한도를 우회할 수 있다.
     */
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    void check(String bucket,int limit,Duration window,String code,String message){
        if(bump(bucket,window)<=limit)return;
        recordRejection(code);
        throw new AppException(code,message,HttpStatus.TOO_MANY_REQUESTS);
    }

    /** 현재 창의 시도 횟수(이번 시도 포함). 한도 판단은 호출부가 한다. */
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    int count(String bucket,Duration window){return bump(bucket,window);}

    /**
     * 세지 않고 읽기만. 모니터링 화면이 "지금 몇 번 거절했는지"를 보려고 카운터를 올리면 안 된다.
     * 창이 지난 행은 0으로 읽는다(다음 요청이 어차피 새 창으로 갈아 끼운다).
     */
    @Transactional(readOnly=true)
    int observed(String bucket,Duration window){
        Instant floor=clock.instant().minus(window);
        return counters.findByBucket(key(bucket)).filter(c->c.windowStart.isAfter(floor)).map(c->c.count).orElse(0);
    }

    /** 카운터 행 수. cardinality 상한에 얼마나 가까운지 보는 값이다. */
    @Transactional(readOnly=true) long rows(){return counters.count();}

    /**
     * 최근 24시간 거절 횟수.
     *
     * 이것 없이는 자원 고갈 방어가 눈을 감고 도는 것과 같다. 정상 손님을 막고 있는지, 실제로
     * 공격을 받고 있는지, 한도가 너무 빡빡한지를 구분할 근거가 여기밖에 없다.
     */
    @Transactional(readOnly=true)
    int rejectionsLast24h(String code){return observed(REJECTION_BUCKET+code,REJECTION_WINDOW);}

    private void recordRejection(String code){
        // 반드시 별도 트랜잭션이다. 거절은 예외로 끝나므로 이 메서드의 트랜잭션은 곧 롤백된다.
        // 같은 트랜잭션에 기록하면 거절 기록만 정확히 전부 사라진다.
        try{newTransaction.executeWithoutResult(status->bump(REJECTION_BUCKET+code,REJECTION_WINDOW));}
        catch(RuntimeException e){log.debug("rejection counter not recorded: {}",code);}
    }

    private int bump(String bucket,Duration window){
        Instant now=clock.instant(),floor=now.minus(window),expiresAt=now.plus(window);
        String key=key(bucket);
        if(counters.reset(key,now,floor,expiresAt)==0&&counters.increment(key,floor)==0){
            if(!insert(key,now,expiresAt))counters.increment(key,floor);
        }
        return counters.findByBucket(key).map(c->c.count).orElse(1);
    }

    /** 성공했으면 그 키의 카운터를 지운다. 로그인 성공이 다음 로그인을 막지 않게 하는 유일한 이유다. */
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    void succeeded(String bucket){counters.clear(key(bucket));}

    /**
     * 고객 키 단위 직렬화.
     *
     * 호출부의 트랜잭션 안에서 잠가야 게임 생성이 끝날 때까지 잠금이 유지된다. 그래서 여기엔
     * REQUIRES_NEW를 붙이지 않는다. 행을 만드는 쪽만 별도 트랜잭션이다(유니크 충돌이 바깥
     * 영속성 컨텍스트를 오염시키면 다음 flush에서 죽는다).
     */
    void lock(String bucket){
        String key=key("lock:"+bucket);
        if(counters.findForUpdate(key).isPresent())return;
        Instant now=clock.instant();
        insert(key,now,now.plus(LOCK_TTL));
        counters.findForUpdate(key);
    }

    /** 만료분 정리. 잠금 행도 같은 청소에 함께 쓸려 나가고, 다음 요청이 다시 만든다. */
    @Scheduled(fixedDelay=600_000,initialDelay=600_000)
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    void scheduledPurge(){
        int removed=counters.purge(clock.instant());
        if(removed>0)log.debug("rate counters purged: {}",removed);
    }

    /** 새 행. 유니크 충돌은 동시 요청이 먼저 만든 것이므로 실패가 아니다. */
    private boolean insert(String key,Instant now,Instant expiresAt){
        return Boolean.TRUE.equals(newTransaction.execute(status->{
            if(counters.count()>=MAX_ROWS){
                counters.purge(now);
                if(counters.count()>=MAX_ROWS)
                    throw new AppException("RATE_LIMITED","요청이 많습니다. 잠시 후 다시 시도해 주세요.",HttpStatus.TOO_MANY_REQUESTS);
            }
            RateCounter c=new RateCounter();c.bucket=key;c.windowStart=now;c.count=1;c.expiresAt=expiresAt;
            try{counters.saveAndFlush(c);return true;}
            catch(DataIntegrityViolationException e){status.setRollbackOnly();return false;}
        }));
    }

    /**
     * bucket 키는 원문을 그대로 쓰지 않는다. 전화번호 해시·이메일이 그대로 들어가면 rate limit
     * 테이블이 또 하나의 개인정보 저장소가 된다. 길이 상한(컬럼 200)도 여기서 보장된다.
     */
    private static String key(String bucket){
        int split=bucket.indexOf(':');
        String prefix=split<0?bucket:bucket.substring(0,split);
        return prefix+":"+HexFormat.of().formatHex(sha256(bucket)).substring(0,40);
    }
    private static byte[] sha256(String value){
        try{return java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
}

/**
 * 클라이언트 IP 하나를 정하는 유일한 자리.
 *
 * 예전에는 컨트롤러마다 `X-Real-IP`를 무조건 믿었다. cloudflared를 거치면 Nginx가 보는 peer가
 * 터널 컨테이너로 고정돼 모든 손님이 한 IP로 뭉쳤고, 헤더를 직접 보내면 누구나 남의 버킷을
 * 채울 수 있었다. 그래서 신뢰 대역에서 온 요청의 헤더만 해석하고, 나머지는 socket peer를 쓴다.
 */
@Component class ClientIpResolver {
    /** 패키지 공개다. 운영자 접근 통제(OperatorAccess)가 같은 파싱과 같은 비교를 쓴다. */
    record Cidr(byte[] address,int bits){
        boolean contains(byte[] candidate){
            if(candidate.length!=address.length)return false;
            for(int i=0;i<bits/8;i++)if(candidate[i]!=address[i])return false;
            int remainder=bits%8;
            if(remainder==0)return true;
            int mask=0xFF<<(8-remainder);
            return (candidate[bits/8]&mask)==(address[bits/8]&mask);
        }
    }
    private final List<Cidr> trusted;
    ClientIpResolver(@Value("${app.trusted-proxies:}") String configured){
        this.trusted=parseCidrs(configured);
    }

    /**
     * 쉼표로 나열한 CIDR 목록을 읽는다. 빈 값은 빈 목록이다.
     *
     * 잘못된 값은 여기서 기동을 멈춘다. 조용히 건너뛰면 오타 하나로 목록이 비고, 비어 있는 목록은
     * "아무도 신뢰하지 않음"이 아니라 설정이 사라진 상태다. 둘을 구분할 수 없게 만들지 않는다.
     */
    static List<Cidr> parseCidrs(String configured){
        List<Cidr> parsed=new ArrayList<>();
        if(configured==null)return List.of();
        for(String entry:configured.split(",")){
            String value=entry.trim();
            if(value.isEmpty())continue;
            int slash=value.indexOf('/');
            byte[] address=parse(slash<0?value:value.substring(0,slash));
            if(address==null)throw new IllegalArgumentException("CIDR entry is not an IP range: "+value);
            int bits=slash<0?address.length*8:Integer.parseInt(value.substring(slash+1));
            if(bits<0||bits>address.length*8)throw new IllegalArgumentException("CIDR prefix is out of range: "+value);
            parsed.add(new Cidr(address,bits));
        }
        return List.copyOf(parsed);
    }

    /** 목록이 비어 있으면 어떤 주소도 맞지 않는다. 빈 목록을 "전부 허용"으로 읽지 말 것. */
    static boolean matches(List<Cidr> cidrs,String ip){
        byte[] address=parse(ip);
        if(address==null||cidrs.isEmpty())return false;
        return cidrs.stream().anyMatch(c->c.contains(address));
    }

    String resolve(HttpServletRequest request){
        String peer=request.getRemoteAddr();
        byte[] peerAddress=parse(peer);
        if(peerAddress==null||trusted.stream().noneMatch(c->c.contains(peerAddress)))return peer;
        String forwarded=request.getHeader("X-Real-IP");
        if(forwarded==null||forwarded.isBlank())return peer;
        String first=forwarded.split(",")[0].trim();
        return parse(first)==null?peer:first;
    }

    /** 리터럴 IP만 받는다. 호스트명을 그대로 넘기면 헤더 한 줄로 DNS 조회를 시킬 수 있다. */
    private static byte[] parse(String value){
        if(value==null||value.isBlank()||!value.matches("[0-9A-Fa-f:.%]+"))return null;
        try{return InetAddress.getByName(value).getAddress();}
        catch(UnknownHostException e){return null;}
    }
}
