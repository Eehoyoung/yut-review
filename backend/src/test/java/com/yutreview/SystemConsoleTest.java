package com.yutreview;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 운영자 콘솔의 접근 통제.
 *
 * 여기서 확인하는 것은 화면이 아니라 문이다. 비밀번호 하나로는 열리지 않는지, 매장 콘솔 토큰으로는
 * 넘어오지 못하는지, 로그아웃·권한 회수·유휴 만료가 토큰 만료를 기다리지 않고 듣는지, 위험한
 * 조작 앞에서 다시 묻는지, 그리고 무슨 일이 있었는지 지울 수 없게 남는지.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SystemConsoleTest {
    @Autowired OperatorAuthService auth;
    @Autowired TotpService totp;
    @Autowired AdminUserRepository admins;
    @Autowired AdminTotpRepository credentials;
    @Autowired SystemAuditLogRepository auditLogs;
    @Autowired SystemAuditService audit;
    @Autowired OperatorSecurityRepository securities;
    @Autowired OperatorSecurityService securityService;
    @Autowired OperatorSessionRepository sessionRows;
    @Autowired OperatorSessionService sessions;
    @Autowired OperatorBackupCodeService backupCodes;
    @Autowired StoreRepository stores;
    @Autowired SubscriptionService subscriptions;
    @Autowired StoreProvisioningService provisioning;
    @Autowired PasswordEncoder encoder;
    @Autowired JwtService jwt;
    @Autowired Clock clock;
    @Autowired MockMvc mvc;

    private static final String PASSWORD = "Console-Pass-2026!";
    private static final String UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) Chrome/130.0 Safari/537.36";
    AdminUser operator;
    String email, secret;

    @BeforeEach
    void setup() {
        email = "ops-" + System.nanoTime() + "@example.com";
        operator = newOperator(email, ConsoleRole.OWNER);
    }

    /* ---------------- 2단계 인증 ---------------- */

    /** RFC 6238 부록 B의 SHA-1 벡터. 인증 앱과 같은 값을 계산하는지 고정해 둔다. */
    @Test
    void producesTheStandardTotpCodes() {
        String seed = TotpService.encode("12345678901234567890".getBytes(StandardCharsets.US_ASCII));
        assertEquals("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", seed);
        assertEquals("287082", TotpService.code(seed, 59L / 30));
        assertEquals("081804", TotpService.code(seed, 1111111109L / 30));
        assertEquals("050471", TotpService.code(seed, 1111111111L / 30));
        // 32비트를 넘는 카운터에서도 같은 값이 나와야 한다(2033년 이후).
        assertEquals("353130", TotpService.code(seed, 20000000000L / 30));
        assertArrayEquals("12345678901234567890".getBytes(StandardCharsets.US_ASCII), TotpService.decode(seed));
    }

    /** 비밀번호만으로는 토큰이 나오지 않는다. 2단계 인증 등록이 먼저다. */
    @Test
    void passwordAloneNeverOpensTheConsole() {
        OperatorAuthService.Outcome outcome = auth.login(email, PASSWORD, null, null, "10.20.0.1", UA);
        assertNull(outcome.session());
        assertNotNull(outcome.enrollment());
        assertFalse(credentials.findByAdminId(operator.id).orElseThrow().confirmed);

        // 등록이 끝나기 전에는 코드를 넣어도 로그인이 아니라 다시 등록 안내를 받는다.
        assertNull(auth.login(email, PASSWORD, "000000", null, "10.20.0.1", UA).session());
    }

    @Test
    void enrolledOperatorGetsAShortLivedConsoleToken() throws Exception {
        String token = enroll("10.20.0.2");
        mvc.perform(console(get("/api/system/me"), token)).andExpect(status().isOk());
        mvc.perform(console(get("/api/system/overview"), token)).andExpect(status().isOk());
        assertEquals(JwtService.SCOPE_OPERATOR, jwt.verify(token).scope());
        assertNotNull(jwt.verify(token).tokenId());
    }

    /** 같은 30초 코드를 두 번 받지 않는다. 등록에 쓴 코드로 바로 로그인되면 2단계가 아니다. */
    @Test
    void refusesACodeThatWasAlreadyUsed() {
        OperatorAuthService.Enrollment enrollment = auth.login(email, PASSWORD, null, null, "10.20.0.3", UA)
                .enrollment();
        String used = TotpService.code(enrollment.secret(), TotpService.step(clock.instant()));
        auth.confirmEnrollment(enrollment.enrollmentToken(), used, "10.20.0.3");

        assertEquals("TOTP_INVALID", assertThrows(AppException.class,
                () -> auth.login(email, PASSWORD, used, null, "10.20.0.3", UA)).code);
        // 다음 슬롯 코드는 통한다(시계 오차 ±30초 허용).
        assertNotNull(auth.login(email, PASSWORD,
                TotpService.code(enrollment.secret(), TotpService.step(clock.instant()) + 1), null, "10.20.0.3", UA)
                .session());
    }

    /** 등록을 마치면 복구 코드 열 개가 함께 나온다. 한 번 쓰면 끝이다. */
    @Test
    void backupCodesLetALostPhoneBackIn() {
        OperatorAuthService.Enrollment enrollment = auth.login(email, PASSWORD, null, null, "10.20.0.4", UA)
                .enrollment();
        List<String> codes = auth.confirmEnrollment(enrollment.enrollmentToken(),
                TotpService.code(enrollment.secret(), TotpService.step(clock.instant())), "10.20.0.4");
        assertEquals(OperatorBackupCodeService.COUNT, codes.size());
        assertEquals(OperatorBackupCodeService.COUNT, backupCodes.remaining(operator.id));

        String code = codes.get(0);
        assertNotNull(auth.login(email, PASSWORD, null, code, "10.20.0.4", UA).session());
        assertEquals(OperatorBackupCodeService.COUNT - 1, backupCodes.remaining(operator.id));
        // 같은 코드는 다시 통하지 않는다. 대소문자·하이픈 표기는 가리지 않는다.
        assertEquals("BACKUP_CODE_INVALID", assertThrows(AppException.class,
                () -> auth.login(email, PASSWORD, null, code.toLowerCase().replace("-", ""), "10.20.0.4", UA)).code);
        assertNotNull(auth.login(email, PASSWORD, null, codes.get(1).toLowerCase().replace("-", ""), "10.20.0.4", UA)
                .session());
    }

    /* ---------------- 세션 ---------------- */

    /** 로그아웃은 토큰 만료를 기다리지 않는다. 그 토큰은 그 순간부터 죽는다. */
    @Test
    void logoutKillsTheTokenImmediately() throws Exception {
        String token = enroll("10.20.0.5");
        mvc.perform(console(post("/api/system/session/logout"), token)).andExpect(status().isOk());
        mvc.perform(console(get("/api/system/overview"), token)).andExpect(status().isUnauthorized());
    }

    /** 자리를 비운 화면은 서버가 닫는다. 화면 타이머만 믿지 않는다. */
    @Test
    void idleSessionsExpireServerSide() throws Exception {
        String token = enroll("10.20.0.6");
        OperatorSession session = sessionRows.findByTokenId(jwt.verify(token).tokenId()).orElseThrow();
        session.lastSeenAt = clock.instant().minusSeconds(3600);
        sessionRows.save(session);

        mvc.perform(console(get("/api/system/overview"), token)).andExpect(status().isUnauthorized());
        assertEquals("IDLE_TIMEOUT", sessionRows.findById(session.id).orElseThrow().revokedReason);
    }

    /** 토큰을 다른 기기로 옮기면 세션이 닫힌다. 훔친 토큰의 가장 흔한 모양이다. */
    @Test
    void aTokenUsedFromAnotherDeviceIsRevoked() throws Exception {
        String token = enroll("10.20.0.7");
        mvc.perform(get("/api/system/overview").header("Authorization", "Bearer " + token)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) Chrome/130.0"))
                .andExpect(status().isUnauthorized());
        assertEquals("DEVICE_MISMATCH",
                sessionRows.findByTokenId(jwt.verify(token).tokenId()).orElseThrow().revokedReason);
        assertTrue(auditLogs.findAll().stream()
                .anyMatch(row -> SystemAuditService.SESSION_REVOKED.equals(row.action) && !row.succeeded));
    }

    /** 동시에 살아 있는 세션 수를 묶는다. 넘치면 가장 오래된 것부터 닫힌다. */
    @Test
    void oldestSessionsAreClosedWhenTheLimitIsPassed() throws Exception {
        String first = enroll("10.20.0.8");
        for (int i = 0; i < 5; i++) loginAgain("10.20.0.8");
        mvc.perform(console(get("/api/system/me"), first)).andExpect(status().isUnauthorized());
        assertEquals("SESSION_LIMIT", sessionRows.findByTokenId(jwt.verify(first).tokenId()).orElseThrow()
                .revokedReason);
    }

    /* ---------------- 토큰 경계와 권한 회수 ---------------- */

    /** 매장 콘솔 토큰은 운영자 계정의 것이라도 콘솔 API를 열지 못한다. */
    @Test
    void storeConsoleTokenCannotReachTheOperatorApi() throws Exception {
        mvc.perform(get("/api/system/overview")).andExpect(status().isUnauthorized());
        mvc.perform(console(get("/api/system/overview"), jwt.issue(operator))).andExpect(status().isForbidden());
        // 등록용 임시 토큰도 마찬가지다.
        mvc.perform(console(get("/api/system/overview"), jwt.issueEnrollment(operator)))
                .andExpect(status().isForbidden());
    }

    /** 세션 행이 없는 토큰(서명은 맞지만 서버가 모르는 세션)은 통하지 않는다. */
    @Test
    void aSignedTokenWithoutASessionIsUseless() throws Exception {
        mvc.perform(console(get("/api/system/me"), jwt.issueOperator(operator, "not-a-real-session")))
                .andExpect(status().isUnauthorized());
    }

    /** 토큰이 살아 있어도 DB에서 운영자 자격을 잃으면 그 순간부터 막힌다. */
    @Test
    void revokingTheRoleClosesTheConsoleImmediately() throws Exception {
        String token = enroll("10.20.0.9");
        operator.role = AdminRole.STORE_ADMIN;
        admins.save(operator);
        mvc.perform(console(get("/api/system/me"), token)).andExpect(status().isForbidden());
    }

    /** 계정을 중지하면 이미 로그인한 세션도 막힌다. */
    @Test
    void disablingAnAccountClosesItsConsole() throws Exception {
        String token = enroll("10.20.0.10");
        OperatorSecurity security = securities.findByAdminId(operator.id).orElseThrow();
        security.disabled = true;
        securities.save(security);
        mvc.perform(console(get("/api/system/me"), token)).andExpect(status().isForbidden());
    }

    /* ---------------- 로그인 방어 ---------------- */

    /** 없는 계정·틀린 비밀번호·운영자가 아닌 계정이 모두 같은 답을 받는다. */
    @Test
    void failedLoginsLookIdenticalAndAreRecorded() {
        AdminUser storeAdmin = newAdmin("owner-" + System.nanoTime() + "@example.com", AdminRole.STORE_ADMIN);

        assertEquals("AUTH_INVALID", assertThrows(AppException.class,
                () -> auth.login(email, "wrong-password", null, null, "10.20.1.1", UA)).code);
        assertEquals("AUTH_INVALID", assertThrows(AppException.class,
                () -> auth.login(storeAdmin.email, PASSWORD, null, null, "10.20.1.2", UA)).code);
        assertEquals("AUTH_INVALID", assertThrows(AppException.class,
                () -> auth.login("nobody-" + System.nanoTime() + "@example.com", PASSWORD, null, null, "10.20.1.3", UA))
                .code);

        List<SystemAuditLog> recorded = auditLogs.findAll().stream()
                .filter(row -> SystemAuditService.LOGIN_FAILED.equals(row.action) && !row.succeeded).toList();
        assertTrue(recorded.stream().anyMatch(row -> row.actorEmail.equals(email)));
        assertTrue(recorded.stream().anyMatch(row -> row.actorEmail.equals(storeAdmin.email)));
    }

    @Test
    void stopsRepeatedGuessesFromOneAddress() {
        String ip = "10.20.1.4";
        for (int i = 0; i < 5; i++)
            assertEquals("AUTH_INVALID", assertThrows(AppException.class,
                    () -> auth.login(email, "wrong-password", null, null, ip, UA)).code);
        assertEquals("AUTH_RATE_LIMITED", assertThrows(AppException.class,
                () -> auth.login(email, PASSWORD, null, null, ip, UA)).code);
    }

    /** 계정 잠금은 프로세스 메모리가 아니라 DB에 남는다. 재시작으로 풀리지 않는다. */
    @Test
    void locksTheAccountAfterEnoughFailures() {
        OperatorSecurity security = securities.findByAdminId(operator.id).orElseThrow();
        security.failedAttempts = 9;
        securities.save(security);
        assertEquals("AUTH_INVALID", assertThrows(AppException.class,
                () -> auth.login(email, "wrong-password", null, null, "10.20.1.5", UA)).code);

        OperatorSecurity locked = securities.findByAdminId(operator.id).orElseThrow();
        assertNotNull(locked.lockedUntil);
        assertTrue(locked.lockedUntil.isAfter(clock.instant()));
        // 비밀번호가 맞아도 잠금이 먼저다. 잠금 사실은 비밀번호가 맞은 뒤에만 알려 준다.
        assertEquals("ACCOUNT_LOCKED", assertThrows(AppException.class,
                () -> auth.login(email, PASSWORD, null, null, "10.20.1.6", UA)).code);
    }

    /** 계정별 IP 제한. 전역 설정이 비어 있어도 이 계정만 따로 묶을 수 있다. */
    @Test
    void perAccountIpRulesApplyAtLogin() {
        OperatorSecurity security = securities.findByAdminId(operator.id).orElseThrow();
        security.allowedIps = "203.0.113.0/24";
        securities.save(security);
        assertEquals("IP_NOT_ALLOWED", assertThrows(AppException.class,
                () -> auth.login(email, PASSWORD, null, null, "10.20.1.7", UA)).code);
        assertNotNull(auth.login(email, PASSWORD, null, null, "203.0.113.9", UA).enrollment());
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
        assertEquals("INVALID_IP_RULE",
                assertThrows(AppException.class, () -> SystemConsoleSettings.validateIpRules("10.0.0.0/64")).code);
    }

    /* ---------------- 재인증(step-up)과 권한 등급 ---------------- */

    /** 위험한 조작은 최근에 2단계 인증을 통과한 세션에서만 된다. */
    @Test
    void sensitiveChangesNeedRecentTwoFactor() throws Exception {
        Store store = newStore("재인증테스트");
        String token = enroll("10.20.2.1");

        // 로그인 직후는 방금 인증한 상태라 그대로 통과한다.
        mvc.perform(console(put("/api/system/stores/" + store.id + "/plan"), token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"plan\":\"STANDARD\",\"note\":\"현장 지원\"}")).andExpect(status().isOk());

        // 재인증이 오래되면 다시 묻는다.
        OperatorSession session = sessionRows.findByTokenId(jwt.verify(token).tokenId()).orElseThrow();
        session.stepUpAt = clock.instant().minusSeconds(3600);
        sessionRows.save(session);
        mvc.perform(console(put("/api/system/stores/" + store.id + "/plan"), token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"plan\":\"PRO\",\"note\":\"업그레이드\"}")).andExpect(status().isForbidden());
        assertEquals(Plan.STANDARD, subscriptions.planOf(store.id));

        // 코드를 한 번 더 넣으면 다시 열린다.
        mvc.perform(console(post("/api/system/session/step-up"), token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + currentCode() + "\"}")).andExpect(status().isOk());
        mvc.perform(console(put("/api/system/stores/" + store.id + "/plan"), token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"plan\":\"PRO\",\"note\":\"업그레이드\"}")).andExpect(status().isOk());
        assertEquals(Plan.PRO, subscriptions.planOf(store.id));
    }

    /** VIEWER는 보기만 한다. 조회는 되고 변경은 막히며, 막힌 시도도 기록에 남는다. */
    @Test
    void viewersCanLookButNotChange() throws Exception {
        Store store = newStore("열람권한테스트");
        setRole(operator, ConsoleRole.VIEWER);
        String token = enroll("10.20.2.2");

        mvc.perform(console(get("/api/system/overview"), token)).andExpect(status().isOk());
        mvc.perform(console(put("/api/system/stores/" + store.id + "/status"), token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"INACTIVE\",\"note\":\"테스트\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(console(get("/api/system/operators"), token)).andExpect(status().isForbidden());
        assertEquals(StoreStatus.ACTIVE, stores.findById(store.id).orElseThrow().status);
        assertTrue(auditLogs.findAll().stream()
                .anyMatch(row -> SystemAuditService.ACCESS_DENIED.equals(row.action) && !row.succeeded));
    }

    /** OPERATOR는 매장을 다루지만 운영자 계정은 건드리지 못한다. */
    @Test
    void operatorsManageStoresButNotAccounts() throws Exception {
        Store store = newStore("운영권한테스트");
        setRole(operator, ConsoleRole.OPERATOR);
        String token = enroll("10.20.2.3");

        mvc.perform(console(put("/api/system/stores/" + store.id + "/status"), token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"INACTIVE\",\"note\":\"폐업 확인\"}"))
                .andExpect(status().isOk());
        assertEquals(StoreStatus.INACTIVE, stores.findById(store.id).orElseThrow().status);
        mvc.perform(console(get("/api/system/operators"), token)).andExpect(status().isForbidden());
        mvc.perform(console(get("/api/system/audit/verify"), token)).andExpect(status().isForbidden());
    }

    /* ---------------- 운영자 계정 관리 ---------------- */

    /** 만들어 준 계정은 임시 비밀번호를 바꾸기 전까지 아무것도 하지 못한다. */
    @Test
    void newOperatorsMustReplaceTheTemporaryPassword() throws Exception {
        String token = enroll("10.20.3.1");
        String newEmail = "ops2-" + System.nanoTime() + "@example.com";
        mvc.perform(console(post("/api/system/operators"), token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + newEmail + "\",\"name\":\"지원팀\",\"password\":\"Temp-Pass-2026!\","
                        + "\"consoleRole\":\"VIEWER\"}"))
                .andExpect(status().isOk());

        AdminUser created = admins.findByEmail(newEmail).orElseThrow();
        assertEquals(AdminRole.SYSTEM_ADMIN, created.role);
        assertEquals(ConsoleRole.VIEWER, securities.findByAdminId(created.id).orElseThrow().consoleRole);
        assertNull(securities.findByAdminId(created.id).orElseThrow().passwordChangedAt);

        String theirToken = enrollAs(created, "Temp-Pass-2026!", "10.20.3.2");
        mvc.perform(console(get("/api/system/me"), theirToken)).andExpect(status().isOk());
        mvc.perform(console(get("/api/system/overview"), theirToken)).andExpect(status().isForbidden());
        mvc.perform(console(post("/api/system/account/password"), theirToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"Temp-Pass-2026!\",\"newPassword\":\"Their-Own-2026!\","
                        + "\"newPasswordConfirm\":\"Their-Own-2026!\"}"))
                .andExpect(status().isOk());
        mvc.perform(console(get("/api/system/overview"), theirToken)).andExpect(status().isOk());
    }

    /** 마지막 OWNER를 내리거나 중지할 수 없다. 아무도 못 들어가는 콘솔이 되기 때문이다. */
    @Test
    void theLastOwnerCannotBeDemotedOrDisabled() throws Exception {
        String token = enroll("10.20.3.3");
        // 자기 자신은 애초에 내릴 수 없다.
        mvc.perform(console(put("/api/system/operators/" + operator.id), token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"consoleRole\":\"VIEWER\"}")).andExpect(status().isBadRequest());

        AdminUser secondOwner = newOperator("ops3-" + System.nanoTime() + "@example.com", ConsoleRole.OWNER);
        mvc.perform(console(put("/api/system/operators/" + secondOwner.id), token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"consoleRole\":\"VIEWER\"}"))
                .andExpect(status().isOk());
        assertEquals(ConsoleRole.VIEWER, securities.findByAdminId(secondOwner.id).orElseThrow().consoleRole);
    }

    /** 권한을 내리면 그 사람의 세션도 지금 닫힌다. */
    @Test
    void demotingAnOperatorClosesTheirSessions() throws Exception {
        AdminUser other = newOperator("ops4-" + System.nanoTime() + "@example.com", ConsoleRole.OPERATOR);
        String theirToken = enrollAs(other, PASSWORD, "10.20.3.4");
        mvc.perform(console(get("/api/system/overview"), theirToken)).andExpect(status().isOk());

        String ownerToken = enroll("10.20.3.5");
        mvc.perform(console(put("/api/system/operators/" + other.id), ownerToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"consoleRole\":\"VIEWER\"}"))
                .andExpect(status().isOk());
        mvc.perform(console(get("/api/system/overview"), theirToken)).andExpect(status().isUnauthorized());
    }

    /** 남의 세션을 끊는 것은 OWNER의 일이고, 본인 세션은 스스로 끊는다. */
    @Test
    void sessionsCanBeClosedFromTheConsole() throws Exception {
        AdminUser other = newOperator("ops5-" + System.nanoTime() + "@example.com", ConsoleRole.VIEWER);
        String theirToken = enrollAs(other, PASSWORD, "10.20.3.6");
        Long theirSessionId = sessionRows.findByTokenId(jwt.verify(theirToken).tokenId()).orElseThrow().id;

        String ownerToken = enroll("10.20.3.7");
        mvc.perform(console(delete("/api/system/sessions/" + theirSessionId), ownerToken)).andExpect(status().isOk());
        mvc.perform(console(get("/api/system/me"), theirToken)).andExpect(status().isUnauthorized());
    }

    /* ---------------- 비밀번호 ---------------- */

    @Test
    void passwordChangeRequiresTheCurrentOneAndClosesOtherSessions() throws Exception {
        String first = enroll("10.20.4.1");
        String second = loginAgain("10.20.4.1");

        mvc.perform(console(post("/api/system/account/password"), second).contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"nope\",\"newPassword\":\"Another-Pass-2026!\","
                        + "\"newPasswordConfirm\":\"Another-Pass-2026!\"}"))
                .andExpect(status().isUnauthorized());
        // 짧거나 종류가 모자란 비밀번호는 거절한다(운영자는 12자 + 영문·숫자·기호).
        mvc.perform(console(post("/api/system/account/password"), second).contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"onlyletters\","
                        + "\"newPasswordConfirm\":\"onlyletters\"}"))
                .andExpect(status().isBadRequest());

        mvc.perform(console(post("/api/system/account/password"), second).contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"Another-Pass-2026!\","
                        + "\"newPasswordConfirm\":\"Another-Pass-2026!\"}"))
                .andExpect(status().isOk());
        // 바꾼 세션은 살아 있고, 다른 세션은 닫힌다.
        mvc.perform(console(get("/api/system/me"), second)).andExpect(status().isOk());
        mvc.perform(console(get("/api/system/me"), first)).andExpect(status().isUnauthorized());
    }

    /* ---------------- 감사 기록 ---------------- */

    /** 요금제·매장 상태 변경은 사유까지 기록된다. 사유 없는 변경은 받지 않는다. */
    @Test
    void changesAreRecordedWithTheirReason() throws Exception {
        Store store = newStore("기록테스트");
        String token = enroll("10.20.5.1");

        mvc.perform(console(put("/api/system/stores/" + store.id + "/plan"), token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"plan\":\"PRO\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(console(put("/api/system/stores/" + store.id + "/plan"), token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"plan\":\"PRO\",\"note\":\"프로모션 적용\"}"))
                .andExpect(status().isOk());

        assertTrue(auditLogs.findAll().stream().anyMatch(row -> SystemAuditService.PLAN_CHANGED.equals(row.action)
                && store.id.equals(row.targetId) && row.detail.contains("BASIC -> PRO")
                && row.detail.contains("프로모션 적용")));
        // 매장 콘솔 토큰으로는 같은 변경을 할 수 없다. 운영자 계정이어도 마찬가지다.
        mvc.perform(console(put("/api/admin/stores/" + store.id + "/subscription"), jwt.issue(operator))
                .contentType(MediaType.APPLICATION_JSON).content("{\"plan\":\"BASIC\"}"))
                .andExpect(status().isForbidden());
        assertEquals(Plan.PRO, subscriptions.planOf(store.id));
    }

    /** 사슬이 이어져 있어야 하고, 중간을 고치면 어디서 끊겼는지 나와야 한다. */
    @Test
    void auditRowsAreChainedSoTamperingShows() {
        audit.record(operator.id, email, SystemAuditService.LOGIN, null, null, "체인 확인 1", "10.20.5.2", true);
        audit.record(operator.id, email, SystemAuditService.LOGIN, null, null, "체인 확인 2", "10.20.5.2", true);
        SystemAuditService.Integrity before = audit.verify();
        assertTrue(before.intact(), "기록을 남긴 직후에는 사슬이 이어져 있어야 한다");

        SystemAuditLog target = auditLogs.findAll().stream()
                .filter(row -> "체인 확인 1".equals(row.detail)).findFirst().orElseThrow();
        target.detail = "조용히 고친 내용";
        auditLogs.save(target);

        SystemAuditService.Integrity after = audit.verify();
        assertFalse(after.intact());
        assertEquals(target.id, after.firstBrokenId());
    }

    /** CSV로 열었을 때 수식이 실행되지 않아야 한다. 감사 로그를 여는 일이 위험해지면 안 된다. */
    @Test
    void csvExportNeutralisesFormulas() {
        SystemAuditLog row = new SystemAuditLog();
        row.actorEmail = "=cmd|'/c calc'!A1";
        row.action = SystemAuditService.LOGIN;
        row.detail = "보통 내용, 쉼표와 \"따옴표\" 포함";
        row.ip = "10.0.0.1";
        row.succeeded = true;
        row.createdAt = Instant.parse("2026-09-18T00:00:00Z");
        String csv = SystemAuditService.csv(List.of(row));
        assertTrue(csv.contains("\"'=cmd|'/c calc'!A1\""));
        assertTrue(csv.contains("\"보통 내용, 쉼표와 \"\"따옴표\"\" 포함\""));
    }

    @Test
    void auditSearchFiltersAndExportIsItselfRecorded() throws Exception {
        String token = enroll("10.20.5.3");
        mvc.perform(console(get("/api/system/audit?failuresOnly=true"), token)).andExpect(status().isOk());
        mvc.perform(console(get("/api/system/audit?action=LOGIN&actor=" + email), token)).andExpect(status().isOk());
        mvc.perform(console(get("/api/system/audit/export"), token)).andExpect(status().isOk());
        assertTrue(auditLogs.findAll().stream()
                .anyMatch(row -> SystemAuditService.AUDIT_EXPORTED.equals(row.action)));
    }

    /* ---------------- 도우미 ---------------- */

    private MockHttpServletRequestBuilder console(MockHttpServletRequestBuilder builder, String token) {
        return builder.header("Authorization", "Bearer " + token).header("User-Agent", UA);
    }

    private AdminUser newAdmin(String address, AdminRole role) {
        AdminUser admin = new AdminUser();
        admin.email = address;
        admin.passwordHash = encoder.encode(PASSWORD);
        admin.name = "테스트";
        admin.role = role;
        admin.createdAt = clock.instant();
        return admins.save(admin);
    }

    private AdminUser newOperator(String address, ConsoleRole role) {
        AdminUser admin = newAdmin(address, AdminRole.SYSTEM_ADMIN);
        securityService.create(admin, role, clock.instant());
        return admin;
    }

    private void setRole(AdminUser admin, ConsoleRole role) {
        OperatorSecurity security = securities.findByAdminId(admin.id).orElseThrow();
        security.consoleRole = role;
        securities.save(security);
    }

    private Store newStore(String name) {
        AdminUser owner = newAdmin("store-owner-" + System.nanoTime() + "@example.com", AdminRole.STORE_ADMIN);
        return provisioning.provision(owner, name, "01012345678", null, null, null, null).store();
    }

    /** 등록 → 확정 → 로그인. 실제 첫 로그인과 같은 순서를 밟는다. */
    private String enroll(String ip) {
        return enrollAs(operator, PASSWORD, ip);
    }

    private String enrollAs(AdminUser who, String password, String ip) {
        OperatorAuthService.Enrollment enrollment = auth.login(who.email, password, null, null, ip, UA).enrollment();
        Instant now = clock.instant();
        auth.confirmEnrollment(enrollment.enrollmentToken(), TotpService.code(enrollment.secret(),
                TotpService.step(now)), ip);
        if (who.id.equals(operator.id)) secret = enrollment.secret();
        return auth.login(who.email, password, TotpService.code(enrollment.secret(), TotpService.step(now) + 1), null,
                ip, UA).session().accessToken();
    }

    /** 이미 등록을 마친 계정의 추가 로그인. */
    private String loginAgain(String ip) {
        return auth.login(email, PASSWORD, currentCode(), null, ip, UA).session().accessToken();
    }

    /**
     * 지금 시각의 코드. 직전에 쓴 슬롯 기록을 지워 두고 만든다.
     *
     * 실제 사용자는 30초를 기다리면 되지만 테스트는 기다릴 수 없다. 같은 코드를 두 번 받지 않는
     * 규칙 자체는 {@link #refusesACodeThatWasAlreadyUsed}에서 따로 본다.
     */
    private String currentCode() {
        AdminTotpCredential credential = credentials.findByAdminId(operator.id).orElseThrow();
        credential.lastUsedStep = 0;
        credentials.save(credential);
        return TotpService.code(secret, TotpService.step(clock.instant()));
    }
}
