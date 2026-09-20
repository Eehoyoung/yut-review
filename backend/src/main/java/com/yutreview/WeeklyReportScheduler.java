package com.yutreview;

import java.time.Clock;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * PRO 매장의 주간 리포트를 자동으로 만들어 둔다.
 *
 * 사장이 월요일 아침에 열면 이미 만들어져 있어야 한다. 그 자리에서 만들면 40초를 기다리게 된다.
 *
 * 한 매장이 실패해도 나머지는 계속 만든다. 한 매장의 데이터가 이상하거나 모델이 그 요청만 거부하는
 * 일은 늘 있고, 그것 때문에 전체가 멈추면 자동 리포트라는 약속 자체가 깨진다.
 *
 * 한도는 수동 호출과 같은 통을 쓴다. 자동 생성이 사장의 이번 달 몫을 조용히 먹어치우면 정작 필요할
 * 때 못 쓰게 되므로, 한도가 없으면 그냥 건너뛰고 다음 주에 다시 시도한다.
 */
@Service
class WeeklyReportScheduler {
    private static final Logger log = LoggerFactory.getLogger(WeeklyReportScheduler.class);

    private final StoreSubscriptionRepository subscriptions;
    private final StoreRepository stores;
    private final PlanEntitlementService entitlements;
    private final AiService ai;
    private final Clock clock;

    WeeklyReportScheduler(StoreSubscriptionRepository subscriptions, StoreRepository stores,
                          PlanEntitlementService entitlements, AiService ai, Clock clock) {
        this.subscriptions = subscriptions;
        this.stores = stores;
        this.entitlements = entitlements;
        this.ai = ai;
        this.clock = clock;
    }

    /** 월요일 새벽. 매장이 열기 전에 끝나 있어야 한다. */
    @Scheduled(cron = "0 20 4 * * MON", zone = "Asia/Seoul")
    void scheduledWeeklyReports() {
        generateAll();
    }

    /** 만들어진 리포트 수. 테스트가 이 값으로 확인한다. */
    int generateAll() {
        LocalDate today = LocalDate.now(clock);
        LocalDate to = today.minusDays(1);
        LocalDate from = to.minusDays(6);
        int made = 0;
        for (StoreSubscription subscription : subscriptions.findAll()) {
            if (subscription.status != SubscriptionStatus.ACTIVE) continue;
            if (!entitlements.automaticWeeklyReport(subscription.plan)) continue;
            Store store = stores.findById(subscription.store.id).orElse(null);
            if (store == null || store.status != StoreStatus.ACTIVE) continue;
            try {
                ai.report(store, from, to);
                made++;
            } catch (RuntimeException e) {
                // 한 매장의 실패가 나머지를 막지 않는다. 사유만 남기고 다음으로 넘어간다.
                String code = e instanceof AppException app ? app.code : e.getClass().getSimpleName();
                log.info("주간 리포트 생성 건너뜀 storeId={} 사유={}", store.id, code);
            }
        }
        if (made > 0) log.info("주간 리포트 {}건 생성 ({} ~ {})", made, from, to);
        return made;
    }
}
