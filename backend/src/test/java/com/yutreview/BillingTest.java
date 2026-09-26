package com.yutreview;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 요금제 자동결제와 결제 미확인 시 이용 제한.
 *
 * 진짜 포트원을 부르지 않는다. 같은 규격으로 답하는 작은 서버가 빌링키 조회와 빌링키 결제에
 * 답하고, 테스트는 우리 쪽 판정(누구 빌링키인지, 언제 청구하는지, 결제일이 어떻게 넘어가는지,
 * 언제 막히는지)만 본다. 시간은 결제예정일을 앞뒤로 옮겨서 흉내 낸다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class BillingTest {
    static final String TOSS = "channel-key-toss-test";
    static final HttpServer SERVER;
    /** billingKey → 빌링키 조회 응답의 customer.id */
    static final Map<String, String> KEYS = new ConcurrentHashMap<>();
    static final List<String> PAID = new CopyOnWriteArrayList<>();
    static final List<String> DELETED = new CopyOnWriteArrayList<>();
    static final List<String> PAID_BODIES = new CopyOnWriteArrayList<>();
    static volatile boolean decline;

    static {
        try {
            SERVER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            SERVER.createContext("/", ex -> {
                String path = ex.getRequestURI().getPath();
                String method = ex.getRequestMethod();
                int status = 200;
                String body = "{}";
                if (path.startsWith("/billing-keys/")) {
                    String key = path.substring("/billing-keys/".length());
                    if (method.equals("DELETE")) DELETED.add(key);
                    else if (!KEYS.containsKey(key)) { status = 404; body = "{\"type\":\"BILLING_KEY_NOT_FOUND\"}"; }
                    else body = "{\"status\":\"ISSUED\",\"billingKey\":\"" + key + "\",\"customer\":{\"id\":\""
                            + KEYS.get(key) + "\"},\"channels\":[{\"key\":\"" + TOSS + "\"}]}";
                } else if (path.endsWith("/billing-key") && method.equals("POST")) {
                    String paymentId = path.split("/")[2];
                    String sent = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    if (PAID.contains(paymentId)) { status = 409; body = "{\"type\":\"ALREADY_PAID\"}"; }
                    else if (decline) { status = 502; body = "{\"type\":\"PG_PROVIDER\",\"pgCode\":\"01\",\"pgMessage\":\"[1254][실시간빌링실패|잔액부족]\"}"; }
                    else { PAID.add(paymentId); PAID_BODIES.add(sent); body = "{\"payment\":{\"pgTxId\":\"tx\",\"paidAt\":\"2026-09-27T00:00:00Z\"}}"; }
                }
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(status, bytes.length);
                ex.getResponseBody().write(bytes);
                ex.close();
            });
            SERVER.start();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void portone(DynamicPropertyRegistry r) {
        r.add("app.portone.api-base", () -> "http://127.0.0.1:" + SERVER.getAddress().getPort());
        r.add("app.portone.api-secret", () -> "test-secret");
        r.add("app.portone.store-id", () -> "store-test");
        r.add("app.portone.channels.tosspayments", () -> TOSS);
    }

    @Autowired BillingService billing;
    @Autowired BillingRenewalScheduler scheduler;
    @Autowired ServiceAccessPolicy policy;
    @Autowired StoreRepository stores;
    @Autowired QrRepository qrs;
    @Autowired AdminUserRepository admins;
    @Autowired MembershipRepository memberships;
    @Autowired StoreSubscriptionRepository subscriptions;
    @Autowired SubscriptionPaymentRepository payments;
    @Autowired SubscriptionService plans;
    @Autowired StoreAccessService access;
    @Autowired Clock clock;
    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;

    Store store;
    String qr;
    AdminUser owner;
    AdminUser manager;

    @BeforeEach
    void setup() {
        decline = false;
        Instant now = Instant.now();
        store = new Store();
        store.name = "결제테스트";
        store.phone = "0200000000";
        store.staffPinHash = "x";
        store.status = StoreStatus.ACTIVE;
        store.createdAt = now;
        store.updatedAt = now;
        stores.save(store);
        StoreQrCode q = new StoreQrCode();
        q.store = store;
        q.publicToken = "qr-billing-" + System.nanoTime();
        q.status = QrStatus.ACTIVE;
        q.createdAt = now;
        qrs.save(q);
        qr = q.publicToken;
        owner = member(MembershipRole.OWNER);
        manager = member(MembershipRole.MANAGER);
        plans.startSignupTrial(store);
    }

    private AdminUser member(MembershipRole role) {
        AdminUser a = new AdminUser();
        a.email = role + "-" + System.nanoTime() + "@example.com";
        a.passwordHash = "x";
        a.name = "홍길동";
        a.phone = "01012345678";
        a.role = AdminRole.STORE_ADMIN;
        a.createdAt = Instant.now();
        admins.save(a);
        AdminStoreMembership m = new AdminStoreMembership();
        m.admin = a;
        m.store = store;
        m.role = role;
        m.createdAt = Instant.now();
        memberships.save(m);
        return a;
    }

    private String issue() {
        String key = "billing-key-" + System.nanoTime();
        KEYS.put(key, BillingService.customerId(store.id));
        return key;
    }

    private StoreSubscription row() {
        return subscriptions.findByStoreId(store.id).orElseThrow();
    }

    private long paidCount() {
        return payments.findTop12ByStoreIdOrderByCreatedAtDesc(store.id).stream()
                .filter(p -> p.status == SubscriptionPaymentStatus.PAID).count();
    }

    /** 결제예정일을 지금 기준으로 옮긴다. 체험 종료도 같이 옮겨 "체험이 끝난 매장"을 만든다. */
    private void dueIn(Duration shift) {
        StoreSubscription s = row();
        s.nextBillingAt = Instant.now().plus(shift);
        s.trialEndsAt = s.trialEndsAt == null ? null : s.nextBillingAt;
        subscriptions.save(s);
    }

    /** D+3 00:00(KST)을 막 지난 결제예정일. */
    private void restrictedNow() {
        StoreSubscription s = row();
        s.nextBillingAt = ZonedDateTime.now(clock).toLocalDate().minusDays(3).atStartOfDay(clock.getZone()).toInstant();
        s.trialEndsAt = s.trialEndsAt == null ? null : s.nextBillingAt;
        subscriptions.save(s);
    }

    /** 체험 종료일이 곧 첫 결제예정일이다. */
    @Test
    void signupSetsTheTrialEndAsTheFirstBillingDate() {
        StoreSubscription s = row();
        assertEquals(s.trialEndsAt, s.nextBillingAt);
        assertNull(s.lastPaidAt);
        assertEquals(ServiceState.TRIAL, policy.state(store.id));
    }

    /** 체험 중 카드 등록은 청구하지 않는다. 체험 종료일에 고른 등급으로 청구한다. */
    @Test
    void aCardRegisteredDuringTheTrialIsChargedOnTheTrialEndDate() {
        Instant trialEnd = row().nextBillingAt;
        StoreSubscription s = billing.checkout(owner.id, store.id, Plan.BASIC, issue());
        assertEquals(0, payments.findTop12ByStoreIdOrderByCreatedAtDesc(store.id).size());
        assertEquals(Plan.PRO, s.plan, "체험은 끝까지 쓴다");
        assertEquals(Plan.BASIC, s.nextPlan);
        assertEquals(trialEnd, s.nextBillingAt);

        dueIn(Duration.ofMinutes(-1));
        Instant due = row().nextBillingAt;
        scheduler.run();
        StoreSubscription after = row();
        assertEquals(Plan.BASIC, after.plan);
        assertNotNull(after.lastPaidAt);
        assertEquals(due.atZone(clock.getZone()).plusMonths(1).toInstant(), after.nextBillingAt,
                "다음 결제일은 결제예정일에서 한 달 뒤다");
        assertTrue(PAID_BODIES.get(PAID_BODIES.size() - 1).contains("\"total\":9900"), "BASIC도 판다");
        assertEquals(ServiceState.ACTIVE, policy.state(store.id));
    }

    /** 결제 대상이 아니던(기존) 매장의 첫 결제는 오늘부터 한 달이다. */
    @Test
    void aFirstPaymentOutsideTheTrialStartsTodayAndRecordsBothDates() {
        plans.exemptFromBilling(store.id);
        assertEquals(ServiceState.OPEN, policy.state(store.id));
        StoreSubscription s = billing.checkout(owner.id, store.id, Plan.STANDARD, issue());
        assertEquals(Plan.STANDARD, s.plan);
        assertNull(s.trialEndsAt);
        assertNotNull(s.lastPaidAt);
        assertEquals(s.lastPaidAt.atZone(clock.getZone()).plusMonths(1).toInstant(), s.nextBillingAt);
        assertEquals(1, paidCount());
    }

    /** 남의 매장 고객 id로 발급된 빌링키를 끼워 넣어도 받지 않는다. */
    @Test
    void aBillingKeyIssuedForAnotherStoreIsRejected() {
        String foreign = "billing-key-foreign-" + System.nanoTime();
        KEYS.put(foreign, BillingService.customerId(store.id + 1000));
        assertEquals("BILLING_KEY_INVALID", assertThrows(AppException.class,
                () -> billing.checkout(owner.id, store.id, Plan.PRO, foreign)).code);
        assertNull(row().billingKey);
    }

    @Test
    void onlyTheOwnerPays() {
        assertEquals("FORBIDDEN", assertThrows(AppException.class,
                () -> billing.checkout(manager.id, store.id, Plan.PRO, issue())).code);
    }

    @Test
    void aDeclinedCardChangesNothingButLeavesARecord() {
        plans.exemptFromBilling(store.id);
        decline = true;
        String key = issue();
        AppException declined = assertThrows(AppException.class, () -> billing.checkout(owner.id, store.id, Plan.PRO, key));
        assertEquals("PAYMENT_DECLINED", declined.code);
        assertTrue(declined.getMessage().contains("잔액부족"), "카드사 거절 사유(pgMessage)를 그대로 보여 준다");
        assertNull(row().nextBillingAt);
        assertNull(row().lastPaidAt);
        assertEquals(SubscriptionPaymentStatus.FAILED,
                payments.findTop12ByStoreIdOrderByCreatedAtDesc(store.id).get(0).status);
        assertTrue(DELETED.contains(key), "쓸 수 없는 빌링키를 남겨 두지 않는다");
        assertNull(row().billingLockUntil, "실패해도 잠금은 풀린다");
    }

    /** 결제 기간 중 내리기는 청구하지 않고 다음 결제예정일에 반영된다. */
    @Test
    void downgradingDuringAPaidPeriodWaitsForTheNextBillingDate() {
        plans.exemptFromBilling(store.id);
        billing.checkout(owner.id, store.id, Plan.PRO, issue());
        StoreSubscription s = billing.checkout(owner.id, store.id, Plan.STANDARD, issue());
        assertEquals(Plan.PRO, s.plan);
        assertEquals(Plan.STANDARD, s.nextPlan);
        assertEquals(1, paidCount());

        dueIn(Duration.ofMinutes(-1));
        billing.renew(store.id);
        assertEquals(Plan.STANDARD, row().plan);
        assertNull(row().nextPlan);
        assertEquals(2, paidCount());
    }

    @Test
    void aRenewalRunningTwiceChargesOnce() {
        plans.exemptFromBilling(store.id);
        billing.checkout(owner.id, store.id, Plan.PRO, issue());
        dueIn(Duration.ofMinutes(-1));
        StoreSubscription before = row();
        billing.renew(store.id);
        // 청구는 됐는데 우리 기록이 사라진 상황: 같은 예정일을 다시 청구해도 포트원이 ALREADY_PAID로
        // 막고 우리는 한 번만 기록한다.
        subscriptions.save(before);
        billing.renew(store.id);
        assertEquals(2, paidCount());
    }

    /** 결제가 안 되면 D+2까지는 서비스하고, D+3 00:00부터 막는다. 막힌 뒤로는 더 청구하지 않는다. */
    @Test
    void anUnpaidStoreIsServedThroughDayTwoThenRestricted() {
        plans.exemptFromBilling(store.id);
        billing.checkout(owner.id, store.id, Plan.PRO, issue());
        decline = true;
        dueIn(Duration.ofDays(-1));
        billing.renew(store.id);
        assertEquals(1, row().renewalFailures);
        assertEquals(ServiceState.GRACE, policy.state(store.id));
        assertDoesNotThrow(() -> access.activeQr(qr), "유예 중에는 손님을 받는다");

        restrictedNow();
        assertEquals(ServiceState.RESTRICTED, policy.state(store.id));
        assertEquals("STORE_PAYMENT_REQUIRED", assertThrows(AppException.class, () -> access.activeQr(qr)).code);
        int attempts = payments.findTop12ByStoreIdOrderByCreatedAtDesc(store.id).size();
        billing.renew(store.id);
        assertEquals(attempts, payments.findTop12ByStoreIdOrderByCreatedAtDesc(store.id).size());
    }

    /** 체험 뒤 카드 없이 D+3이 되면 막힌다. 기존 매장(결제예정일 없음)은 막지 않는다. */
    @Test
    void anExpiredTrialWithoutACardIsRestrictedButLegacyStoresAreNot() {
        restrictedNow();
        assertEquals(ServiceState.RESTRICTED, policy.state(store.id));
        plans.exemptFromBilling(store.id);
        assertEquals(ServiceState.OPEN, policy.state(store.id));
    }

    /** 유예 중 결제는 원래 주기를 잇고, 제한 뒤 결제는 오늘부터 새 주기다. 둘 다 바로 풀린다. */
    @Test
    void payingAgainLiftsTheRestriction() {
        dueIn(Duration.ofDays(-1));
        Instant due = row().nextBillingAt;
        billing.checkout(owner.id, store.id, Plan.PRO, issue());
        assertEquals(due.atZone(clock.getZone()).plusMonths(1).toInstant(), row().nextBillingAt);

        restrictedNow();
        billing.checkout(owner.id, store.id, Plan.PRO, issue());
        StoreSubscription s = row();
        assertEquals(s.lastPaidAt.atZone(clock.getZone()).plusMonths(1).toInstant(), s.nextBillingAt);
        assertEquals(ServiceState.ACTIVE, policy.state(store.id));
        assertDoesNotThrow(() -> access.activeQr(qr));
    }

    /** 막힌 사장은 결제 화면과 매장 상세만 쓸 수 있다. 나머지는 안내 코드로 끊긴다. */
    @Test
    void aRestrictedOwnerCanOnlyReachBillingAndTheStoreDetail() throws Exception {
        restrictedNow();
        String auth = "Bearer " + jwt.issue(owner);
        mvc.perform(get("/api/admin/stores/{id}", store.id).header("Authorization", auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.serviceSuspended").value(true));
        mvc.perform(get("/api/admin/stores/{id}/billing", store.id).header("Authorization", auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.serviceState").value("RESTRICTED"));
        mvc.perform(get("/api/admin/stores/{id}/analytics/summary", store.id).header("Authorization", auth))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.error.code").value("SUBSCRIPTION_PAYMENT_REQUIRED"));
        mvc.perform(get("/api/public/stores/by-token/{t}", qr))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("STORE_PAYMENT_REQUIRED"));
    }

    @Test
    void cancellingStopsChargingAndTheStoreIsRestrictedAfterGrace() {
        plans.exemptFromBilling(store.id);
        billing.checkout(owner.id, store.id, Plan.PRO, issue());
        billing.setAutoRenew(owner.id, store.id, false);
        dueIn(Duration.ofMinutes(-1));
        billing.renew(store.id);
        assertEquals(1, paidCount());
        assertEquals(ServiceState.GRACE, policy.state(store.id));
    }

    @Test
    void aSecondCheckoutWhileOneIsRunningIsTurnedAway() {
        plans.exemptFromBilling(store.id);
        StoreSubscription s = row();
        s.billingLockUntil = Instant.now().plusSeconds(60);
        subscriptions.save(s);
        assertEquals("BILLING_BUSY", assertThrows(AppException.class,
                () -> billing.checkout(owner.id, store.id, Plan.PRO, issue())).code);
        assertEquals(0, payments.findTop12ByStoreIdOrderByCreatedAtDesc(store.id).size());
    }
}
