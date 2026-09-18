package com.yutreview;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 운영자 콘솔의 접근 통제.
 *
 * 여기서 확인하는 것은 화면이 아니라 문이다. 비밀번호 하나로는 열리지 않는지, 매장 콘솔 토큰으로는
 * 넘어오지 못하는지, 권한을 회수하면 이미 발급된 토큰이 바로 막히는지, 그리고 무슨 일이 있었는지
 * 기록이 남는지.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SystemConsoleTest {
    @Autowired OperatorAuthService auth;
    @Autowired TotpService totp;
    @Autowired AdminUserRepository admins;
    @Autowired AdminTotpRepository credentials;
    @Autowired SystemAuditLogRepository auditLogs;
    @Autowired StoreRepository stores;
    @Autowired SubscriptionService subscriptions;
    @Autowired StoreProvisioningService provisioning;
    @Autowired PasswordEncoder encoder;
    @Autowired JwtService jwt;
    @Autowired Clock clock;
    @Autowired MockMvc mvc;

    private static final String PASSWORD = "operator-password-1234";
    AdminUser operator;
    String email;

    @BeforeEach
    void setup() {
        email = "ops-" + System.nanoTime() + "@example.com";
        operator = new AdminUser();
        operator.email = email;
        operator.passwordHash = encoder.encode(PASSWORD);
        operator.name = "운영자";
        operator.role = AdminRole.SYSTEM_ADMIN;
        operator.createdAt = clock.instant();
        admins.save(operator);
    }

    /** RFC 6238 부록 B의 SHA-1 벡터. 인증 앱과 같은 값을 계산하는지 고정해 둔다. */
    @Test
    void producesTheStandardTotpCodes() {
        String secret = TotpService.encode("12345678901234567890".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        assertEquals("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", secret);
        assertEquals("287082", TotpService.code(secret, 59L / 30));
        assertEquals("081804", TotpService.code(secret, 1111111109L / 30));
        assertEquals("050471", TotpService.code(secret, 1111111111L / 30));
        // 32비트를 넘는 카운터에서도 같은 값이 나와야 한다(2033년 이후).
        assertEquals("353130", TotpService.code(secret, 20000000000L / 30));
        assertArrayEquals("12345678901234567890".getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                TotpService.decode(secret));
    }

    /** 비밀번호만으로는 토큰이 나오지 않는다. 2단계 인증 등록이 먼저다. */
    @Test
    void passwordAloneNeverOpensTheConsole() {
        OperatorAuthService.Outcome outcome = auth.login(email, PASSWORD, null, "10.10.0.1");
        assertNull(outcome.session());
        assertNotNull(outcome.enrollment());
        assertFalse(credentials.findByAdminId(operator.id).orElseThrow().confirmed);

        // 등록이 끝나기 전에는 코드를 넣어도 로그인이 아니라 다시 등록 안내를 받는다.
        assertNull(auth.login(email, PASSWORD, "000000", "10.10.0.1").session());
    }

    @Test
    void enrolledOperatorGetsAShortLivedConsoleToken() throws Exception {
        String token = enrollAndLogin("10.10.0.2");
        mvc.perform(get("/api/system/me").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        mvc.perform(get("/api/system/overview").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        assertEquals(JwtService.SCOPE_OPERATOR, jwt.verify(token).scope());
    }

    /** 같은 30초 코드를 두 번 받지 않는다. 등록에 쓴 코드로 바로 로그인되면 2단계가 아니다. */
    @Test
    void refusesACodeThatWasAlreadyUsed() {
        OperatorAuthService.Enrollment enrollment = auth.login(email, PASSWORD, null, "10.10.0.3").enrollment();
        String used = TotpService.code(enrollment.secret(), TotpService.step(clock.instant()));
        auth.confirmEnrollment(enrollment.enrollmentToken(), used, "10.10.0.3");

        assertEquals("TOTP_INVALID",
                assertThrows(AppException.class, () -> auth.login(email, PASSWORD, used, "10.10.0.3")).code);
        // 다음 슬롯 코드는 통한다(시계 오차 ±30초 허용).
        assertNotNull(auth.login(email, PASSWORD,
                TotpService.code(enrollment.secret(), TotpService.step(clock.instant()) + 1), "10.10.0.3").session());
    }

    /** 매장 콘솔 토큰은 운영자 계정의 것이라도 콘솔 API를 열지 못한다. */
    @Test
    void storeConsoleTokenCannotReachTheOperatorApi() throws Exception {
        mvc.perform(get("/api/system/overview")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/system/overview").header("Authorization", "Bearer " + jwt.issue(operator)))
                .andExpect(status().isForbidden());
        // 등록용 임시 토큰도 마찬가지다.
        mvc.perform(get("/api/system/overview").header("Authorization", "Bearer " + jwt.issueEnrollment(operator)))
                .andExpect(status().isForbidden());
    }

    /** 토큰이 살아 있어도 DB에서 운영자 자격을 잃으면 그 순간부터 막힌다. */
    @Test
    void revokingTheRoleClosesTheConsoleImmediately() throws Exception {
        String token = enrollAndLogin("10.10.0.4");
        operator.role = AdminRole.STORE_ADMIN;
        admins.save(operator);
        mvc.perform(get("/api/system/me").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
    }

    /** 없는 계정·틀린 비밀번호·운영자가 아닌 계정이 모두 같은 답을 받는다. */
    @Test
    void failedLoginsLookIdenticalAndAreRecorded() {
        AdminUser storeAdmin = new AdminUser();
        storeAdmin.email = "owner-" + System.nanoTime() + "@example.com";
        storeAdmin.passwordHash = encoder.encode(PASSWORD);
        storeAdmin.name = "사장";
        storeAdmin.role = AdminRole.STORE_ADMIN;
        storeAdmin.createdAt = clock.instant();
        admins.save(storeAdmin);

        assertEquals("AUTH_INVALID",
                assertThrows(AppException.class, () -> auth.login(email, "wrong-password", null, "10.10.0.5")).code);
        assertEquals("AUTH_INVALID", assertThrows(AppException.class,
                () -> auth.login(storeAdmin.email, PASSWORD, null, "10.10.0.6")).code);
        assertEquals("AUTH_INVALID", assertThrows(AppException.class,
                () -> auth.login("nobody-" + System.nanoTime() + "@example.com", PASSWORD, null, "10.10.0.7")).code);

        List<SystemAuditLog> recorded = auditLogs.findAll().stream()
                .filter(row -> SystemAuditService.LOGIN_FAILED.equals(row.action) && !row.succeeded).toList();
        assertTrue(recorded.stream().anyMatch(row -> row.actorEmail.equals(email)));
        assertTrue(recorded.stream().anyMatch(row -> row.actorEmail.equals(storeAdmin.email)));
    }

    @Test
    void stopsRepeatedGuessesFromOneAddress() {
        String ip = "10.10.0.8";
        for (int i = 0; i < 5; i++)
            assertEquals("AUTH_INVALID",
                    assertThrows(AppException.class, () -> auth.login(email, "wrong-password", null, ip)).code);
        assertEquals("AUTH_RATE_LIMITED",
                assertThrows(AppException.class, () -> auth.login(email, PASSWORD, null, ip)).code);
    }

    @Test
    void ipAllowlistTakesSingleAddressesAndCidrRanges() {
        SystemConsoleSettings open = new SystemConsoleSettings(true, "", "윷리뷰 운영자");
        assertTrue(open.ipAllowed("203.0.113.9"));
        assertFalse(open.restrictsIp());

        SystemConsoleSettings limited = new SystemConsoleSettings(true, "203.0.113.4, 10.0.0.0/8", "윷리뷰 운영자");
        assertTrue(limited.restrictsIp());
        assertTrue(limited.ipAllowed("203.0.113.4"));
        assertFalse(limited.ipAllowed("203.0.113.5"));
        assertTrue(limited.ipAllowed("10.3.2.1"));
        assertFalse(limited.ipAllowed("11.3.2.1"));
        assertFalse(limited.ipAllowed("not-an-ip"));
        assertThrows(IllegalArgumentException.class, () -> new SystemConsoleSettings(true, "10.0.0.0/64", "x"));
    }

    /** 요금제 변경은 콘솔에서 일어나고, 무엇이 어떻게 바뀌었는지 기록이 남는다. */
    @Test
    void planChangeFromTheConsoleIsRecorded() throws Exception {
        AdminUser owner = new AdminUser();
        owner.email = "owner-" + System.nanoTime() + "@example.com";
        owner.passwordHash = encoder.encode(PASSWORD);
        owner.name = "사장";
        owner.role = AdminRole.STORE_ADMIN;
        owner.createdAt = clock.instant();
        admins.save(owner);
        Store store = provisioning.provision(owner, "콘솔테스트", "01012345678", null, null, null, null).store();
        String token = enrollAndLogin("10.10.0.9");

        mvc.perform(put("/api/system/stores/" + store.id + "/plan").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"plan\":\"PRO\",\"note\":\"현장 테스트\"}"))
                .andExpect(status().isOk());
        assertEquals(Plan.PRO, subscriptions.planOf(store.id));
        assertTrue(auditLogs.findAll().stream().anyMatch(row -> SystemAuditService.PLAN_CHANGED.equals(row.action)
                && store.id.equals(row.targetId) && row.detail.contains("BASIC -> PRO")));

        // 매장 콘솔 토큰으로는 같은 변경을 할 수 없다. 운영자 계정이어도 마찬가지다.
        mvc.perform(put("/api/admin/stores/" + store.id + "/subscription")
                .header("Authorization", "Bearer " + jwt.issue(operator)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"plan\":\"BASIC\"}")).andExpect(status().isForbidden());
        assertEquals(Plan.PRO, subscriptions.planOf(store.id));
    }

    /** 중지한 매장은 손님이 QR로 들어오지 못한다. 이미 발급된 쿠폰은 건드리지 않는다. */
    @Test
    void canStopAndResumeAStore() throws Exception {
        AdminUser owner = new AdminUser();
        owner.email = "owner-" + System.nanoTime() + "@example.com";
        owner.passwordHash = encoder.encode(PASSWORD);
        owner.name = "사장";
        owner.role = AdminRole.STORE_ADMIN;
        owner.createdAt = clock.instant();
        admins.save(owner);
        Store store = provisioning.provision(owner, "중지테스트", "01012345678", null, null, null, null).store();
        String token = enrollAndLogin("10.10.0.10");

        mvc.perform(put("/api/system/stores/" + store.id + "/status").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"INACTIVE\"}"))
                .andExpect(status().isOk());
        assertEquals(StoreStatus.INACTIVE, stores.findById(store.id).orElseThrow().status);
        assertTrue(auditLogs.findAll().stream()
                .anyMatch(row -> SystemAuditService.STORE_STATUS_CHANGED.equals(row.action)
                        && store.id.equals(row.targetId)));
    }

    private String enrollAndLogin(String ip) {
        OperatorAuthService.Enrollment enrollment = auth.login(email, PASSWORD, null, ip).enrollment();
        Instant now = clock.instant();
        auth.confirmEnrollment(enrollment.enrollmentToken(), TotpService.code(enrollment.secret(),
                TotpService.step(now)), ip);
        return auth.login(email, PASSWORD, TotpService.code(enrollment.secret(), TotpService.step(now) + 1), ip)
                .session().accessToken();
    }
}
