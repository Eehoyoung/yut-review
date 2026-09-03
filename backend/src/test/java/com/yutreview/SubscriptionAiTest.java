package com.yutreview;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 요금제 권한과 AI 경계.
 *
 * 실제 OpenAI를 부르지 않는다. FakeLlmProvider가 스키마에 맞는 응답을 만들고, 테스트는 그 앞뒤의
 * 규칙(권한, 한도, 개인정보 배제, 다른 매장 차단, 실패 격리)만 본다. 네트워크에 의존하는 테스트는
 * 실패가 신호가 아니라 잡음이 된다.
 */
@SpringBootTest
class SubscriptionAiTest {
    @Autowired StoreRepository stores;
    @Autowired QrRepository qrs;
    @Autowired PasswordEncoder encoder;
    @Autowired GameConfigService gameConfig;
    @Autowired GameService games;
    @Autowired PrizeRepository prizes;
    @Autowired SubscriptionService subscriptions;
    @Autowired PlanEntitlementService entitlements;
    @Autowired AiService ai;
    @Autowired AiQuotaService quota;
    @Autowired AiContextService context;
    @Autowired AiMonthlyQuotaRepository quotaRows;
    @Autowired AiUsageEventRepository usageEvents;
    @Autowired StoreAccessService access;
    @Autowired AdminSignupService signup;
    @Autowired AdminUserRepository admins;
    @Autowired FakeLlmProvider fake;
    @Autowired AnalyticsService analytics;
    @Autowired WeeklyReportScheduler weekly;
    @Autowired AiUsageService quotaUsage;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper json;
    private static final String QUOTE = String.valueOf('"');

    Store store;
    String qr;

    @BeforeEach
    void setup() {
        fake.failing = false;
        fake.brokenJson = false;
        fake.resetToolCalls();
        fake.lastExchanges = List.of();
        fake.lastRequest = null;
        Instant now = Instant.now();
        store = new Store();
        store.name = "테스트포차";
        store.phone = "0200000000";
        store.staffPinHash = encoder.encode("123456");
        store.status = StoreStatus.ACTIVE;
        store.createdAt = now;
        store.updatedAt = now;
        stores.save(store);
        StoreQrCode q = new StoreQrCode();
        q.store = store;
        q.publicToken = "qr-" + System.nanoTime();
        q.status = QrStatus.ACTIVE;
        q.createdAt = now;
        qrs.save(q);
        qr = q.publicToken;
        gameConfig.save(store, GameConfigService.defaults());
        subscriptions.start(store, Plan.BASIC);
    }

    @Test
    void planDecidesWhichFeaturesOpenAndGameIsNeverGated() {
        // 게임·QR·상품·쿠폰·PIN·쿨타임은 Entitlement에 아예 없다. 어떤 등급에서도 잠기지 않는다는 뜻이다.
        assertEquals(List.of("ADVANCED_ANALYTICS", "BASIC_ANALYTICS", "BRANDING", "CSV_EXPORT"),
                entitlements.entitlements(Plan.PRO).stream().map(Enum::name).sorted().toList());

        assertTrue(entitlements.has(Plan.BASIC, Entitlement.BASIC_ANALYTICS));
        assertFalse(entitlements.has(Plan.BASIC, Entitlement.ADVANCED_ANALYTICS));
        assertFalse(entitlements.has(Plan.BASIC, Entitlement.CSV_EXPORT));
        assertTrue(entitlements.has(Plan.STANDARD, Entitlement.CSV_EXPORT));
        assertTrue(entitlements.has(Plan.STANDARD, Entitlement.BRANDING));
    }

    @Test
    void basicRejectsEveryAiFeature() {
        for (AiFeature feature : AiFeature.values())
            assertFalse(entitlements.has(Plan.BASIC, feature), feature + "는 BASIC에서 열려 있으면 안 된다");
        assertEquals("PLAN_UPGRADE_REQUIRED",
                assertThrows(AppException.class, () -> ai.eventCopy(store, null)).code);
        assertEquals("PLAN_UPGRADE_REQUIRED",
                assertThrows(AppException.class, () -> ai.report(store, null, null)).code);
        assertEquals("PLAN_UPGRADE_REQUIRED",
                assertThrows(AppException.class, () -> ai.improvement(store, null, null)).code);
        assertEquals("PLAN_UPGRADE_REQUIRED",
                assertThrows(AppException.class, () -> ai.chat(store, "참여 몇 건이야?", List.of())).code);
        // 막힌 호출은 한도를 건드리지 않는다.
        assertTrue(quotaRows.findByStoreIdAndQuotaMonth(store.id, quota.currentMonth()).isEmpty());
    }

    @Test
    void standardAllowsCopyAndReportButNotChatOrImprovement() {
        subscriptions.changePlan(store, Plan.STANDARD, "테스트");
        Map<String, Object> copy = ai.eventCopy(store, new AiService.EventCopyRequest("친근한", "가족 손님 위주"));
        assertTrue(copy.containsKey("headline"));
        assertTrue(copy.containsKey("policyNotice"));

        Map<String, Object> report = ai.report(store, LocalDate.now().minusDays(7), LocalDate.now());
        assertTrue(report.containsKey("recommendations"));
        assertTrue(report.containsKey("dataLimitations"));

        assertEquals("PLAN_UPGRADE_REQUIRED",
                assertThrows(AppException.class, () -> ai.chat(store, "지난주 어땠어?", List.of())).code);
        assertEquals("PLAN_UPGRADE_REQUIRED",
                assertThrows(AppException.class, () -> ai.improvement(store, null, null)).code);
    }

    @Test
    void proAllowsEveryAiFeature() {
        subscriptions.changePlan(store, Plan.PRO, "테스트");
        assertTrue(ai.eventCopy(store, null).containsKey("headline"));
        assertTrue(ai.report(store, null, null).containsKey("summary"));
        assertTrue(ai.improvement(store, null, null).containsKey("experiments"));
        Map<String, Object> chat = ai.chat(store, "지난 30일 참여 몇 건이야?", List.of());
        assertNotNull(chat.get("answer"));
        assertTrue(entitlements.automaticWeeklyReport(Plan.PRO));
        assertFalse(entitlements.automaticWeeklyReport(Plan.STANDARD));
    }

    @Test
    void quotaStopsAtTheLimitAndRefundsFailedCalls() {
        subscriptions.changePlan(store, Plan.STANDARD, "테스트");
        int limit = entitlements.monthlyQuota(Plan.STANDARD, AiFeature.AI_REPORT);
        assertEquals(5, limit);
        for (int i = 0; i < limit; i++) ai.report(store, null, null);
        assertEquals("AI_QUOTA_EXCEEDED",
                assertThrows(AppException.class, () -> ai.report(store, null, null)).code);

        // 실패한 호출은 한도를 깎지 않는다. 남은 자리가 없으니 하나 되돌아온 뒤 다시 막혀야 한다.
        AiMonthlyQuota row = quotaRows
                .findByStoreIdAndFeatureAndQuotaMonth(store.id, AiFeature.AI_EVENT_COPY, quota.currentMonth())
                .orElse(null);
        assertNull(row, "쓰지 않은 기능은 행이 만들어지지 않는다");
        fake.failing = true;
        assertEquals("AI_PROVIDER_UNAVAILABLE",
                assertThrows(AppException.class, () -> ai.eventCopy(store, null)).code);
        assertEquals(0, quotaRows
                .findByStoreIdAndFeatureAndQuotaMonth(store.id, AiFeature.AI_EVENT_COPY, quota.currentMonth())
                .orElseThrow().used, "실패한 호출은 되돌려져 사용량이 0이어야 한다");
    }

    @Test
    void concurrentCallsCannotExceedTheLimit() throws Exception {
        subscriptions.changePlan(store, Plan.PRO, "테스트");
        int limit = entitlements.monthlyQuota(Plan.PRO, AiFeature.AI_REPORT);
        int threads = limit + 6;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Boolean>> jobs = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++)
                jobs.add(() -> {
                    try {
                        quota.consume(store, Plan.PRO, AiFeature.AI_REPORT);
                        return true;
                    } catch (AppException e) {
                        return false;
                    }
                });
            int granted = 0;
            for (Future<Boolean> f : pool.invokeAll(jobs)) if (f.get()) granted++;
            assertEquals(limit, granted, "동시에 들어와도 한도를 넘겨 쓸 수 없다");
        } finally {
            pool.shutdownNow();
        }
        assertEquals(limit, quotaRows
                .findByStoreIdAndFeatureAndQuotaMonth(store.id, AiFeature.AI_REPORT, quota.currentMonth())
                .orElseThrow().used);
    }

    @Test
    void nothingSentToTheModelCanIdentifyACustomer() {
        subscriptions.changePlan(store, Plan.PRO, "테스트");
        games.create(qr, "홍길동", "01012345678", "pii-1");
        games.create(qr, "김손님", "01099998888", "pii-2");

        ai.report(store, LocalDate.now().minusDays(7), LocalDate.now());
        String sent = fake.lastRequest.messages().stream().map(LlmMessage::content).reduce("", String::concat)
                + fake.lastRequest.systemPrompt();

        for (String forbidden : List.of("홍길동", "김손님", "01012345678", "01099998888", "5678", "8888"))
            assertFalse(sent.contains(forbidden), "모델 입력에 " + forbidden + "이 들어갔다");
        for (String key : List.of("phoneHash", "phoneLast4", "couponToken", "customerName", "staffPin"))
            assertFalse(sent.contains(key), "모델 입력에 " + key + " 필드가 들어갔다");
        // 대신 집계와 공개 라벨은 들어가야 한다. 아무것도 안 보내면 분석이 될 수 없다.
        assertTrue(sent.contains("테스트포차"));
        assertTrue(sent.contains("couponsIssued"));
    }

    @Test
    void chatToolsSeeOnlyTheAuthenticatedStore() {
        subscriptions.changePlan(store, Plan.PRO, "테스트");
        games.create(qr, "홍길동", "01012345678", "tool-1");

        // 도구 정의에 storeId 인자가 없다. 모델이 다른 매장을 지목할 방법 자체가 없어야 한다.
        AiAnalyticsToolRegistry registry = new AiAnalyticsToolRegistry(context);
        for (LlmTool tool : registry.tools())
            assertFalse(tool.parameters().toString().contains("storeId"),
                    tool.name() + "이 storeId를 인자로 받는다");

        Map<String, Object> summary = registry.invoke(store, Plan.PRO, "get_period_summary", null);
        assertEquals(1L, summary.get("plays"));
        assertFalse(summary.toString().contains("01012345678"));
    }

    @Test
    void anotherStoreIsRejectedBeforeAnythingIsRead() {
        AdminSignupService.Request r = new AdminSignupService.Request("secret1234", "secret1234",
                "other-owner@test.com", "김대표", "01044445555", "다른포차", "5556667778");
        StoreProvisioningService.Provisioned other = signup.signUp(r);
        AdminUser owner = admins.findByEmail("other-owner@test.com").orElseThrow();

        // 남의 매장은 멤버십 검사에서 막힌다. 존재 여부도 알려주지 않는다.
        assertEquals("FORBIDDEN",
                assertThrows(AppException.class, () -> access.member(owner.id, store.id)).code);
        // 자기 매장은 통과하고, 기본 요금제는 BASIC이다.
        access.member(owner.id, other.store().id);
        assertEquals(Plan.BASIC, subscriptions.planOf(other.store().id));
    }

    @Test
    void aiFailureDoesNotBreakTheCustomerFlow() {
        subscriptions.changePlan(store, Plan.PRO, "테스트");
        fake.failing = true;

        assertEquals("AI_PROVIDER_UNAVAILABLE",
                assertThrows(AppException.class, () -> ai.report(store, null, null)).code);

        // AI가 죽어 있어도 손님은 윷을 던지고 쿠폰을 받는다. AI는 관리자 경로에만 있다.
        GamePlay play = games.create(qr, "손님", "01077776666", "after-ai-failure");
        Coupon coupon = games.reveal(play.publicId);
        assertEquals(CouponStatus.ISSUED, coupon.status);
        assertNotNull(coupon.couponToken);

        // 실패도 기록은 남는다. 비용과 장애를 나중에 설명할 수 있어야 한다.
        AiUsageEvent last = usageEvents.findTop20ByStoreIdOrderByCreatedAtDesc(store.id).get(0);
        assertFalse(last.succeeded);
        assertEquals("AI_PROVIDER_UNAVAILABLE", last.failureCode);
    }

    @Test
    void analyticsWindowIsClampedByPlanRetention() {
        LocalDate today = LocalDate.now();
        // BASIC 90일, STANDARD 365일, PRO 상한 없음.
        assertEquals(today.minusDays(90), entitlements.analyticsFloor(Plan.BASIC, today).orElseThrow());
        assertEquals(today.minusDays(365), entitlements.analyticsFloor(Plan.STANDARD, today).orElseThrow());
        assertTrue(entitlements.analyticsFloor(Plan.PRO, today).isEmpty());

        AiContextService.Window basic = context.window(Plan.BASIC, today.minusDays(300), today);
        assertTrue(basic.clampedByPlan());
        assertEquals(today.minusDays(90), basic.from());

        AiContextService.Window pro = context.window(Plan.PRO, today.minusDays(300), today);
        assertFalse(pro.clampedByPlan());
        assertEquals(today.minusDays(300), pro.from());

        // 개인정보 보존은 요금제와 무관하게 120일 기준을 유지한다.
        assertEquals(120, PrivacyCleanupService.RETENTION_DAYS);
    }

    @Test
    void chatInputIsBounded() {
        subscriptions.changePlan(store, Plan.PRO, "테스트");
        assertEquals("INVALID_REQUEST",
                assertThrows(AppException.class, () -> ai.chat(store, "  ", List.of())).code);
        assertEquals("INVALID_REQUEST",
                assertThrows(AppException.class,
                        () -> ai.chat(store, "가".repeat(AiService.MAX_CHAT_MESSAGE_CHARS + 1), List.of())).code);
    }

    @Test
    void ownersCannotUpgradeThemselves() {
        // 결제가 없는 동안 등급 변경은 운영자만 한다. 매장주가 스스로 올릴 수 있으면 요금제가
        // 통째로 우회된다.
        AdminUser owner = new AdminUser();
        owner.email = "owner-self@test.com";
        owner.passwordHash = "x";
        owner.name = "매장주";
        owner.role = AdminRole.STORE_ADMIN;
        owner.createdAt = Instant.now();
        admins.save(owner);
        assertEquals("FORBIDDEN",
                assertThrows(AppException.class, () -> subscriptions.requireOperator(owner)).code);
        assertEquals("FORBIDDEN",
                assertThrows(AppException.class, () -> subscriptions.requireOperator(null)).code);

        AdminUser operator = new AdminUser();
        operator.email = "operator@test.com";
        operator.passwordHash = "x";
        operator.name = "운영자";
        operator.role = AdminRole.SYSTEM_ADMIN;
        operator.createdAt = Instant.now();
        admins.save(operator);
        subscriptions.requireOperator(operator);
    }

    @Test
    void aPeriodEntirelyOutsideRetentionIsRefusedNotZeroed() {
        // 잘라서 from > to가 되면 어떤 행도 안 걸려 "참여 0건"으로 보이고, 모델은 이벤트가 죽었다고
        // 분석한다. 조용히 거짓을 만드는 대신 왜 못 보는지 말한다.
        LocalDate today = LocalDate.now();
        assertEquals("ANALYTICS_OUT_OF_RETENTION",
                assertThrows(AppException.class,
                        () -> context.window(Plan.BASIC, today.minusDays(300), today.minusDays(200))).code);

        // 보관기간 경계에 딱 붙은 구간. 직전 구간은 통째로 밖이라 비교하지 않는다. 0으로 채우면
        // 모델이 "지난 기간 대비 폭증"이라는 없는 사실을 만든다.
        AiContextService.Window edge = context.window(Plan.BASIC, today.minusDays(90), today);
        assertEquals(today.minusDays(90), edge.from());
        assertTrue(context.previousWindow(Plan.BASIC, edge).isEmpty());

        // 경계에서 하루라도 걸치면 비교는 한다. 잘렸다는 사실만 표시된다.
        AiContextService.Window inside = context.window(Plan.BASIC, today.minusDays(89), today);
        assertTrue(context.previousWindow(Plan.BASIC, inside).isPresent());
        assertTrue(context.previousWindow(Plan.BASIC, inside).orElseThrow().clampedByPlan());
        Map<String, Object> compared = context.comparePeriods(store.id, edge, context.previousWindow(Plan.BASIC, edge));
        assertNull(compared.get("previous"));
        assertNotNull(compared.get("note"));
        assertFalse(compared.containsKey("playsChangePercent"));
    }

    @Test
    void paidCallsAreNotRefunded() {
        subscriptions.changePlan(store, Plan.PRO, "테스트");
        String month = quota.currentMonth();

        // 공급자에 닿기 전 실패는 되돌린다.
        fake.failing = true;
        assertEquals("AI_PROVIDER_UNAVAILABLE",
                assertThrows(AppException.class, () -> ai.eventCopy(store, null)).code);
        assertEquals(0, quotaRows.findByStoreIdAndFeatureAndQuotaMonth(store.id, AiFeature.AI_EVENT_COPY, month)
                .orElseThrow().used, "공급자 장애는 되돌린다");

        // 응답을 받은 뒤 형식이 깨진 실패는 이미 과금된 호출이라 되돌리지 않는다. 되돌리면 실패가
        // 반복되는 동안 사용량이 오르지 않은 채 비용만 나간다.
        fake.failing = false;
        fake.brokenJson = true;
        assertEquals("AI_RESPONSE_INVALID",
                assertThrows(AppException.class, () -> ai.eventCopy(store, null)).code);
        assertEquals(1, quotaRows.findByStoreIdAndFeatureAndQuotaMonth(store.id, AiFeature.AI_EVENT_COPY, month)
                .orElseThrow().used, "과금된 실패는 사용량이 남는다");
    }

    @Test
    void chatKeepsEveryToolResultAcrossRounds() {
        subscriptions.changePlan(store, Plan.PRO, "테스트");
        games.create(qr, "홍길동", "01012345678", "tool-rounds");

        // 라운드를 두 번 돌린다. 한 번만 돌면 누적이든 마지막 것만 보내든 결과가 같아서, 고치기
        // 전 코드로도 통과하는 무의미한 테스트가 된다.
        fake.queueToolCalls(List.of(new LlmToolCall("call-1", "get_period_summary", "{}")));
        fake.queueToolCalls(List.of(new LlmToolCall("call-2", "get_prize_performance", "{}")));

        Map<String, Object> answer = ai.chat(store, "지난 30일 참여 몇 건이야?", List.of());
        assertNotNull(answer.get("answer"));
        @SuppressWarnings("unchecked")
        List<String> used = (List<String>) answer.get("toolsUsed");
        assertEquals(List.of("get_period_summary", "get_prize_performance"), used);

        // 두 라운드의 결과가 모두 실려 가야 한다. 마지막 것만 보내면 앞의 근거가 사라져 모델이
        // 숫자를 지어낸다.
        assertEquals(2, fake.lastExchanges.size(), "앞 라운드의 도구 결과가 버려졌다");
        assertEquals(List.of("call-1", "call-2"),
                fake.lastExchanges.stream().map(x -> x.call().callId()).toList());
        assertTrue(fake.lastExchanges.get(0).resultJson().contains("plays"));
        assertTrue(fake.lastExchanges.get(1).resultJson().contains("prizes"));
        for (LlmToolExchange x : fake.lastExchanges)
            assertFalse(x.resultJson().contains("01012345678"), "도구 결과에 전화번호가 들어갔다");
    }

    @Test
    void chatStopsCallingToolsAtTheCapButKeepsTheEvidence() {
        subscriptions.changePlan(store, Plan.PRO, "테스트");
        games.create(qr, "손님", "01055554444", "tool-cap");
        // 계약상 요청당 도구 호출 상한은 4회다. 그보다 많이 요구해도 넘지 않아야 한다.
        for (int i = 1; i <= 8; i++)
            fake.queueToolCalls(List.of(new LlmToolCall("c" + i, "get_period_summary", "{}")));

        Map<String, Object> answer = ai.chat(store, "계속 확인해줘", List.of());
        @SuppressWarnings("unchecked")
        List<String> used = (List<String>) answer.get("toolsUsed");
        assertEquals(AiService.MAX_TOOL_CALLS, used.size(), "상한을 넘겨 도구를 돌렸다");
        // 닫는 호출에도 모은 근거는 그대로 실린다.
        assertEquals(AiService.MAX_TOOL_CALLS, fake.lastExchanges.size());
        // 도구 정의를 지우지 않는다. 지난 function_call만 남으면 공급자가 요청을 거부할 수 있다.
        assertFalse(fake.lastRequest.tools().isEmpty());
        assertTrue(fake.lastRequest.toolChoiceNone());
    }

    @Test
    void outOfRetentionInsideChatTellsTheModelWhy() throws Exception {
        // 도구가 보관기간 밖을 물으면 이유가 담긴 오류가 나가야 한다. "실패했습니다"만 돌려주면
        // 모델이 같은 기간을 다시 물으며 왕복만 소진하고, 사장은 요금제 한계라는 걸 못 듣는다.
        AiAnalyticsToolRegistry registry = new AiAnalyticsToolRegistry(context);
        AppException thrown = assertThrows(AppException.class, () -> registry.invoke(store, Plan.BASIC,
                "get_period_summary", json.readTree("{" + QUOTE + "from" + QUOTE + ":" + QUOTE + "2020-01-01"
                        + QUOTE + "," + QUOTE + "to" + QUOTE + ":" + QUOTE + "2020-02-01" + QUOTE + "}")));
        assertEquals("ANALYTICS_OUT_OF_RETENTION", thrown.code);
        assertTrue(thrown.getMessage().contains("요금제"));
    }

    @Test
    void hourBucketsUseStoreTimeNotServerDefault() {
        // 시간대 집계가 서버 기본 타임존을 따라가면 "피크 시간"이 통째로 밀린다.
        subscriptions.changePlan(store, Plan.PRO, "테스트");
        GamePlay play = games.create(qr, "손님", "01033332222", "hour-1");
        // 기대 시각은 그 참여의 playedAt에서 뽑는다. 지금 시각으로 계산하면 자정 직전에 만든 참여가
        // 다음 날 시각과 비교되어 가끔 깨진다.
        int seoulHour = play.playedAt.atZone(java.time.ZoneId.of("Asia/Seoul")).getHour();
        AiContextService.Window w = context.window(Plan.PRO, play.playedDate.minusDays(1), play.playedDate);
        @SuppressWarnings("unchecked")
        Map<String, Long> byHour = (Map<String, Long>) context.hourlyDistribution(store.id, w).get("playsByHour");
        assertEquals(24, byHour.size());
        assertEquals(1L, byHour.values().stream().mapToLong(Long::longValue).sum());
        assertEquals(1L, byHour.get(String.format("%02d", seoulHour)),
                "매장 시간 기준 시각에 잡혀야 한다");
    }

    @Test
    void analyticsDepthAndExportsFollowThePlan() {
        // BASIC은 요약만. 상세와 CSV는 402로 막힌다.
        assertEquals("PLAN_UPGRADE_REQUIRED",
                assertThrows(AppException.class, () -> analytics.detailed(store.id, null, null)).code);
        assertEquals("PLAN_UPGRADE_REQUIRED",
                assertThrows(AppException.class, () -> analytics.dailyCsv(store.id, LocalDate.now().minusDays(3), LocalDate.now())).code);
        assertEquals(List.of(), analytics.availableExports(store.id));
        assertEquals(false, analytics.summary(store.id).get("advancedAvailable"));

        subscriptions.changePlan(store, Plan.STANDARD, "테스트");
        games.create(qr, "손님", "01011112222", "csv-1");
        assertTrue(analytics.detailed(store.id, null, null).containsKey("hourly"));
        assertEquals(List.of("daily", "prize"), analytics.availableExports(store.id));

        String csv = analytics.dailyCsv(store.id, LocalDate.now().minusDays(1), LocalDate.now());
        assertTrue(csv.contains("날짜,참여수,쿠폰발급,쿠폰사용"));
        assertTrue(csv.contains(LocalDate.now().toString()));
        // 집계만 나간다. 참여자 명단을 내려주는 기능이 아니다.
        for (String forbidden : List.of("홍길동", "손님", "01011112222", "1122"))
            assertFalse(csv.contains(forbidden), "CSV에 " + forbidden + "이 들어갔다");

        String prizeCsv = analytics.prizeCsv(store.id, LocalDate.now().minusDays(1), LocalDate.now());
        assertTrue(prizeCsv.contains("등급,상품명,쿠폰발급,쿠폰사용,사용률(%)"));
        assertFalse(prizeCsv.contains("01011112222"));
    }

    @Test
    void weeklyReportsAreGeneratedForProOnly() {
        // 이 테스트 클래스는 롤백하지 않아 다른 테스트가 만든 매장이 DB에 남는다. 전체 건수 대신
        // 이 매장에 리포트가 생겼는지로 확인한다.
        subscriptions.changePlan(store, Plan.STANDARD, "테스트");
        weekly.generateAll();
        assertEquals("AI_REPORT_NOT_FOUND",
                assertThrows(AppException.class, () -> ai.latestReport(store, AiFeature.AI_REPORT)).code,
                "PRO가 아니면 자동 생성 대상이 아니다");

        subscriptions.changePlan(store, Plan.PRO, "테스트");
        weekly.generateAll();
        assertNotNull(ai.latestReport(store, AiFeature.AI_REPORT).get("content"));

        // 한 매장이 실패해도 예외가 밖으로 나가지 않는다. 자동 생성이 통째로 멈추면 안 된다.
        fake.failing = true;
        assertDoesNotThrow(() -> weekly.generateAll());
    }

    @Test
    void tokenUsageIsSummarisedAndCostIsNotInvented() {
        subscriptions.changePlan(store, Plan.PRO, "테스트");
        ai.eventCopy(store, null);
        Map<String, Object> cost = quotaUsage.monthlyCost(store.id, quota.currentMonth());
        assertEquals(120L, cost.get("inputTokens"));
        assertEquals(80L, cost.get("outputTokens"));
        // 단가가 설정돼 있지 않으면 0원이라고 말하지 않고 모른다고 말한다.
        assertEquals(false, cost.get("pricingConfigured"));
        assertNull(cost.get("estimatedCostUsd"));
    }

    @Test
    void brandingTaglineNeedsThePlan() {
        // 브랜딩은 STANDARD 이상. 안내물 렌더링 자체는 등급과 무관하게 같은 구조다.
        assertFalse(entitlements.has(Plan.BASIC, Entitlement.BRANDING));
        assertTrue(entitlements.has(Plan.STANDARD, Entitlement.BRANDING));
        byte[] plain = StorePosterService.render("테스트포차", "http://localhost:8088/s/token", null);
        byte[] branded = StorePosterService.render("테스트포차", "http://localhost:8088/s/token", "오늘도 고맙습니다");
        assertTrue(plain.length > 0);
        assertNotEquals(plain.length, branded.length, "문구가 실제로 안내물에 반영된다");
    }

    @Test
    void statusExplainsWhyAFeatureIsClosed() {
        subscriptions.changePlan(store, Plan.STANDARD, "테스트");
        Map<String, Object> status = ai.status(store);
        assertEquals("STANDARD", status.get("plan"));
        assertEquals("fake", status.get("provider"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> features = (List<Map<String, Object>>) status.get("features");
        Map<String, Object> chat = features.stream()
                .filter(f -> AiFeature.AI_CHAT.name().equals(f.get("feature"))).findFirst().orElseThrow();
        assertEquals(false, chat.get("allowed"));
        assertEquals(0, chat.get("limitPerMonth"));

        Map<String, Object> copy = features.stream()
                .filter(f -> AiFeature.AI_EVENT_COPY.name().equals(f.get("feature"))).findFirst().orElseThrow();
        assertEquals(true, copy.get("allowed"));
        assertEquals(20, copy.get("limitPerMonth"));
    }
}
