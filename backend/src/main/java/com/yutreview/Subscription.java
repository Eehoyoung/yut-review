package com.yutreview;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 요금제가 무엇을 열어 주는지 한 곳에서만 정한다. 화면과 API가 각자 판단하면 두 판단이 갈라진다.
 *
 * 게임 관련 기능(윷 던지기, QR 이벤트, 상품, 쿠폰, 직원 PIN, 2일 쿨타임)은 어떤 등급에서도 잠기지
 * 않는다. 그래서 이 서비스에는 그 기능들에 대한 물음 자체가 없다. 등급으로 잠기는 것은 분석 깊이,
 * CSV 내보내기, 브랜딩, 그리고 AI 기능뿐이다.
 */
@Service
class PlanEntitlementService {
    private static final Map<Plan, Set<Entitlement>> ENTITLEMENTS = new EnumMap<>(Map.of(
            Plan.BASIC, EnumSet.of(Entitlement.BASIC_ANALYTICS),
            Plan.STANDARD, EnumSet.of(Entitlement.BASIC_ANALYTICS, Entitlement.ADVANCED_ANALYTICS,
                    Entitlement.CSV_EXPORT, Entitlement.BRANDING),
            Plan.PRO, EnumSet.allOf(Entitlement.class)));

    private static final Map<Plan, Set<AiFeature>> AI_FEATURES = new EnumMap<>(Map.of(
            Plan.BASIC, EnumSet.noneOf(AiFeature.class),
            Plan.STANDARD, EnumSet.of(AiFeature.AI_EVENT_COPY, AiFeature.AI_REPORT),
            Plan.PRO, EnumSet.allOf(AiFeature.class)));

    /** 등급별 월 기본 한도. 없는 기능은 0이며, 0은 "열려 있지 않다"가 아니라 "쓸 수 없다"로만 쓴다. */
    private static final Map<Plan, Map<AiFeature, Integer>> QUOTAS = new EnumMap<>(Map.of(
            Plan.BASIC, Map.of(),
            Plan.STANDARD, Map.of(AiFeature.AI_EVENT_COPY, 20, AiFeature.AI_REPORT, 5),
            Plan.PRO, Map.of(AiFeature.AI_EVENT_COPY, 100, AiFeature.AI_REPORT, 20,
                    AiFeature.AI_IMPROVEMENT, 30, AiFeature.AI_CHAT, 100)));

    boolean has(Plan plan, Entitlement entitlement) {
        return ENTITLEMENTS.get(plan).contains(entitlement);
    }

    boolean has(Plan plan, AiFeature feature) {
        return AI_FEATURES.get(plan).contains(feature);
    }

    Set<AiFeature> aiFeatures(Plan plan) {
        return AI_FEATURES.get(plan);
    }

    Set<Entitlement> entitlements(Plan plan) {
        return ENTITLEMENTS.get(plan);
    }

    int monthlyQuota(Plan plan, AiFeature feature) {
        return QUOTAS.get(plan).getOrDefault(feature, 0);
    }

    /** PRO는 자동 주간 리포트를 받는다. */
    boolean automaticWeeklyReport(Plan plan) {
        return plan == Plan.PRO;
    }

    /**
     * 분석 집계를 거슬러 볼 수 있는 가장 이른 날짜. 상한이 없는 등급은 비어 있다.
     * 고객 개인정보 보존(120일, {@link PrivacyCleanupService})과는 별개의 축이다. 이건 이미 비식별인
     * 집계를 어디까지 보여줄지의 문제다.
     */
    Optional<LocalDate> analyticsFloor(Plan plan, LocalDate today) {
        int days = plan.analyticsRetentionDays;
        return days <= 0 ? Optional.empty() : Optional.of(today.minusDays(days));
    }

    void require(Plan plan, Entitlement entitlement) {
        if (!has(plan, entitlement))
            throw new AppException("PLAN_UPGRADE_REQUIRED",
                    "현재 요금제에서는 사용할 수 없는 기능입니다.", HttpStatus.PAYMENT_REQUIRED);
    }

    void require(Plan plan, AiFeature feature) {
        if (!has(plan, feature))
            throw new AppException("PLAN_UPGRADE_REQUIRED",
                    "현재 요금제에서는 사용할 수 없는 AI 기능입니다.", HttpStatus.PAYMENT_REQUIRED);
    }
}

/**
 * 매장의 현재 요금제를 읽고 바꾼다. 결제(PG) 연동은 이번 범위가 아니라, 등급 변경은 관리자 조작으로만
 * 일어난다. 결제를 붙일 때 이 서비스의 {@link #changePlan} 안쪽만 결제 결과에 연결하면 된다.
 */
@Service
class SubscriptionService {
    private final StoreSubscriptionRepository subscriptions;
    private final Clock clock;

    SubscriptionService(StoreSubscriptionRepository subscriptions, Clock clock) {
        this.subscriptions = subscriptions;
        this.clock = clock;
    }

    /**
     * 구독 행이 없는 매장은 BASIC으로 본다. 기존 매장을 일괄 백필하지 않아도 되고, 행이 사라져도
     * 매장이 잠기지 않는다. 요금제는 기능을 열어 주는 값이므로 없을 때의 기본은 가장 낮은 등급이다.
     */
    Plan planOf(Long storeId) {
        return subscriptions.findByStoreId(storeId)
                .filter(s -> s.status == SubscriptionStatus.ACTIVE)
                .map(s -> s.plan)
                .orElse(Plan.BASIC);
    }

    Optional<StoreSubscription> find(Long storeId) {
        return subscriptions.findByStoreId(storeId);
    }

    @Transactional
    StoreSubscription start(Store store, Plan plan) {
        Instant now = clock.instant();
        StoreSubscription s = subscriptions.findByStoreId(store.id).orElseGet(StoreSubscription::new);
        if (s.id == null) {
            s.store = store;
            s.startedAt = now;
        }
        s.plan = plan;
        s.status = SubscriptionStatus.ACTIVE;
        s.updatedAt = now;
        return subscriptions.save(s);
    }

    @Transactional
    StoreSubscription changePlan(Store store, Plan plan, String note) {
        StoreSubscription s = start(store, plan);
        s.note = note == null || note.isBlank() ? null : note.trim();
        return s;
    }
}
