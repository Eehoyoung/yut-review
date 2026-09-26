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
import org.springframework.scheduling.annotation.Scheduled;
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
                    Entitlement.CSV_EXPORT),
            Plan.PRO, EnumSet.allOf(Entitlement.class)));

    private static final Map<Plan, Set<AiFeature>> AI_FEATURES = new EnumMap<>(Map.of(
            Plan.BASIC, EnumSet.of(AiFeature.AI_REPORT),
            Plan.STANDARD, EnumSet.of(AiFeature.AI_REPORT),
            Plan.PRO, EnumSet.allOf(AiFeature.class)));

    /** 등급별 월 기본 한도. 없는 기능은 0이며, 0은 "열려 있지 않다"가 아니라 "쓸 수 없다"로만 쓴다. */
    private static final Map<Plan, Map<AiFeature, Integer>> QUOTAS = new EnumMap<>(Map.of(
            Plan.BASIC, Map.of(AiFeature.AI_REPORT, 5),
            Plan.STANDARD, Map.of(AiFeature.AI_REPORT, 5),
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
 * 매장의 현재 요금제를 읽고 바꾼다. 유료 전환은 {@link BillingService}(포트원 자동결제)가 하고,
 * 여기 {@link #changePlan}은 운영자 수동 조정용으로 남는다.
 */
@Service
class SubscriptionService {
    static final int SIGNUP_TRIAL_DAYS = 14;
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
    @Transactional
    Plan planOf(Long storeId) {
        Optional<StoreSubscription> found = subscriptions.findByStoreId(storeId);
        if (found.isEmpty() || found.get().status != SubscriptionStatus.ACTIVE) return Plan.BASIC;
        StoreSubscription subscription = found.get();
        if (subscription.trialEndsAt != null && !clock.instant().isBefore(subscription.trialEndsAt)) {
            subscription.plan = Plan.BASIC;
            subscription.trialEndsAt = null;
            subscription.note = "14일 PRO 무료체험 종료";
            subscription.updatedAt = clock.instant();
            subscriptions.save(subscription);
        }
        return subscription.plan;
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
        s.trialEndsAt = null;
        s.updatedAt = now;
        return subscriptions.save(s);
    }

    /** 가입일을 1일째로 세며, 15일째 00:01(Asia/Seoul)에 BASIC 전환 경계가 온다. */
    @Transactional
    StoreSubscription startSignupTrial(Store store) {
        Instant now = clock.instant();
        StoreSubscription s = subscriptions.findByStoreId(store.id).orElseGet(StoreSubscription::new);
        if (s.id == null) {
            s.store = store;
            s.startedAt = now;
        }
        s.plan = Plan.PRO;
        s.status = SubscriptionStatus.ACTIVE;
        LocalDate signupDate = now.atZone(clock.getZone()).toLocalDate();
        s.trialEndsAt = signupDate.plusDays(SIGNUP_TRIAL_DAYS).atTime(0, 1).atZone(clock.getZone()).toInstant();
        // 체험 종료일이 첫 결제예정일이다. 결제가 없으면 D+2까지 서비스하고 그 뒤 이용을 제한한다.
        s.nextBillingAt = s.trialEndsAt;
        s.updatedAt = now;
        s.note = "가입일 기준 14일 PRO 무료체험";
        return subscriptions.save(s);
    }

    /** 결제 대상에서 뺀다. 현장 테스트용 시드 매장이 16일 뒤 막히지 않게 한다. */
    @Transactional
    void exemptFromBilling(Long storeId) {
        subscriptions.findByStoreId(storeId).ifPresent(s -> s.nextBillingAt = null);
    }

    @Transactional
    StoreSubscription changePlan(Store store, Plan plan, String note) {
        StoreSubscription s = start(store, plan);
        s.note = note == null || note.isBlank() ? null : note.trim();
        return s;
    }

    /**
     * 등급 변경은 운영자만 한다.
     *
     * 결제 연동이 없는 상태에서 매장 멤버십만 확인하면, 가입한 사람이 스스로 PRO로 올려 유료 기능과
     * 운영자 API 키로 나가는 AI 호출을 전부 열 수 있다. 요금제 시스템이 통째로 우회되는 것이라
     * 결제가 붙기 전까지 변경은 SYSTEM_ADMIN 조작으로만 일어난다.
     */
    void requireOperator(AdminUser admin) {
        if (admin == null || admin.role != AdminRole.SYSTEM_ADMIN)
            throw new AppException("FORBIDDEN",
                    "요금제 변경은 운영자에게 문의해 주세요.", HttpStatus.FORBIDDEN);
    }
}

/** 조회가 없어도 매일 정책 시각에 만료 체험을 BASIC으로 정리한다. */
@Service
class SubscriptionTrialScheduler {
    private final StoreSubscriptionRepository subscriptions;
    private final SubscriptionService service;

    SubscriptionTrialScheduler(StoreSubscriptionRepository subscriptions, SubscriptionService service) {
        this.subscriptions = subscriptions;
        this.service = service;
    }

    @Scheduled(cron = "0 1 0 * * *", zone = "Asia/Seoul")
    void downgradeExpiredTrials() {
        subscriptions.findAll().stream()
                .filter(s -> s.status == SubscriptionStatus.ACTIVE && s.trialEndsAt != null)
                .forEach(s -> service.planOf(s.store.id));
    }
}
