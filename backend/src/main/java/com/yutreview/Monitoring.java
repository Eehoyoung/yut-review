package com.yutreview;

import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.LocalDate;
import java.util.*;
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
        out.put("posterBase64Chars",entityManager
            .createQuery("select coalesce(sum(length(p.contentBase64)),0) from StorePoster p",Long.class)
            .getSingleResult());
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
