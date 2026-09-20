package com.yutreview;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * LLM에 들어갈 값을 만드는 단 하나의 자리.
 *
 * 이 서비스가 반환하는 것은 숫자와 공개 라벨(매장명, 공개 상품명)뿐이다. 고객 이름, 전화번호,
 * phoneHash, phoneLast4, 쿠폰 토큰은 어떤 경로로도 여기를 통과하지 못한다. 엔티티를 그대로
 * 직렬화하지 않고 집계 쿼리 결과만 Map으로 옮기는 이유가 그것이다. 새 필드를 추가할 때도
 * "이 값이 한 사람을 가리킬 수 있는가"를 먼저 물어야 한다.
 *
 * 조회 구간은 요금제의 분석 보관기간으로 잘린다. 개인정보 보존(120일)과는 다른 축이며, 이미
 * 비식별인 집계를 얼마나 거슬러 보여줄지의 문제다.
 */
@Service
class AiContextService {
    /** 한 번에 볼 수 있는 최대 구간. 모델에 넣는 토큰과 쿼리 비용을 같이 묶어 둔다. */
    static final int MAX_WINDOW_DAYS = 400;

    private static final String[] WEEKDAYS = {"일", "월", "화", "수", "목", "금", "토"};

    private final AnalyticsRepository analytics;
    private final PrizeRepository prizes;
    private final StoreOutcomeRepository outcomes;
    private final PlanEntitlementService entitlements;
    private final Clock clock;

    AiContextService(AnalyticsRepository analytics, PrizeRepository prizes, StoreOutcomeRepository outcomes,
                     PlanEntitlementService entitlements, Clock clock) {
        this.analytics = analytics;
        this.prizes = prizes;
        this.outcomes = outcomes;
        this.entitlements = entitlements;
        this.clock = clock;
    }

    /** 요금제로 잘린 실제 조회 구간. 요청한 시작일이 보관기간보다 이르면 보관기간 쪽으로 당겨진다. */
    record Window(LocalDate from, LocalDate to, boolean clampedByPlan) {
    }

    Window window(Plan plan, LocalDate from, LocalDate to) {
        LocalDate today = LocalDate.now(clock);
        LocalDate end = to == null || to.isAfter(today) ? today : to;
        LocalDate start = from == null ? end.minusDays(29) : from;
        if (start.isAfter(end))
            throw new AppException("INVALID_REQUEST", "시작일이 종료일보다 늦습니다.");
        if (start.isBefore(end.minusDays(MAX_WINDOW_DAYS - 1L)))
            start = end.minusDays(MAX_WINDOW_DAYS - 1L);
        Optional<LocalDate> floor = entitlements.analyticsFloor(plan, today);
        if (floor.isPresent() && start.isBefore(floor.get())) {
            // 요청 구간 전체가 보관기간 밖이면 잘라 봐야 from > to가 된다. 그대로 두면 어떤 행도
            // 걸리지 않아 "참여 0건"으로 보이고, 모델은 이벤트가 죽었다고 분석한다. 조용히 거짓을
            // 만드는 대신 왜 볼 수 없는지 말한다.
            if (floor.get().isAfter(end))
                throw new AppException("ANALYTICS_OUT_OF_RETENTION",
                        "현재 요금제에서 볼 수 있는 기간(" + floor.get() + " 이후)을 벗어난 조회입니다.");
            return new Window(floor.get(), end, true);
        }
        return new Window(start, end, false);
    }

    /** 기간 요약. AI 리포트와 채팅 도구가 같은 값을 보게 하려고 한 곳에서만 만든다. */
    Map<String, Object> periodSummary(Long storeId, Window w) {
        long plays = analytics.countPlays(storeId, w.from(), w.to());
        Map<String, Long> couponStatus = counts(analytics.couponStatusCounts(storeId, w.from(), w.to()));
        long issued = couponStatus.values().stream().mapToLong(Long::longValue).sum();
        long redeemed = couponStatus.getOrDefault(CouponStatus.REDEEMED.name(), 0L);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", w.from().toString());
        out.put("to", w.to().toString());
        out.put("days", w.from().datesUntil(w.to().plusDays(1)).count());
        out.put("plays", plays);
        out.put("couponsIssued", issued);
        out.put("couponsRedeemed", redeemed);
        out.put("redemptionRatePercent", percent(redeemed, issued));
        out.put("couponsByStatus", couponStatus);
        return out;
    }

    /**
     * 직전 구간과의 비교. 직전 구간이 보관기간 밖이면 비교하지 않는다. 없는 기간을 0으로 채워 넣으면
     * 모델이 "지난 기간 대비 폭증"이라는 없는 사실을 만든다.
     */
    Map<String, Object> comparePeriods(Long storeId, Window current, Optional<Window> previous) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("current", periodSummary(storeId, current));
        if (previous.isEmpty()) {
            out.put("previous", null);
            out.put("note", "직전 같은 길이의 기간은 요금제 보관기간 밖이라 비교할 수 없다.");
            return out;
        }
        Window before = previous.get();
        out.put("previous", periodSummary(storeId, before));
        long nowPlays = analytics.countPlays(storeId, current.from(), current.to());
        long beforePlays = analytics.countPlays(storeId, before.from(), before.to());
        out.put("playsChange", nowPlays - beforePlays);
        out.put("playsChangePercent", beforePlays == 0 ? null : percent(nowPlays - beforePlays, beforePlays));
        return out;
    }

    /** 이전 같은 길이의 구간. 보관기간 밖이면 비어 있다. */
    Optional<Window> previousWindow(Plan plan, Window w) {
        long days = w.from().datesUntil(w.to().plusDays(1)).count();
        try {
            return Optional.of(window(plan, w.from().minusDays(days), w.from().minusDays(1)));
        } catch (AppException outOfRetention) {
            return Optional.empty();
        }
    }

    Map<String, Object> hourlyDistribution(Long storeId, Window w) {
        Map<String, Long> byHour = new LinkedHashMap<>();
        Map<String, Long> raw = counts(analytics.hourCounts(storeId, w.from(), w.to()));
        for (int hour = 0; hour < 24; hour++) {
            String key = String.format("%02d", hour);
            byHour.put(key, raw.getOrDefault(String.valueOf(hour), 0L));
        }
        return Map.of("from", w.from().toString(), "to", w.to().toString(), "playsByHour", byHour);
    }

    Map<String, Object> weekdayDistribution(Long storeId, Window w) {
        Map<String, Long> raw = counts(analytics.weekdayCounts(storeId, w.from(), w.to()));
        Map<String, Long> byDay = new LinkedHashMap<>();
        // HQL의 day of week는 1=일요일이라 배열 인덱스와 한 칸 어긋난다.
        for (int day = 0; day < 7; day++)
            byDay.put(WEEKDAYS[day], raw.getOrDefault(String.valueOf(day + 1), 0L));
        return Map.of("from", w.from().toString(), "to", w.to().toString(), "playsByWeekday", byDay);
    }

    Map<String, Object> resultDistribution(Long storeId, Window w) {
        Map<String, Long> raw = counts(analytics.resultCounts(storeId, w.from(), w.to()));
        Map<String, Long> byResult = new LinkedHashMap<>();
        for (YutResult result : YutResult.values())
            byResult.put(result.name(), raw.getOrDefault(result.name(), 0L));
        Map<String, Long> byRank = counts(analytics.rankCounts(storeId, w.from(), w.to()));
        return Map.of("from", w.from().toString(), "to", w.to().toString(),
                "playsByResult", byResult, "playsByPrizeRank", byRank);
    }

    /** 상품별 발급·사용. 상품명은 매장이 고객에게 공개하는 값이라 그대로 쓴다. */
    Map<String, Object> prizePerformance(Long storeId, Window w) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object[] row : analytics.prizePerformance(storeId, w.from(), w.to())) {
            long issued = ((Number) row[2]).longValue();
            long redeemed = row[3] == null ? 0 : ((Number) row[3]).longValue();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("prizeName", String.valueOf(row[0]));
            item.put("prizeRank", ((Number) row[1]).intValue());
            item.put("issued", issued);
            item.put("redeemed", redeemed);
            item.put("redemptionRatePercent", percent(redeemed, issued));
            rows.add(item);
        }
        return Map.of("from", w.from().toString(), "to", w.to().toString(), "prizes", rows);
    }

    Map<String, Object> repeatMetrics(Long storeId, Window w) {
        List<Object[]> rows = analytics.repeatMetrics(storeId, w.from(), w.to(),
                PrivacyCleanupService.ANONYMIZED_PHONE_HASH);
        long participants = 0;
        long repeaters = 0;
        if (!rows.isEmpty() && rows.get(0)[0] != null) {
            participants = ((Number) rows.get(0)[0]).longValue();
            repeaters = rows.get(0)[1] == null ? 0 : ((Number) rows.get(0)[1]).longValue();
        }
        return Map.of("from", w.from().toString(), "to", w.to().toString(),
                "uniqueParticipants", participants,
                "repeatParticipants", repeaters,
                "repeatRatePercent", percent(repeaters, participants));
    }

    /** 현재 상품·확률 설정. 공개 상품명과 확률만 담는다. */
    Map<String, Object> prizeConfig(Long storeId) {
        List<StoreOutcome> config = outcomes.findByStoreId(storeId);
        List<Map<String, Object>> ladder = new ArrayList<>();
        for (Prize p : prizes.findByStoreIdOrderByRank(storeId)) {
            if (!p.active) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("rank", p.rank);
            item.put("name", p.name);
            item.put("description", p.description == null ? "" : p.description);
            item.put("redeemPolicy", p.redeemPolicy.name());
            item.put("oddsPercent", GameConfigService.odds(config, o -> o.prizeRank == p.rank));
            ladder.add(item);
        }
        return Map.of("rankCount", GameConfigService.rankCount(config), "prizes", ladder);
    }

    /** 리포트·개선 제안이 한 번에 받는 묶음. */
    Map<String, Object> analyticsBundle(Store store, Plan plan, Window w) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("storeName", store.name);
        out.put("plan", plan.name());
        out.put("window", Map.of("from", w.from().toString(), "to", w.to().toString(),
                "clampedByPlanRetention", w.clampedByPlan()));
        out.put("summary", periodSummary(store.id, w));
        out.put("comparedToPrevious", comparePeriods(store.id, w, previousWindow(plan, w)));
        out.put("resultDistribution", resultDistribution(store.id, w));
        out.put("hourly", hourlyDistribution(store.id, w));
        out.put("weekday", weekdayDistribution(store.id, w));
        out.put("prizePerformance", prizePerformance(store.id, w));
        out.put("repeat", repeatMetrics(store.id, w));
        out.put("prizeConfig", prizeConfig(store.id));
        out.put("dataNote", "매출 데이터는 수집하지 않는다. 참여·쿠폰 집계만 있다.");
        return out;
    }

    /**
     * 관리자가 직접 쓴 문장을 모델에 넘기기 전에 거른다.
     *
     * 집계 경로는 설계상 개인정보가 지나갈 수 없지만, 자유 입력은 다르다. 사장이 "010-1234-5678
     * 손님한테 보낸 문구처럼"이라고 적으면 그대로 모델에 간다. 개인정보를 보내지 않는다는 약속은
     * 시스템 프롬프트가 아니라 코드가 지켜야 한다.
     *
     * 숫자를 지우지 않고 거부하는 이유는, 가려서 보내면 사장은 자기가 쓴 내용이 그대로 갔다고
     * 믿은 채 남게 되기 때문이다.
     */
    static String withoutPersonalData(String text, String field) {
        if (text == null || text.isBlank()) return "";
        String value = text.trim();
        String digits = value.replaceAll("[^0-9]", "");
        // 휴대전화·일반전화·사업자번호가 될 만한 숫자 덩어리. 구분자가 섞여 있어도 잡힌다.
        if (digits.length() >= 9)
            throw new AppException("PERSONAL_DATA_NOT_ALLOWED",
                    field + "에 전화번호처럼 보이는 숫자가 있습니다. AI에는 고객 개인정보를 보낼 수 없습니다.");
        if (value.matches(".*[\\w.+-]+@[\\w-]+\\.[\\w.]+.*"))
            throw new AppException("PERSONAL_DATA_NOT_ALLOWED",
                    field + "에 이메일 주소가 있습니다. AI에는 고객 개인정보를 보낼 수 없습니다.");
        return value;
    }

    private static Map<String, Long> counts(List<Object[]> rows) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (Object[] row : rows) {
            Object key = row[0];
            String label = key instanceof Number n ? String.valueOf(n.intValue()) : String.valueOf(key);
            out.put(label, ((Number) row[1]).longValue());
        }
        return out;
    }

    /** 소수 첫째 자리까지. 분모가 0이면 0으로 두고 비율을 만들어 내지 않는다. */
    private static double percent(long part, long total) {
        return total == 0 ? 0 : Math.round(part * 1000.0 / total) / 10.0;
    }
}
