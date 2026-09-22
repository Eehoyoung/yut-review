package com.yutreview;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 2026-09-21 정적 보안점검 10건에 대한 회귀 테스트.
 *
 * 각 테스트는 "공격 경로를 그대로 밟고 통제가 닫히는지"를 본다. 게임 생성 한도는 테스트 전역 설정이
 * 높게 잡혀 있어서(다른 테스트가 한도를 확인하려는 것이 아니다) 여기서만 properties로 낮춘다.
 */
@SpringBootTest(properties = {
        "app.limits.game-per-store-per-minute=3",
        "app.limits.game-per-ip-per-minute=2",
        "app.limits.game-per-store-per-day=3",
    // 승인제는 기본값이 꺼짐이다. 이 클래스는 승인 흐름 자체를 검증하므로 켜고 돈다.
    // 기능을 지운 것이 아니라 꺼 둔 것이라, 다시 켤 때 동작하는지가 여기서 보장된다.
    "app.store-approval-required=true"
})
class SecurityRemediationTest {
    @Autowired StoreRepository stores;
    @Autowired QrRepository qrs;
    @Autowired PasswordEncoder encoder;
    @Autowired GameConfigService config;
    @Autowired GameService games;
    @Autowired CouponService couponService;
    @Autowired CouponRepository coupons;
    @Autowired ParticipationService participation;
    @Autowired CouponRecoveryService recovery;
    @Autowired CouponRecoverySessionRepository recoverySessions;
    @Autowired RateLimitService rateLimits;
    @Autowired RateCounterRepository counters;
    @Autowired ClientIpResolver clientIps;
    @Autowired StoreApprovalService approvals;
    @Autowired AdminSignupService signup;
    @Autowired AdminUserRepository admins;
    @Autowired MembershipRepository memberships;
    @Autowired PhoneService phones;
    @Autowired PhoneHashMigrationService phoneHashes;
    @Autowired AiService ai;
    @Autowired AiChatHistoryService chatHistory;
    @Autowired FakeLlmProvider fake;
    @Autowired SubscriptionService subscriptions;
    @Autowired EntityManager entityManager;
    @Autowired Clock clock;
    @Autowired org.springframework.transaction.support.TransactionTemplate tx;
    @Autowired OperatorMonitoringService monitoring;

    Store store;
    String qr;

    @BeforeEach void setup() {
        fake.failing = false; fake.brokenJson = false; fake.resetToolCalls();
        store = newStore("보안테스트");
        qr = activeQr(store);
        config.save(store, GameConfigService.defaults());
    }

    private Store newStore(String name) {
        Instant now = clock.instant();
        Store s = new Store();
        s.name = name + "-" + System.nanoTime();
        s.phone = "0200000000";
        s.staffPinHash = encoder.encode("123456");
        s.status = StoreStatus.ACTIVE;
        s.createdAt = now; s.updatedAt = now;
        return stores.save(s);
    }

    private String activeQr(Store s) {
        StoreQrCode q = new StoreQrCode();
        q.store = s; q.publicToken = "qr-" + System.nanoTime(); q.status = QrStatus.ACTIVE; q.createdAt = clock.instant();
        return qrs.save(q).publicToken;
    }

    // ---------------------------------------------------------------- finding 2/3: 쿠폰 회수

    @Test void phoneLookupNoLongerHandsOutTheCouponToken() {
        String phone = "01044443333";
        games.create(qr, "손님", phone, "recovery-happy");
        ParticipationService.State state = participation.state(store.id, phone);
        assertEquals("HAS_ACTIVE_COUPON", state.state());

        String ticket = recovery.issue(store, state.coupon(), state.coupon().phoneHash);
        // 티켓은 저장되지 않는다. DB를 통째로 읽어도 티켓 자체는 복원되지 않는다.
        assertTrue(recoverySessions.findAll().stream().noneMatch(s -> ticket.equals(s.ticketHash)));

        assertEquals(state.coupon().couponToken, recovery.redeem(store.id, ticket).couponToken);
        // 1회용. 같은 티켓을 다시 쓰면 막힌다.
        assertEquals("RECOVERY_TICKET_INVALID",
                assertThrows(AppException.class, () -> recovery.redeem(store.id, ticket)).code);
    }

    @Test void recoveryTicketIsRejectedWhenExpiredOrForeignOrUnknown() {
        String phone = "01044442222";
        games.create(qr, "손님", phone, "recovery-guard");
        Coupon coupon = participation.state(store.id, phone).coupon();

        String expired = recovery.issue(store, coupon, coupon.phoneHash);
        tx.executeWithoutResult(status -> entityManager
                .createQuery("update CouponRecoverySession s set s.expiresAt=:past where s.usedAt is null")
                .setParameter("past", clock.instant().minusSeconds(1)).executeUpdate());
        assertEquals("RECOVERY_TICKET_INVALID",
                assertThrows(AppException.class, () -> recovery.redeem(store.id, expired)).code);

        Store other = newStore("다른매장");
        String crossStore = recovery.issue(store, coupon, coupon.phoneHash);
        assertEquals("RECOVERY_TICKET_INVALID",
                assertThrows(AppException.class, () -> recovery.redeem(other.id, crossStore)).code,
                "같은 티켓을 다른 매장 경로로 쓰면 거부된다");

        assertEquals("RECOVERY_TICKET_INVALID",
                assertThrows(AppException.class, () -> recovery.redeem(store.id, "rt_없는티켓")).code);
        assertEquals("RECOVERY_TICKET_INVALID",
                assertThrows(AppException.class, () -> recovery.redeem(store.id, null)).code);
    }

    @Test void couponTokenItselfStaysThePossessionProof() {
        // 토큰을 직접 들고 오는 경로는 그대로다. 추측할 수 없는 값을 가진 것이 곧 소지 증명이다.
        games.create(qr, "손님", "01044441111", "recovery-direct");
        Coupon issued = participation.state(store.id, "01044441111").coupon();
        assertEquals(issued.id, couponService.get(issued.couponToken).id);
        assertEquals("COUPON_NOT_FOUND",
                assertThrows(AppException.class, () -> couponService.get("cp_지어낸토큰")).code);
    }

    // ---------------------------------------------------------------- finding 1/5: 자원 고갈

    @Test void gameCreationIsCappedPerStorePerIpAndPerDay() {
        // IP 한도(2)가 매장 한도(3)보다 먼저 닫힌다.
        games.create(qr, "A", "01050000001", "cap-ip-1", true, LegalConsentPolicy.CUSTOMER_PRIVACY_VERSION, "203.0.113.7");
        games.create(qr, "B", "01050000002", "cap-ip-2", true, LegalConsentPolicy.CUSTOMER_PRIVACY_VERSION, "203.0.113.7");
        assertEquals("GAME_RATE_LIMITED", assertThrows(AppException.class, () -> games.create(
                qr, "C", "01050000003", "cap-ip-3", true, LegalConsentPolicy.CUSTOMER_PRIVACY_VERSION, "203.0.113.7")).code);

        // 다른 IP로 오면 매장 한도(3)에서 닫힌다. 위 두 건이 이미 매장 카운터에 들어 있다.
        games.create(qr, "D", "01050000004", "cap-store-1", true, LegalConsentPolicy.CUSTOMER_PRIVACY_VERSION, "203.0.113.8");
        assertEquals("GAME_RATE_LIMITED", assertThrows(AppException.class, () -> games.create(
                qr, "E", "01050000005", "cap-store-2", true, LegalConsentPolicy.CUSTOMER_PRIVACY_VERSION, "203.0.113.9")).code);

        // 분당 한도를 풀어도 하루 행 상한(3)이 남는다.
        counters.deleteAll();
        assertEquals("STORE_DAILY_LIMIT", assertThrows(AppException.class, () -> games.create(
                qr, "F", "01050000006", "cap-day", true, LegalConsentPolicy.CUSTOMER_PRIVACY_VERSION, "203.0.113.10")).code);
    }

    @Test void throttledRequestsAreCountedForTheOperatorView() {
        int before = rateLimits.rejectionsLast24h("GAME_RATE_LIMITED");
        games.create(qr, "A", "01051000001", "seen-1", true, LegalConsentPolicy.CUSTOMER_PRIVACY_VERSION, "203.0.113.40");
        games.create(qr, "B", "01051000002", "seen-2", true, LegalConsentPolicy.CUSTOMER_PRIVACY_VERSION, "203.0.113.40");
        assertThrows(AppException.class, () -> games.create(
                qr, "C", "01051000003", "seen-3", true, LegalConsentPolicy.CUSTOMER_PRIVACY_VERSION, "203.0.113.40"));
        // 거절은 예외로 끝난다. 같은 트랜잭션에 기록했다면 이 숫자가 늘지 않았을 것이다.
        assertTrue(rateLimits.rejectionsLast24h("GAME_RATE_LIMITED") > before,
                "막은 요청이 세어지지 않으면 한도가 정상 손님을 막고 있는지 알 방법이 없다");

        Map<String, Object> snapshot = monitoring.snapshot();
        assertTrue(((Map<?, ?>) snapshot.get("throttled")).containsKey("STORE_DAILY_LIMIT"),
                "0인 코드도 내려와야 화면이 조용함과 집계 없음을 구분한다");
        assertEquals(3, ((Map<?, ?>) snapshot.get("limits")).get("gamePerStorePerMinute"));
        Map<?, ?> storage = (Map<?, ?>) snapshot.get("storage");
        assertTrue(((Number) storage.get("gamePlays")).longValue() >= 2);
        // 안내물 용량은 실제 PostgreSQL에서 lo_get(text) 오류로 500이 났던 자리다. 호출되는지만이라도 본다.
        assertTrue(((Number) storage.get("posterBase64Chars")).longValue() >= 0);
        assertTrue(((Number) ((Map<?, ?>) snapshot.get("counters")).get("rows")).longValue() > 0);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> busiest = (List<Map<String, Object>>) snapshot.get("busiestStores");
        Map<String, Object> mine = busiest.stream()
                .filter(row -> store.id.equals(row.get("storeId"))).findFirst().orElseThrow();
        assertEquals(3, ((Number) mine.get("dailyLimit")).intValue());
        assertTrue(((Number) mine.get("usedPercent")).doubleValue() > 0);
        // 집계와 공개 라벨만 나간다.
        assertEquals(List.of("storeId", "name", "playsToday", "dailyLimit", "usedPercent"),
                List.copyOf(mine.keySet()));
    }

    @Test void rateCounterCountsUpToTheBoundaryAndPurgesByTtl() {
        String bucket = "test:" + System.nanoTime();
        Duration window = Duration.ofMinutes(1);
        for (int i = 1; i <= 3; i++) rateLimits.check(bucket, 3, window, "RATE_LIMITED", "too many");
        assertEquals("RATE_LIMITED",
                assertThrows(AppException.class, () -> rateLimits.check(bucket, 3, window, "RATE_LIMITED", "too many")).code);

        // 성공하면 그 키는 초기화된다. 로그인 성공이 다음 로그인을 막지 않는 이유다.
        rateLimits.succeeded(bucket);
        assertDoesNotThrow(() -> rateLimits.check(bucket, 3, window, "RATE_LIMITED", "too many"));

        // TTL이 지난 행은 정리된다. 인메모리 맵과 달리 무한히 쌓이지 않는다.
        tx.executeWithoutResult(status -> entityManager.createQuery("update RateCounter c set c.expiresAt=:past")
                .setParameter("past", clock.instant().minusSeconds(1)).executeUpdate());
        rateLimits.scheduledPurge();
        assertEquals(0, counters.count());
    }

    @Test void customerLockRowsExpireSoTheCounterTableCannotGrowWithCustomers() {
        // 잠금 행이 (매장, 전화번호)마다 오래 살면 rate_counters가 고객 수와 1:1로 자란다.
        // 부하 테스트에서 게임 8,367건에 잠금 행 8,367개가 쌓였고, 30일 TTL이면 목표 규모에서
        // 상한에 닿아 정상 손님 전원이 429를 받는다. 수명은 요청 하나 길이면 충분하다.
        assertTrue(RateLimitService.LOCK_TTL.compareTo(Duration.ofHours(6)) <= 0,
                "잠금 행 수명은 짧아야 한다. 길면 카운터 테이블이 고객 수만큼 자란다");

        games.create(qr, "잠금", "01061110001", "lock-ttl-1");
        Instant ceiling = clock.instant().plus(RateLimitService.LOCK_TTL).plusSeconds(60);
        List<Instant> lockExpiries = tx.execute(status -> entityManager.createQuery(
                "select c.expiresAt from RateCounter c where c.bucket like 'lock:%'", Instant.class).getResultList());
        assertFalse(lockExpiries.isEmpty(), "고객 단위 잠금 행이 만들어져야 한다");
        assertTrue(lockExpiries.stream().allMatch(e -> e.isBefore(ceiling)),
                "잠금 행이 LOCK_TTL보다 오래 살면 안 된다");
    }

    @Test void signupIsThrottledPerIpAndPerBusinessNumber() {
        // 같은 사업자등록번호로 하루 3회까지. 네 번째는 형식이 맞아도 429다.
        signup.signUp(new AdminSignupService.Request("secret1234", "secret1234", "biz0@test.com", "대표",
                "01099990000", "중복상회", "9990000001"), "https://example.test", "203.0.113.20");
        for (int i = 1; i < 3; i++) {
            int attempt = i;
            assertEquals("DUPLICATE_BUSINESS_NUMBER", assertThrows(AppException.class, () -> signup.signUp(
                    new AdminSignupService.Request("secret1234", "secret1234", "biz" + attempt + "@test.com", "대표",
                            "01099990000", "중복상회", "9990000001"), "https://example.test", "203.0.113.20")).code);
        }
        AppException blocked = assertThrows(AppException.class, () -> signup.signUp(new AdminSignupService.Request(
                "secret1234", "secret1234", "biz-final@test.com", "대표", "01099990000",
                "중복상회", "9990000001"), "https://example.test", "203.0.113.20"));
        assertEquals("SIGNUP_RATE_LIMITED", blocked.code,
                "실패한 시도도 세어야 한다. 세지 않으면 실패를 반복해 한도를 우회할 수 있다");
    }

    // ---------------------------------------------------------------- finding 6: 프록시 뒤 클라이언트 IP

    @Test void forwardedIpIsTrustedOnlyFromAConfiguredProxy() {
        MockHttpServletRequest trusted = new MockHttpServletRequest();
        trusted.setRemoteAddr("127.0.0.1");
        trusted.addHeader("X-Real-IP", "203.0.113.5");
        assertEquals("203.0.113.5", clientIps.resolve(trusted), "신뢰 대역의 Nginx가 넘긴 값은 쓴다");

        MockHttpServletRequest spoofed = new MockHttpServletRequest();
        spoofed.setRemoteAddr("198.51.100.9");
        spoofed.addHeader("X-Real-IP", "203.0.113.5");
        assertEquals("198.51.100.9", clientIps.resolve(spoofed),
                "신뢰 대역 밖에서 온 헤더는 무시한다. 믿으면 누구나 남의 버킷을 채울 수 있다");

        MockHttpServletRequest hostname = new MockHttpServletRequest();
        hostname.setRemoteAddr("127.0.0.1");
        hostname.addHeader("X-Real-IP", "attacker.example.com");
        assertEquals("127.0.0.1", clientIps.resolve(hostname), "리터럴 IP가 아니면 쓰지 않는다(DNS 조회 유도 차단)");

        MockHttpServletRequest chained = new MockHttpServletRequest();
        chained.setRemoteAddr("127.0.0.1");
        chained.addHeader("X-Real-IP", "203.0.113.5, 198.51.100.9");
        assertEquals("203.0.113.5", clientIps.resolve(chained));
    }

    // ---------------------------------------------------------------- finding 4: 사업자 통제권

    @Test void selfSignupWaitsForOperatorApprovalAndKeepsAnAuditTrail() {
        StoreProvisioningService.Provisioned p = signup.signUp(new AdminSignupService.Request(
                "secret1234", "secret1234", "pending@test.com", "대표", "01077770000",
                "심사상회", "7770000001"), "https://example.test", "203.0.113.30");
        assertEquals(StoreStatus.PENDING_APPROVAL, p.store().status);

        // 승인 전에는 고객 경로도 관리자 운영 경로도 닫혀 있다.
        String pendingQr = qrs.findFirstByStoreIdAndStatus(p.store().id, QrStatus.ACTIVE).orElseThrow().publicToken;
        assertEquals("STORE_INACTIVE",
                assertThrows(AppException.class, () -> games.create(pendingQr, "손님", "01077771111", "pending-game")).code);
        assertEquals("STORE_PENDING_APPROVAL",
                assertThrows(AppException.class, () -> approvals.requireOperable(p.store())).code);

        AdminUser owner = admins.findByEmail("pending@test.com").orElseThrow();
        assertEquals("OPERATOR_ONLY",
                assertThrows(AppException.class, () -> approvals.requireOperator(owner.id)).code,
                "신청자가 스스로 승인할 수 없다");

        AdminUser operator = operator("operator-approval@test.com");
        approvals.reject(operator, p.store().id, "사업자등록증 확인 불가");
        assertEquals(StoreStatus.REJECTED, stores.findById(p.store().id).orElseThrow().status);
        assertEquals("사업자등록증 확인 불가", approvals.rejectionNote(p.store().id));
        assertEquals("STORE_REJECTED", assertThrows(AppException.class,
                () -> approvals.requireOperable(stores.findById(p.store().id).orElseThrow())).code);

        approvals.reviewAgain(operator, p.store().id, "서류 재제출");
        assertEquals(StoreStatus.PENDING_APPROVAL, stores.findById(p.store().id).orElseThrow().status);

        approvals.approve(operator, p.store().id, null, "https://example.test");
        assertEquals(StoreStatus.ACTIVE, stores.findById(p.store().id).orElseThrow().status);
        assertDoesNotThrow(() -> games.create(pendingQr, "손님", "01077772222", "approved-game"));

        List<Map<String, Object>> events = approvals.events(p.store().id);
        assertEquals(List.of("APPROVE", "REVIEW_AGAIN", "REJECT"), events.stream().map(e -> e.get("action")).toList());
        assertTrue(events.stream().allMatch(e -> "operator-approval@test.com".equals(e.get("actorEmail"))));
    }

    @Test void operatorCanMoveOwnershipToTheRealBusiness() {
        StoreProvisioningService.Provisioned p = signup.signUp(new AdminSignupService.Request(
                "secret1234", "secret1234", "claimer@test.com", "가짜대표", "01066660000",
                "분쟁상회", "6660000001"), "https://example.test", "203.0.113.31");
        AdminUser real = operator("real-owner@test.com");
        real.role = AdminRole.STORE_ADMIN;
        admins.save(real);

        AdminUser operator = operator("operator-ownership@test.com");
        approvals.changeOwner(operator, p.store().id, "real-owner@test.com", "사업자등록증 확인");

        List<AdminStoreMembership> owners = memberships.findByStoreId(p.store().id).stream()
                .filter(m -> m.role == MembershipRole.OWNER).toList();
        assertEquals(1, owners.size());
        assertEquals("real-owner@test.com", owners.get(0).admin.email);
        assertEquals("ADMIN_NOT_FOUND", assertThrows(AppException.class,
                () -> approvals.changeOwner(operator, p.store().id, "nobody@test.com", null)).code);
    }

    private AdminUser operator(String email) {
        AdminUser a = new AdminUser();
        a.email = email; a.passwordHash = encoder.encode("secret1234"); a.name = "운영자";
        a.role = AdminRole.SYSTEM_ADMIN; a.createdAt = clock.instant();
        return admins.save(a);
    }

    // ---------------------------------------------------------------- finding 7: AI 이력

    @Test void chatHistoryNeverCarriesPersonalDataOrSecretsToTheProvider() {
        subscriptions.changePlan(store, Plan.PRO, "테스트");
        chatHistory.clear(store.id);

        // 서버가 가진 이력에 어떤 경로로든 비밀값이 들어갔다고 가정한다(예전 클라이언트 history 경로).
        chatHistory.append(store, "user", "지난주 어땠어?");
        chatHistory.append(store, "assistant", "참여가 늘었습니다.");
        chatHistory.append(store, "user", "01012345678 손님 기록 보여줘");
        chatHistory.append(store, "assistant", "쿠폰 cp_AbCdEfGhIjKlMnOp 확인했습니다.");
        chatHistory.append(store, "user", "Bearer eyJhbGciOiJIUzI1NiJ9.payload 로 다시 봐줘");
        chatHistory.append(store, "user", "키는 sk-abcdefghijklmnop 야");
        chatHistory.append(store, "assistant", "해시 " + "a".repeat(64) + " 입니다.");
        chatHistory.append(store, "user", "직원 PIN 123456 알려줘");

        ai.chat(store, "이번 달 참여 몇 건이야?");
        String payload = fake.lastRequest.messages().stream().map(LlmMessage::content)
                .reduce("", (a, b) -> a + "\n" + b);

        assertTrue(payload.contains("지난주 어땠어?"), "깨끗한 턴은 그대로 남는다");
        assertFalse(payload.contains("01012345678"));
        assertFalse(payload.contains("cp_AbCdEfGhIjKlMnOp"));
        assertFalse(payload.contains("eyJhbGciOiJIUzI1NiJ9"));
        assertFalse(payload.contains("sk-abcdefghijklmnop"));
        assertFalse(payload.contains("a".repeat(64)));
        assertFalse(payload.contains("123456"));
    }

    @Test void chatHistoryIsServerOwnedAndClearable() {
        subscriptions.changePlan(store, Plan.PRO, "테스트");
        chatHistory.clear(store.id);
        ai.chat(store, "지난 30일 참여 몇 건이야?");
        List<AiChatTurn> turns = chatHistory.recent(store.id);
        assertEquals(2, turns.size());
        assertEquals("user", turns.get(0).role);
        assertEquals("assistant", turns.get(1).role);

        // 다른 매장의 이력이 섞이지 않는다.
        Store other = newStore("다른매장");
        assertTrue(chatHistory.recent(other.id).isEmpty());

        assertEquals(2, chatHistory.clear(store.id));
        assertTrue(chatHistory.recent(store.id).isEmpty());
    }

    // ---------------------------------------------------------------- 저장량 (finding 1/5의 디스크 쪽)

    @Test void posterIsStoredInlineSoItCannotLeakLargeObjects() throws Exception {
        // @Lob이 붙으면 PostgreSQL에서 이 컬럼에 OID만 들어가고 실제 바이트는 pg_largeobject에
        // 따로 산다. 그 객체는 행을 덮어써도 회수되지 않아 안내물을 다시 만들 때마다 수백 KB가
        // 영구히 샌다(실측: 3회 재생성에 +414KB). 매장 정보를 고칠 때마다 안내물을 다시 만드니
        // 2GB VM에서 조용히 차오르는 경로였다.
        //
        // H2 PostgreSQL 모드는 이 차이를 재현하지 못해 동작 테스트로는 잡히지 않는다.
        // 그래서 어노테이션 자체를 잠근다.
        assertNull(StorePoster.class.getDeclaredField("contentBase64").getAnnotation(jakarta.persistence.Lob.class),
                "StorePoster.contentBase64에 @Lob을 붙이지 말 것 (Monitoring.java 주석 참고)");
        assertEquals("text",
                StorePoster.class.getDeclaredField("contentBase64")
                        .getAnnotation(jakarta.persistence.Column.class).columnDefinition());
    }

    // ---------------------------------------------------------------- finding 8: CSV 수식 주입

    @Test void csvNeutralizesEveryFormulaTrigger() {
        assertEquals("'=1+1", AnalyticsService.csv("=1+1"));
        assertEquals("'+1", AnalyticsService.csv("+1"));
        assertEquals("'-1", AnalyticsService.csv("-1"));
        assertEquals("'@SUM(A1)", AnalyticsService.csv("@SUM(A1)"));
        // 선행 공백 뒤에 숨긴 수식도 잡는다. 엑셀은 앞의 공백을 무시하고 읽는다.
        assertEquals("' =1+1", AnalyticsService.csv(" =1+1"));
        // tab/CR로 시작하면 중화한 뒤 RFC 방식으로 감싼다.
        assertEquals("\"'\t=1\"", AnalyticsService.csv("\t=1"));
        assertEquals("\"'\r=1\"", AnalyticsService.csv("\r=1"));
        // 예전 규칙(쉼표·따옴표 이스케이프)은 그대로 살아 있다.
        assertEquals("\"아메리카노, 1잔\"", AnalyticsService.csv("아메리카노, 1잔"));
        assertEquals("\"그는 \"\"1등\"\"\"", AnalyticsService.csv("그는 \"1등\""));
        // 평범한 값은 건드리지 않는다.
        assertEquals("아메리카노", AnalyticsService.csv("아메리카노"));
        assertEquals("", AnalyticsService.csv(null));
    }

    // ---------------------------------------------------------------- finding 9: HMAC 키

    @Test void phoneHmacKeyMustBeBase64AndLongEnough() {
        String strong = java.util.Base64.getEncoder().encodeToString(new byte[32]);
        String encryption = java.util.Base64.getEncoder().encodeToString(new byte[32]);
        assertDoesNotThrow(() -> new PhoneService(strong, "", encryption));

        assertThrows(IllegalArgumentException.class, () -> new PhoneService("test-phone-hmac-key", "", encryption),
                "Base64가 아니면 기동하지 않는다");
        assertThrows(IllegalArgumentException.class,
                () -> new PhoneService(java.util.Base64.getEncoder().encodeToString(new byte[31]), "", encryption),
                "32바이트 미만이면 기동하지 않는다");

        // 예외 메시지에 키 값이 들어가면 안 된다. 기동 실패 로그는 가장 널리 공유되는 로그다.
        String message = assertThrows(IllegalArgumentException.class,
                () -> new PhoneService(strong.substring(0, 8), "", encryption)).getMessage();
        assertFalse(message.contains(strong.substring(0, 8)));
    }

    @Test void hmacRotationKeepsCooldownAndRehashMovesStoredHashes() {
        String phone = "01033332222";
        games.create(qr, "회전손님", phone, "rotation-1");

        byte[] currentKey = new byte[32]; currentKey[0] = 1;
        byte[] nextKey = new byte[32]; nextKey[0] = 2;
        String current = java.util.Base64.getEncoder().encodeToString(currentKey);
        String next = java.util.Base64.getEncoder().encodeToString(nextKey);
        String encryption = java.util.Base64.getEncoder().encodeToString(new byte[32]);

        PhoneService before = new PhoneService(current, "", encryption);
        PhoneService rotating = new PhoneService(next, current, encryption);
        assertTrue(rotating.rotating());
        assertEquals(2, rotating.lookupHashes(phone).size());
        assertTrue(rotating.lookupHashes(phone).contains(before.hash(phone)),
                "회전 중에는 이전 키로 저장된 해시도 찾을 수 있어야 쿨타임이 살아 있다");
        assertNotEquals(before.hash(phone), rotating.hash(phone), "쓰기는 언제나 현재 키다");

        // 실제 저장된 해시를 현재 키로 옮긴다. 원문은 AES-GCM으로 남아 있어 되돌릴 수 있다.
        Long playedId = tx.execute(status -> entityManager.createQuery(
                "select g.id from GamePlay g where g.idempotencyKey='rotation-1'", Long.class).getSingleResult());
        String legacy = "f".repeat(64);
        tx.executeWithoutResult(status -> {
            entityManager.createQuery("update GamePlay g set g.phoneHash=:h where g.id=:id")
                    .setParameter("h", legacy).setParameter("id", playedId).executeUpdate();
            entityManager.createQuery("update Coupon c set c.phoneHash=:h where c.gamePlay.id=:id")
                    .setParameter("h", legacy).setParameter("id", playedId).executeUpdate();
        });

        Map<String, Object> result = phoneHashes.rehashAll();
        assertTrue((Long) result.get("rehashed") >= 1);
        assertEquals(phones.hash(phone),
                tx.execute(status -> entityManager.find(GamePlay.class, playedId).phoneHash));
        assertEquals(phones.hash(phone), coupons.findByGamePlayId(playedId).orElseThrow().phoneHash);
        assertEquals("HAS_ACTIVE_COUPON", participation.state(store.id, phone).state());
    }

    // ---------------------------------------------------------------- 잠긴 규칙이 그대로인지

    @Test void lockedCustomerRulesStillHold() {
        // QR만 있으면 직원 승인 없이 게임이 된다. 승인 절차는 매장 개설에만 붙었다.
        GamePlay first = games.create(qr, "손님", "01022221111", "locked-1");
        assertNotNull(coupons.findByGamePlayId(first.id).orElseThrow(), "게임과 쿠폰은 함께 커밋된다");
        // 같은 요청 키를 다시 보내도 쿠폰이 하나 더 생기지 않는다.
        assertEquals(first.id, games.create(qr, "손님", "01022221111", "locked-1").id);
        assertEquals("HAS_ACTIVE_COUPON", participation.state(store.id, "01022221111").state());
        // 다른 매장은 격리된다.
        Store other = newStore("격리매장");
        config.save(other, GameConfigService.defaults());
        assertEquals("CAN_PLAY", participation.state(other.id, "01022221111").state());
    }
}
