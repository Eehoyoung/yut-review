package com.yutreview;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 월 사용 한도.
 *
 * 한도 검사와 차감을 한 문장으로 처리하는 게 핵심이다. 읽어서 비교한 뒤 저장하면 동시에 들어온 두
 * 요청이 같은 값을 읽고 둘 다 통과해 한도를 넘긴다. 그래서 차감은 조건부 UPDATE 한 방이고, 바뀐
 * 행이 0이면 한도에 닿은 것으로 본다.
 *
 * 차감은 모델을 부르기 전에 한다. 부른 뒤에 차감하면 응답을 받고 기록에 실패하는 순간 공짜 호출이
 * 생긴다. 대신 호출이 실패하면 되돌린다.
 */
@Service
class AiQuotaService {
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");

    private final AiMonthlyQuotaRepository quotas;
    private final AiQuotaRowFactory rowFactory;
    private final PlanEntitlementService entitlements;
    private final Clock clock;

    AiQuotaService(AiMonthlyQuotaRepository quotas, AiQuotaRowFactory rowFactory,
                   PlanEntitlementService entitlements, Clock clock) {
        this.quotas = quotas;
        this.rowFactory = rowFactory;
        this.entitlements = entitlements;
        this.clock = clock;
    }

    String currentMonth() {
        return LocalDate.now(clock).format(MONTH);
    }

    record Usage(AiFeature feature, int used, int limitPerMonth) {
        int remaining() {
            return Math.max(0, limitPerMonth - used);
        }
    }

    /** 이번 달 현황. 요금제에 없는 기능은 한도 0으로 보여 화면이 왜 못 쓰는지 설명할 수 있게 한다. */
    List<Usage> usage(Long storeId, Plan plan) {
        String month = currentMonth();
        Map<AiFeature, AiMonthlyQuota> rows = new LinkedHashMap<>();
        for (AiMonthlyQuota q : quotas.findByStoreIdAndQuotaMonth(storeId, month)) rows.put(q.feature, q);
        List<Usage> out = new ArrayList<>();
        for (AiFeature feature : AiFeature.values()) {
            int limit = entitlements.monthlyQuota(plan, feature);
            AiMonthlyQuota row = rows.get(feature);
            out.add(new Usage(feature, row == null ? 0 : row.used, limit));
        }
        return out;
    }

    /**
     * 한 번 쓸 자리를 확보한다. 실패하면 호출 자체를 하지 않는다.
     *
     * 새 트랜잭션으로 분리한 이유는, 뒤이어 모델 호출이 실패해 바깥 트랜잭션이 롤백되더라도 차감과
     * 되돌리기를 우리가 명시적으로 통제하려는 것이다.
     */
    /** 차감한 달을 돌려준다. 되돌릴 때 그 달을 그대로 써야 월말 경계에서 어긋나지 않는다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    String consume(Store store, Plan plan, AiFeature feature) {
        int limit = entitlements.monthlyQuota(plan, feature);
        if (limit <= 0)
            throw new AppException("PLAN_UPGRADE_REQUIRED", "현재 요금제에서는 사용할 수 없는 AI 기능입니다.",
                    HttpStatus.PAYMENT_REQUIRED);
        AiMonthlyQuota row = row(store, feature, limit);
        // 등급이 바뀌어 한도가 늘었다면 이번 달 행도 따라 올린다. 내려가는 경우도 같게 둔다.
        if (row.limitPerMonth != limit) quotas.relimit(row.id, limit, clock.instant());
        if (quotas.consume(row.id, clock.instant()) == 0)
            throw new AppException("AI_QUOTA_EXCEEDED",
                    "이번 달 AI 사용 한도를 모두 썼습니다. 다음 달에 다시 사용할 수 있습니다.",
                    HttpStatus.TOO_MANY_REQUESTS);
        return row.quotaMonth;
    }

    /**
     * 모델 호출이 실패했을 때 되돌린다.
     *
     * 조건부 UPDATE로 내리는 이유는 차감과 같다. 엔티티를 읽어 -1 하고 저장하면 그 사이 다른 요청이
     * 올린 값을 덮어써서, 원자적으로 만들어 둔 차감이 통째로 무의미해진다.
     *
     * 되돌릴 달을 인자로 받는 이유는 월말 때문이다. 23:59:58에 차감하고 00:00:03에 실패하면
     * currentMonth()는 이미 다음 달이라, 지난달은 깎인 채로 남고 다음 달이 공짜로 한 칸 늘어난다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void refund(Long storeId, AiFeature feature, String chargedMonth) {
        quotas.refund(storeId, feature, chargedMonth, clock.instant());
    }

    /**
     * 두 요청이 같은 달의 첫 사용을 동시에 시작하면 둘 다 행을 만들려 한다. 유니크 제약이 한쪽을
     * 막는데, 그 실패는 별도 트랜잭션 안에서 끝나야 한다. 같은 세션에서 제약 위반을 잡고 계속 쓰면
     * Hibernate가 오염된 영속성 컨텍스트로 넘어가 "null id ... don't flush after an exception"으로
     * 죽는다. 그래서 삽입만 새 트랜잭션에 맡기고, 실패하면 여기서 깨끗하게 다시 읽는다.
     */
    private AiMonthlyQuota row(Store store, AiFeature feature, int limit) {
        String month = currentMonth();
        return quotas.findByStoreIdAndFeatureAndQuotaMonth(store.id, feature, month)
                .orElseGet(() -> {
                    try {
                        return rowFactory.insert(store, feature, month, limit);
                    } catch (DataIntegrityViolationException duplicate) {
                        return quotas.findByStoreIdAndFeatureAndQuotaMonth(store.id, feature, month)
                                .orElseThrow(() -> duplicate);
                    }
                });
    }
}

/** 쿼터 행 삽입만 담당한다. 별도 빈이어야 프록시를 타고 진짜로 새 트랜잭션이 열린다. */
@Service
class AiQuotaRowFactory {
    private final AiMonthlyQuotaRepository quotas;
    private final Clock clock;

    AiQuotaRowFactory(AiMonthlyQuotaRepository quotas, Clock clock) {
        this.quotas = quotas;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    AiMonthlyQuota insert(Store store, AiFeature feature, String month, int limit) {
        AiMonthlyQuota q = new AiMonthlyQuota();
        q.store = store;
        q.feature = feature;
        q.quotaMonth = month;
        q.used = 0;
        q.limitPerMonth = limit;
        q.updatedAt = clock.instant();
        return quotas.saveAndFlush(q);
    }
}

/**
 * 호출 기록. 프롬프트 원문과 고객 개인정보는 남기지 않는다. 남는 것은 어떤 기능이 어떤 모델·프롬프트
 * 버전으로 토큰을 얼마 썼는지, 성공했는지뿐이다. 비용을 설명할 수 있는 최소한이다.
 */
@Service
class AiUsageService {
    private final AiUsageEventRepository events;
    private final Clock clock;
    /** 공급자 가격은 코드에 박지 않는다. 값이 바뀌면 설정만 고친다. 0이면 비용을 계산하지 않는다. */
    private final double inputPerMillion;
    private final double outputPerMillion;

    AiUsageService(AiUsageEventRepository events, Clock clock,
                   @org.springframework.beans.factory.annotation.Value("${app.ai.pricing.input-per-million:0}") double inputPerMillion,
                   @org.springframework.beans.factory.annotation.Value("${app.ai.pricing.output-per-million:0}") double outputPerMillion) {
        this.events = events;
        this.clock = clock;
        this.inputPerMillion = inputPerMillion;
        this.outputPerMillion = outputPerMillion;
    }

    /**
     * 이번 달 누적 토큰과 추정 비용.
     *
     * 단가가 설정돼 있지 않으면 비용을 만들어 내지 않는다. 0원이라고 말하는 것과 모른다고 말하는 것은
     * 다르고, 여기서 짐작한 숫자가 정산 근거처럼 읽히면 곤란하다.
     */
    Map<String, Object> monthlyCost(Long storeId, String month) {
        java.time.YearMonth ym = java.time.YearMonth.parse(month);
        java.time.ZoneId zone = clock.getZone();
        java.time.Instant from = ym.atDay(1).atStartOfDay(zone).toInstant();
        java.time.Instant to = ym.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant();
        long input = 0, output = 0;
        for (AiUsageEvent e : events.findByStoreIdAndCreatedAtBetween(storeId, from, to)) {
            input += e.inputTokens;
            output += e.outputTokens;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("inputTokens", input);
        out.put("outputTokens", output);
        boolean priced = inputPerMillion > 0 || outputPerMillion > 0;
        out.put("estimatedCostUsd", priced
                ? Math.round((input * inputPerMillion + output * outputPerMillion) / 1_000_000 * 10000) / 10000.0
                : null);
        out.put("pricingConfigured", priced);
        return out;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void record(Store store, AiFeature feature, String model, String promptVersion, LlmUsage usage,
                boolean succeeded, String failureCode) {
        AiUsageEvent e = new AiUsageEvent();
        e.store = store;
        e.feature = feature;
        e.model = model == null || model.isBlank() ? "unknown" : model;
        e.promptVersion = promptVersion;
        e.inputTokens = usage == null ? 0 : usage.inputTokens();
        e.outputTokens = usage == null ? 0 : usage.outputTokens();
        e.succeeded = succeeded;
        e.failureCode = failureCode;
        e.createdAt = clock.instant();
        events.save(e);
    }

    List<Map<String, Object>> recent(Long storeId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (AiUsageEvent e : events.findTop20ByStoreIdOrderByCreatedAtDesc(storeId)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("feature", e.feature.name());
            item.put("model", e.model);
            item.put("promptVersion", e.promptVersion);
            item.put("inputTokens", e.inputTokens);
            item.put("outputTokens", e.outputTokens);
            item.put("succeeded", e.succeeded);
            item.put("failureCode", e.failureCode == null ? "" : e.failureCode);
            item.put("createdAt", e.createdAt);
            out.add(item);
        }
        return out;
    }
}
