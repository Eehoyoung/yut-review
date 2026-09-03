package com.yutreview;

import java.io.StringWriter;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 요금제로 갈리는 분석.
 *
 * BASIC은 오늘/전체 참여 수와 쿠폰 수 같은 한 줄 요약만 본다. STANDARD 이상은 시간대·요일·상품별·
 * 재참여까지 본다. 이 구분이 요금제가 실제로 파는 것이라 여기서만 갈린다.
 *
 * 조회 구간은 {@link AiContextService#window}가 요금제 보관기간으로 잘라 준다. AI가 보는 숫자와
 * 화면이 보는 숫자를 같은 함수에서 만들어야, 사장이 "리포트에는 이렇게 나오는데 통계는 다르다"는
 * 상황을 겪지 않는다.
 */
@Service
class AnalyticsService {
    private final AiContextService context;
    private final PlanEntitlementService entitlements;
    private final SubscriptionService subscriptions;
    private final GameRepository games;
    private final CouponRepository coupons;
    private final Clock clock;

    AnalyticsService(AiContextService context, PlanEntitlementService entitlements, SubscriptionService subscriptions,
                     GameRepository games, CouponRepository coupons, Clock clock) {
        this.context = context;
        this.entitlements = entitlements;
        this.subscriptions = subscriptions;
        this.games = games;
        this.coupons = coupons;
        this.clock = clock;
    }

    /** 모든 요금제가 보는 한 줄 요약. 기존 응답 모양을 그대로 유지한다. */
    Map<String, Object> summary(Long storeId) {
        Map<String, Long> results = new LinkedHashMap<>();
        for (YutResult result : YutResult.values())
            results.put(result.name(), games.countByStoreIdAndYutResult(storeId, result));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("todayPlays", games.countByStoreIdAndPlayedDate(storeId, LocalDate.now(clock)));
        out.put("totalPlays", games.countByStoreId(storeId));
        out.put("issuedCoupons", coupons.countByStoreIdAndStatus(storeId, CouponStatus.ISSUED));
        out.put("redeemedCoupons", coupons.countByStoreIdAndStatus(storeId, CouponStatus.REDEEMED));
        out.put("results", results);
        out.put("plan", subscriptions.planOf(storeId).name());
        out.put("advancedAvailable", entitlements.has(subscriptions.planOf(storeId), Entitlement.ADVANCED_ANALYTICS));
        return out;
    }

    /** STANDARD 이상. 시간대·요일·상품별·재참여까지. */
    Map<String, Object> detailed(Long storeId, LocalDate from, LocalDate to) {
        Plan plan = subscriptions.planOf(storeId);
        entitlements.require(plan, Entitlement.ADVANCED_ANALYTICS);
        AiContextService.Window w = context.window(plan, from, to);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("window", Map.of("from", w.from().toString(), "to", w.to().toString(),
                "clampedByPlanRetention", w.clampedByPlan()));
        out.put("summary", context.periodSummary(storeId, w));
        out.put("comparedToPrevious", context.comparePeriods(storeId, w, context.previousWindow(plan, w)));
        out.put("hourly", context.hourlyDistribution(storeId, w));
        out.put("weekday", context.weekdayDistribution(storeId, w));
        out.put("resultDistribution", context.resultDistribution(storeId, w));
        out.put("prizePerformance", context.prizePerformance(storeId, w));
        out.put("repeat", context.repeatMetrics(storeId, w));
        return out;
    }

    /**
     * STANDARD 이상. 일자별 집계 CSV.
     *
     * 고객 개인정보는 한 칸도 넣지 않는다. 참여 내역 원본을 내려주는 기능이 아니라 집계를 옮기는
     * 기능이다. 사장이 엑셀에서 보려는 것은 "며칠에 몇 명이 왔고 쿠폰이 얼마나 쓰였나"이지 손님
     * 명단이 아니다.
     */
    String dailyCsv(Long storeId, LocalDate from, LocalDate to) {
        Plan plan = subscriptions.planOf(storeId);
        entitlements.require(plan, Entitlement.CSV_EXPORT);
        AiContextService.Window w = context.window(plan, from, to);

        StringWriter out = new StringWriter();
        // 엑셀이 UTF-8을 알아보게 BOM을 붙인다. 없으면 한글 머리글이 깨진 채로 열린다.
        out.write('﻿');
        out.write("날짜,참여수,쿠폰발급,쿠폰사용\n");
        List<LocalDate> days = w.from().datesUntil(w.to().plusDays(1)).toList();
        for (LocalDate day : days) {
            AiContextService.Window oneDay = new AiContextService.Window(day, day, w.clampedByPlan());
            Map<String, Object> summary = context.periodSummary(storeId, oneDay);
            out.write(day + "," + summary.get("plays") + "," + summary.get("couponsIssued") + ","
                    + summary.get("couponsRedeemed") + "\n");
        }
        return out.toString();
    }

    /** 상품별 집계 CSV. 상품명은 매장이 고객에게 공개하는 값이라 그대로 쓴다. */
    String prizeCsv(Long storeId, LocalDate from, LocalDate to) {
        Plan plan = subscriptions.planOf(storeId);
        entitlements.require(plan, Entitlement.CSV_EXPORT);
        AiContextService.Window w = context.window(plan, from, to);

        StringWriter out = new StringWriter();
        out.write('﻿');
        out.write("등급,상품명,쿠폰발급,쿠폰사용,사용률(%)\n");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) context.prizePerformance(storeId, w).get("prizes");
        for (Map<String, Object> row : rows)
            out.write(row.get("prizeRank") + "," + csv(String.valueOf(row.get("prizeName"))) + ","
                    + row.get("issued") + "," + row.get("redeemed") + "," + row.get("redemptionRatePercent") + "\n");
        return out.toString();
    }

    /** 상품명에 쉼표나 따옴표가 들어가도 열이 밀리지 않게 한다. */
    private static String csv(String value) {
        if (value.indexOf(',') < 0 && value.indexOf('"') < 0 && value.indexOf('\n') < 0) return value;
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    /** 파일명에 쓸 수 있는 매장명. 경로 구분자와 제어문자를 걷어낸다. */
    static String safeFileName(String storeName, String suffix, LocalDate from, LocalDate to) {
        String base = storeName == null ? "store" : storeName.replaceAll("[\\r\\n\\\\/:*?\"<>|]", "_");
        return base + "_" + suffix + "_" + from + "_" + to + ".csv";
    }

    /** 화면이 무엇을 열어 줄지 판단할 근거. 화면이 요금제 표를 다시 해석하지 않게 한다. */
    List<String> availableExports(Long storeId) {
        Plan plan = subscriptions.planOf(storeId);
        List<String> out = new ArrayList<>();
        if (entitlements.has(plan, Entitlement.CSV_EXPORT)) {
            out.add("daily");
            out.add("prize");
        }
        return out;
    }
}
