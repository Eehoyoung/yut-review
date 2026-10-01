package com.yutreview;

import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 운영자용 자원 현황.
 *
 * 공개 게임 생성의 자원 고갈 방어는 쿼터와 상한으로 막는 것까지가 절반이고, 나머지 절반은
 * "지금 무엇을 막고 있는지 보이는 것"이다. 보이지 않으면 한도가 너무 빡빡해서 정상 손님을 막고
 * 있는지, 실제로 공격을 받고 있는지, 디스크가 차고 있는지를 구분할 수 없다.
 *
 * 전부 집계 숫자와 매장 공개 라벨뿐이다. 고객 이름·전화번호·해시·쿠폰 토큰은 어떤 값도 나가지 않는다.
 */
@Service class OperatorMonitoringService {
    /** 화면에 세우는 매장 수. 전부 나열하면 붐비는 곳을 못 찾는다. */
    private static final int BUSIEST_STORES=10;
    /** 관측하는 거절 코드. 새 한도를 추가하면 여기에도 넣어야 화면에 보인다. */
    private static final List<String> REJECTION_CODES=
        List.of("GAME_RATE_LIMITED","STORE_DAILY_LIMIT","SIGNUP_RATE_LIMITED","AUTH_RATE_LIMITED",
                "STAFF_PIN_RATE_LIMITED","RATE_LIMITED");

    private final RateLimitService rateLimits;private final GameService gameLimits;
    private final GameRepository games;private final CouponRepository coupons;
    private final CouponRecoverySessionRepository recoverySessions;private final AiChatTurnRepository chatTurns;
    private final StoreRepository stores;private final EntityManager entityManager;private final Clock clock;
    OperatorMonitoringService(RateLimitService rateLimits,GameService gameLimits,GameRepository games,
        CouponRepository coupons,CouponRecoverySessionRepository recoverySessions,AiChatTurnRepository chatTurns,
        StoreRepository stores,EntityManager entityManager,Clock clock){
        this.rateLimits=rateLimits;this.gameLimits=gameLimits;this.games=games;this.coupons=coupons;
        this.recoverySessions=recoverySessions;this.chatTurns=chatTurns;this.stores=stores;
        this.entityManager=entityManager;this.clock=clock;
    }

    @Transactional(readOnly=true) Map<String,Object> snapshot(){
        LocalDate today=LocalDate.now(clock);
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("date",today.toString());
        out.put("throttled",throttled());
        out.put("counters",Map.of("rows",rateLimits.rows(),"maxRows",RateLimitService.MAX_ROWS));
        out.put("limits",gameLimits.limits());
        out.put("storage",storage());
        out.put("busiestStores",busiestStores(today));
        return out;
    }

    /** 최근 24시간 코드별 거절 수. 0도 함께 내려보내야 화면이 "조용함"과 "집계 없음"을 구분한다. */
    private Map<String,Integer> throttled(){
        Map<String,Integer> out=new LinkedHashMap<>();
        for(String code:REJECTION_CODES)out.put(code,rateLimits.rejectionsLast24h(code));
        return out;
    }

    /**
     * 저장량. Lightsail 2GB 한 대라 행 수와 포스터 크기가 실제 상한이다.
     * 포스터는 매장당 한 행이고 base64 문자열이라 문자 수가 그대로 크기의 근사치다.
     */
    private Map<String,Object> storage(){
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("gamePlays",games.count());
        out.put("coupons",coupons.count());
        out.put("recoverySessions",recoverySessions.count());
        out.put("aiChatTurns",chatTurns.count());
        out.put("stores",stores.count());
        // 네이티브로 둔다. contentBase64는 @Lob이라 HQL의 length()가 PostgreSQL large object
        // 함수(lo_get)를 부르고, 실제 컬럼은 text라 "function lo_get(text) does not exist"로 죽는다.
        // H2 PostgreSQL 모드는 이걸 잡지 못했고 실제 PostgreSQL에 붙여서야 드러났다.
        Object bytes=entityManager
            .createNativeQuery("select coalesce(sum(length(content_base64)),0) from store_posters")
            .getSingleResult();
        out.put("posterBase64Chars",((Number)bytes).longValue());
        return out;
    }

    /**
     * 오늘 가장 붐비는 매장과 일일 상한 대비 사용률.
     *
     * 한 매장이 상한에 다가가고 있으면 둘 중 하나다. 그 가게가 정말 잘 되고 있거나, 누가 그
     * 매장 토큰으로 밀어붙이고 있거나. 어느 쪽인지는 운영자가 판단하고, 숫자는 여기서 준다.
     */
    private List<Map<String,Object>> busiestStores(LocalDate today){
        int dailyLimit=gameLimits.limits().get("gamePerStorePerDay");
        List<Object[]> rows=entityManager.createQuery("""
                select g.store.id, g.store.name, count(g) from GamePlay g
                where g.playedDate = :today
                group by g.store.id, g.store.name
                order by count(g) desc
                """,Object[].class)
                .setParameter("today",today)
                .setMaxResults(BUSIEST_STORES)
                .getResultList();
        List<Map<String,Object>> out=new ArrayList<>();
        for(Object[] row:rows){
            long plays=((Number)row[2]).longValue();
            Map<String,Object> item=new LinkedHashMap<>();
            item.put("storeId",row[0]);
            item.put("name",row[1]);
            item.put("playsToday",plays);
            item.put("dailyLimit",dailyLimit);
            item.put("usedPercent",dailyLimit==0?0:Math.round(plays*1000.0/dailyLimit)/10.0);
            out.add(item);
        }
        return out;
    }
}

/**
 * 운영자 현황판. 가입·게임·쿠폰의 오늘 값과 누적, 최근 14일 흐름, 매장별 숫자를 준다.
 *
 * 자원 현황(`OperatorMonitoringService`)과 나눈 이유는 보는 때가 달라서다. 이쪽은 매일 여는 화면이고
 * 저쪽은 무언가 이상할 때 여는 화면이다. 여기도 집계와 매장 라벨뿐이고 고객 개인정보는 없다.
 */
@Service class OperatorOverviewService {
    static final int TREND_DAYS=14;
    private static final int RECENT_SIGNUPS=10;
    private static final int TOP_STORES=10;

    private final EntityManager em;private final StoreRepository stores;private final GameRepository games;
    private final CouponRepository coupons;private final StoreApprovalService approvals;
    private final StoreSubscriptionRepository subscriptions;private final ServiceAccessPolicy policy;private final Clock clock;
    OperatorOverviewService(EntityManager em,StoreRepository stores,GameRepository games,CouponRepository coupons,
        StoreApprovalService approvals,StoreSubscriptionRepository subscriptions,ServiceAccessPolicy policy,Clock clock){
        this.em=em;this.stores=stores;this.games=games;this.coupons=coupons;this.approvals=approvals;
        this.subscriptions=subscriptions;this.policy=policy;this.clock=clock;
    }

    @Transactional(readOnly=true) Map<String,Object> overview(){
        LocalDate today=LocalDate.now(clock);ZoneId zone=clock.getZone();
        Instant startOfToday=today.atStartOfDay(zone).toInstant();
        LocalDate from=today.minusDays(TREND_DAYS-1);

        Map<String,Object> out=new LinkedHashMap<>();
        out.put("date",today.toString());
        out.put("today",Map.of(
            "signups",since("select count(s) from Store s where s.createdAt >= :t",startOfToday),
            "games",em.createQuery("select count(g) from GamePlay g where g.playedDate = :d",Long.class)
                .setParameter("d",today).getSingleResult(),
            "couponsIssued",since("select count(c) from Coupon c where c.issuedAt >= :t",startOfToday),
            "couponsRedeemed",since("select count(c) from Coupon c where c.redeemedAt >= :t",startOfToday)));
        out.put("totals",Map.of(
            "stores",stores.count(),
            "activeStores",stores.countByStatus(StoreStatus.ACTIVE),
            "pendingStores",stores.countByStatus(StoreStatus.PENDING_APPROVAL),
            "games",games.count(),
            "couponsIssued",coupons.count(),
            "couponsRedeemed",em.createQuery("select count(c) from Coupon c where c.status = :s",Long.class)
                .setParameter("s",CouponStatus.REDEEMED).getSingleResult()));
        out.put("trend",trend(from,today,from.atStartOfDay(zone).toInstant(),zone));
        out.put("recentSignups",recentSignups());
        out.put("topStoresToday",topStoresToday(today,startOfToday));
        return out;
    }

    /**
     * 매장 목록 한 페이지의 숫자. 매장마다 쿼리를 돌리지 않고 id 묶음으로 한 번씩 센다.
     * 이용 상태는 저장값이 아니라 `ServiceAccessPolicy`의 계산이다(결제 규칙과 같은 정본).
     */
    @Transactional(readOnly=true) Map<Long,Map<String,Object>> storeStats(Collection<Long> ids){
        Map<Long,Map<String,Object>> out=new HashMap<>();
        if(ids.isEmpty())return out;
        LocalDate today=LocalDate.now(clock);
        Map<Long,Long> gamesTotal=grouped("select g.store.id, count(g) from GamePlay g where g.store.id in :ids group by g.store.id",ids,null);
        Map<Long,Long> gamesToday=grouped("select g.store.id, count(g) from GamePlay g where g.store.id in :ids and g.playedDate = :d group by g.store.id",ids,today);
        Map<Long,Long> issued=grouped("select c.store.id, count(c) from Coupon c where c.store.id in :ids group by c.store.id",ids,null);
        Map<Long,Long> redeemed=grouped("select c.store.id, count(c) from Coupon c where c.store.id in :ids and c.status = :d group by c.store.id",ids,CouponStatus.REDEEMED);
        Map<Long,Object> lastPlayed=new HashMap<>();
        for(Object[] r:em.createQuery("select g.store.id, max(g.playedAt) from GamePlay g where g.store.id in :ids group by g.store.id",Object[].class)
                .setParameter("ids",ids).getResultList())lastPlayed.put((Long)r[0],r[1]);
        for(Long id:ids){
            Map<String,Object> m=new LinkedHashMap<>();
            m.put("gamesToday",gamesToday.getOrDefault(id,0L));
            m.put("gamesTotal",gamesTotal.getOrDefault(id,0L));
            m.put("couponsIssued",issued.getOrDefault(id,0L));
            m.put("couponsRedeemed",redeemed.getOrDefault(id,0L));
            m.put("lastPlayedAt",lastPlayed.get(id));
            subscriptions.findByStoreId(id).ifPresent(s->{m.put("plan",SubscriptionService.effective(s,clock.instant()).name());m.put("serviceState",policy.state(s).name());});
            out.put(id,m);
        }
        return out;
    }

    private List<Map<String,Object>> trend(LocalDate from,LocalDate today,Instant start,ZoneId zone){
        Map<LocalDate,long[]> days=new LinkedHashMap<>();
        for(LocalDate d=from;!d.isAfter(today);d=d.plusDays(1))days.put(d,new long[3]);
        for(Object[] r:em.createQuery("select g.playedDate, count(g) from GamePlay g where g.playedDate >= :f group by g.playedDate",Object[].class)
                .setParameter("f",from).getResultList()){long[] v=days.get((LocalDate)r[0]);if(v!=null)v[0]=((Number)r[1]).longValue();}
        // 날짜 함수는 H2와 PostgreSQL이 달라서 시각만 받아 여기서 KST 날짜로 나눈다. 14일치라 양이 작다.
        bucket("select c.redeemedAt from Coupon c where c.redeemedAt >= :t",start,zone,days,1);
        bucket("select s.createdAt from Store s where s.createdAt >= :t",start,zone,days,2);
        List<Map<String,Object>> out=new ArrayList<>();
        days.forEach((d,v)->out.add(Map.of("date",d.toString(),"games",v[0],"couponsRedeemed",v[1],"signups",v[2])));
        return out;
    }

    private List<Map<String,Object>> recentSignups(){
        List<Map<String,Object>> out=new ArrayList<>();
        for(Store s:stores.findAll(PageRequest.of(0,RECENT_SIGNUPS,Sort.by(Sort.Direction.DESC,"createdAt")))){
            Map<String,Object> m=new LinkedHashMap<>();
            m.put("id",s.id);m.put("name",s.name);m.put("status",s.status.name());m.put("createdAt",s.createdAt);
            m.put("businessVerified",s.businessVerifiedAt!=null);
            m.putAll(approvals.owner(s.id));
            out.add(m);
        }
        return out;
    }

    private List<Map<String,Object>> topStoresToday(LocalDate today,Instant startOfToday){
        List<Object[]> rows=em.createQuery("""
                select g.store.id, g.store.name, count(g) from GamePlay g where g.playedDate = :d
                group by g.store.id, g.store.name order by count(g) desc""",Object[].class)
            .setParameter("d",today).setMaxResults(TOP_STORES).getResultList();
        List<Long> ids=rows.stream().map(r->(Long)r[0]).toList();
        Map<Long,Long> redeemed=ids.isEmpty()?Map.of():grouped(
            "select c.store.id, count(c) from Coupon c where c.store.id in :ids and c.redeemedAt >= :d group by c.store.id",ids,startOfToday);
        List<Map<String,Object>> out=new ArrayList<>();
        for(Object[] r:rows)out.add(Map.of("storeId",r[0],"name",r[1],"games",((Number)r[2]).longValue(),
            "couponsRedeemed",redeemed.getOrDefault((Long)r[0],0L)));
        return out;
    }

    private long since(String jpql,Instant t){
        return em.createQuery(jpql,Long.class).setParameter("t",t).getSingleResult();
    }

    private Map<Long,Long> grouped(String jpql,Collection<Long> ids,Object d){
        var q=em.createQuery(jpql,Object[].class).setParameter("ids",ids);
        if(d!=null)q.setParameter("d",d);
        Map<Long,Long> out=new HashMap<>();
        for(Object[] r:q.getResultList())out.put((Long)r[0],((Number)r[1]).longValue());
        return out;
    }

    private void bucket(String jpql,Instant since,ZoneId zone,Map<LocalDate,long[]> days,int slot){
        for(Instant at:em.createQuery(jpql,Instant.class).setParameter("t",since).getResultList()){
            long[] v=days.get(at.atZone(zone).toLocalDate());if(v!=null)v[slot]++;
        }
    }
}
