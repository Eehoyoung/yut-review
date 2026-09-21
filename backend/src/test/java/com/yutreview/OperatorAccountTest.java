package com.yutreview;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 운영자 계정 권한.
 *
 * 매장 심사보다 되돌리기 어려운 동작이다. 운영자를 전부 잃으면 승인을 할 사람이 없어지고,
 * 그 상태를 되돌릴 API 자체가 없어서 DB를 직접 고쳐야 한다. 그래서 잠금 상태로 가는 두 경로를
 * 테스트로 막아 둔다.
 */
@SpringBootTest
class OperatorAccountTest {
    @Autowired OperatorAccountService accounts;
    @Autowired AdminUserRepository admins;
    @Autowired StoreApprovalService approvals;
    @Autowired AdminSignupService signup;
    @Autowired PasswordEncoder encoder;
    @Autowired Clock clock;

    @Test void operatorCanCreateAnotherOperatorWithoutGivingThemAStore() {
        AdminUser actor = operator("create-actor@test.com");

        Map<String, Object> created = accounts.create(actor, "New.Operator@Test.com", "새 운영자",
                "operator1234", "operator1234", "인수인계");

        // 이메일은 소문자로 정규화된다. 로그인이 소문자로만 찾기 때문이다.
        assertEquals("new.operator@test.com", created.get("email"));
        assertEquals("SYSTEM_ADMIN", created.get("role"));
        // 매장을 만들지 않는다. 운영자가 어느 매장의 멤버가 되면 자기 매장을 스스로 심사할 수 있다.
        assertEquals(0L, created.get("storeCount"));

        AdminUser saved = admins.findByEmail("new.operator@test.com").orElseThrow();
        assertEquals(AdminRole.SYSTEM_ADMIN, saved.role);
        assertNotEquals("operator1234", saved.passwordHash, "비밀번호는 평문으로 저장되지 않는다");
        // 응답 어디에도 해시가 실리지 않는다.
        assertFalse(created.containsValue(saved.passwordHash));

        assertEquals("DUPLICATE_EMAIL", assertThrows(AppException.class, () -> accounts.create(
                actor, "new.operator@test.com", "중복", "operator1234", "operator1234", null)).code);
        assertEquals("WEAK_PASSWORD", assertThrows(AppException.class, () -> accounts.create(
                actor, "weak@test.com", "약한", "short1", "short1", null)).code);
        assertEquals("PASSWORD_MISMATCH", assertThrows(AppException.class, () -> accounts.create(
                actor, "mismatch@test.com", "불일치", "operator1234", "operator5678", null)).code);
    }

    @Test void grantAndRevokeAreIdempotentAndRecorded() {
        AdminUser actor = operator("grant-actor@test.com");
        StoreProvisioningService.Provisioned p = signup.signUp(new AdminSignupService.Request(
                "secret1234", "secret1234", "promoted@test.com", "승격대상", "01055551111",
                "승격상회", "5551110001"), "https://example.test", "203.0.113.90");
        AdminUser target = admins.findByEmail("promoted@test.com").orElseThrow();
        assertEquals(AdminRole.STORE_ADMIN, target.role);
        assertNotNull(p.store());

        Map<String, Object> first = accounts.grant(actor, target.id, "지점 운영 인수");
        assertEquals(Boolean.TRUE, first.get("changed"));
        assertEquals("SYSTEM_ADMIN", first.get("role"));
        // 매장 소유권은 그대로 남는다. 권한을 준 것이지 매장을 뺏은 것이 아니다.
        assertEquals(1L, first.get("storeCount"));

        // 재전송 방어. 이미 운영자면 아무 일도 하지 않는다.
        assertEquals(Boolean.FALSE, accounts.grant(actor, target.id, "다시").get("changed"));

        assertEquals(Boolean.TRUE, accounts.revoke(actor, target.id, "인수인계 종료").get("changed"));
        assertEquals(AdminRole.STORE_ADMIN, admins.findById(target.id).orElseThrow().role);
        assertEquals(Boolean.FALSE, accounts.revoke(actor, target.id, "다시").get("changed"));

        // 권한을 잃으면 운영자 문이 닫힌다.
        assertEquals("OPERATOR_ONLY",
                assertThrows(AppException.class, () -> approvals.requireOperator(target.id)).code);

        List<Map<String, Object>> feed = accounts.feed();
        List<Object> actions = feed.stream().filter(e -> "grant-actor@test.com".equals(e.get("actor")))
                .map(e -> e.get("action")).toList();
        // 멱등 호출은 기록을 남기지 않는다. 아무 일도 일어나지 않았기 때문이다.
        assertEquals(List.of("OPERATOR_REVOKED", "OPERATOR_GRANTED"), actions);
        assertTrue(feed.stream().anyMatch(e -> "promoted@test.com".equals(e.get("target"))
                && "인수인계 종료".equals(e.get("note"))));
    }

    /**
     * 운영자가 아무도 없는 상태로 가는 길을 막는다.
     *
     * 이 상태가 되면 승인·거부·소유권 이전을 할 사람이 없고, 운영자를 다시 만드는 API도 운영자만
     * 쓸 수 있어서 DB를 직접 고쳐야 한다. 실수 한 번으로 갈 수 있는 곳이면 안 된다.
     */
    @Test void theSystemCannotBeLeftWithoutAnyOperator() {
        // 기존 테스트가 만든 운영자들을 치우고 딱 한 명만 남긴다.
        for (AdminUser a : admins.findAll()) {
            if (a.role == AdminRole.SYSTEM_ADMIN) { a.role = AdminRole.STORE_ADMIN; admins.save(a); }
        }
        AdminUser only = operator("last-operator@test.com");
        assertEquals(1, admins.countByRole(AdminRole.SYSTEM_ADMIN));

        assertEquals("OPERATOR_SELF_REVOKE",
                assertThrows(AppException.class, () -> accounts.revoke(only, only.id, null)).code,
                "자기 자신을 내리면 그 자리에서 잠긴다");

        AdminUser second = operator("second-operator@test.com");
        assertEquals("OPERATOR_SELF_REVOKE",
                assertThrows(AppException.class, () -> accounts.revoke(second, second.id, null)).code);

        // 둘 있을 때는 서로 회수할 수 있다.
        assertEquals(Boolean.TRUE, accounts.revoke(only, second.id, null).get("changed"));
        assertEquals(1, admins.countByRole(AdminRole.SYSTEM_ADMIN));

        // 이제 only가 마지막 한 명이다. 자기 자신이 아닌 다른 사람이 시도해도 내려가지 않는다.
        // 자기 회수 검사가 먼저 걸려서 이 경로가 가려지지 않는지 확인하는 것이 요점이다.
        AdminUser demoted = admins.findById(second.id).orElseThrow();
        assertEquals("OPERATOR_LAST_ONE",
                assertThrows(AppException.class, () -> accounts.revoke(demoted, only.id, null)).code,
                "마지막 운영자는 누가 시도해도 내려가지 않는다");
        assertEquals(1, admins.countByRole(AdminRole.SYSTEM_ADMIN));
    }

    @Test void accountListIsSearchableAndNeverLeaksPasswordHashes() {
        operator("searchable-operator@test.com");
        signup.signUp(new AdminSignupService.Request(
                "secret1234", "secret1234", "searchable-owner@test.com", "검색대상", "01044443333",
                "검색상회", "4443330001"), "https://example.test", "203.0.113.91");

        AdminController.PageView<Map<String, Object>> found = accounts.list("searchable-owner", 0, 20);
        assertEquals(1, found.content().size());
        Map<String, Object> row = found.content().get(0);
        assertEquals("searchable-owner@test.com", row.get("email"));
        assertEquals("STORE_ADMIN", row.get("role"));
        assertFalse(row.containsKey("passwordHash"));
        assertFalse(row.containsKey("password"));

        // 이름으로도 찾힌다.
        assertEquals(1, accounts.list("검색대상", 0, 20).content().size());
        // 대소문자를 가리지 않는다.
        assertEquals(1, accounts.list("SEARCHABLE-OWNER", 0, 20).content().size());

        assertEquals("INVALID_REQUEST",
                assertThrows(AppException.class, () -> accounts.list(null, 0, 500)).code);
        assertEquals("ADMIN_NOT_FOUND",
                assertThrows(AppException.class, () -> accounts.grant(
                        operator("missing-actor@test.com"), 9_999_999L, null)).code);
    }

    private AdminUser operator(String email) {
        AdminUser a = new AdminUser();
        a.email = email; a.passwordHash = encoder.encode("secret1234"); a.name = "운영자";
        a.role = AdminRole.SYSTEM_ADMIN; a.createdAt = clock.instant();
        return admins.save(a);
    }
}
