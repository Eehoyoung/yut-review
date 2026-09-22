package com.yutreview;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 초대코드.
 *
 * 리워드 정책은 아직 없다. 여기서 잠그는 것은 "누가 누구를 데려왔는가"가 정확히 기록되는가와,
 * 정책이 붙었을 때 이미 오염된 기록이 없는가 두 가지다. 리워드를 나중에 붙이면서 그때부터
 * 기록을 믿을 수는 없다 — 그 전에 쌓인 것을 쓰게 된다.
 */
@SpringBootTest
class InviteCodeTest {
    @Autowired InviteCodeService invites;
    @Autowired AdminSignupService signup;
    @Autowired AdminUserRepository admins;

    @Test void everySignupGetsACodeAndReferralsAreRecordedBothWays() {
        AdminUser inviter = signUp("inviter", "01077770001", "7770000001");
        assertNotNull(inviter.inviteCode);
        assertEquals(InviteCodeService.LENGTH, inviter.inviteCode.length());
        assertTrue(inviter.inviteCode.matches("[A-HJ-NP-Z2-9]{6}"),
                "헷갈리는 글자(0/O/1/I/L)는 쓰지 않는다: " + inviter.inviteCode);
        assertNull(inviter.invitedBy, "코드 없이 가입하면 추천인이 없다");

        AdminUser invited = signUpWith("invited", "01077770002", "7770000002", inviter.inviteCode);
        assertEquals(inviter.id, invited.invitedBy.id);
        // 코드도 함께 얼린다. 초대한 계정이 사라져도 어느 코드로 들어왔는지는 남아야 한다.
        assertEquals(inviter.inviteCode, invited.invitedByCode);
        // 데려온 사람도 자기 코드를 받는다. 받은 사람이 다시 초대할 수 있어야 한다.
        assertNotNull(invited.inviteCode);
        assertNotEquals(inviter.inviteCode, invited.inviteCode);

        assertEquals(1, admins.countByInvitedById(inviter.id));
        assertEquals(0, admins.countByInvitedById(invited.id));
    }

    /**
     * 틀린 코드는 가입을 막는다.
     *
     * 조용히 무시하면 오타를 친 사람이 "추천이 반영됐겠지" 하고 넘어간다. 그 사실은 리워드를
     * 받을 때가 되어서야 드러나고, 그때는 되돌릴 방법이 없다.
     */
    @Test void aWrongCodeStopsTheSignupInsteadOfBeingIgnored() {
        assertEquals("INVITE_CODE_NOT_FOUND", assertThrows(AppException.class,
                () -> signUpWith("wrong", "01077770003", "7770000003", "ZZZZZZ")).code);
        assertTrue(admins.findByEmail("wrong@test.com").isEmpty(), "막혔으면 계정도 남지 않는다");

        // 형식이 아예 다른 값도 같은 결과다. 길이나 글자 종류를 따로 설명하지 않는다 —
        // 사람이 할 일은 "받은 코드를 다시 확인"이지 형식을 배우는 것이 아니다.
        //
        // 사업자번호를 매번 바꾼다. 초대코드 검사는 가입 한도(사업자번호 24시간 3회)보다 뒤에
        // 있어서, 같은 번호로 네 번 시도하면 네 번째는 SIGNUP_RATE_LIMITED로 먼저 막힌다.
        // 코드 검사를 한도보다 앞으로 옮기면 안 된다 — 한도를 쓰지 않고 코드를 훑을 수 있게 된다.
        String[] bads = { "AB", "TOOLONGCODE", "!!!!!!", "OOOOOO" };
        for (int i = 0; i < bads.length; i++) {
            final String bad = bads[i];
            final String biz = "777000010" + i;
            final String phone = "010777701" + i + "0";
            assertEquals("INVITE_CODE_NOT_FOUND", assertThrows(AppException.class,
                    () -> signUpWith("bad" + biz, phone, biz, bad)).code, bad);
        }
    }

    @Test void codesAreCaseInsensitiveAndTolerateSeparators() {
        AdminUser inviter = signUp("caseinviter", "01077770005", "7770000005");
        String code = inviter.inviteCode;

        // 문자로 받은 코드를 소문자로 옮겨 적는 일이 흔하다. 그걸 막으면 맞는 코드를 들고도 못 쓴다.
        AdminUser a = signUpWith("lower", "01077770006", "7770000006", code.toLowerCase());
        assertEquals(inviter.id, a.invitedBy.id);

        // 공백이나 하이픈을 끼워 적어도 같은 코드다.
        AdminUser b = signUpWith("spaced", "01077770007", "7770000007",
                " " + code.charAt(0) + code.charAt(1) + "-" + code.substring(2) + " ");
        assertEquals(inviter.id, b.invitedBy.id);
        assertEquals(2, admins.countByInvitedById(inviter.id));
    }

    /**
     * 자기 자신 추천 차단.
     *
     * 같은 사람이 계정을 하나 더 만들어 자기 코드를 쓰는 길이다. 연락처로 본다 — 매장을 여러 개
     * 하는 사장은 보통 한 계정에 매장을 추가하므로 정상 사용자를 막지 않는다.
     */
    @Test void youCannotInviteYourself() {
        AdminUser self = signUp("selfie", "01077770008", "7770000008");
        assertEquals("INVITE_CODE_SELF", assertThrows(AppException.class,
                () -> signUpWith("selfie2", "01077770008", "7770000009", self.inviteCode)).code);
        assertTrue(admins.findByEmail("selfie2@test.com").isEmpty());

        // 연락처가 다르면 통과한다. 차단 기준은 사람이지 코드가 아니다.
        assertEquals(self.id, signUpWith("friend", "01077770010", "7770000010", self.inviteCode).invitedBy.id);
    }

    @Test void oldAccountsGetACodeTheFirstTimeTheyLookAtIt() {
        AdminUser legacy = signUp("legacy", "01077770011", "7770000011");
        // 이 기능이 생기기 전 가입한 계정을 흉내 낸다.
        legacy.inviteCode = null;
        admins.save(legacy);

        String first = invites.codeFor(legacy.id);
        assertNotNull(first);
        assertEquals(InviteCodeService.LENGTH, first.length());
        // 두 번째 조회가 새 코드를 만들면 이미 남에게 알려 준 값이 바뀐다.
        assertEquals(first, invites.codeFor(legacy.id));
        assertEquals(first, admins.findById(legacy.id).orElseThrow().inviteCode);
    }

    @Test void codesDoNotCollideAcrossManyAccounts() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 40; i++) {
            String code = invites.issue();
            assertTrue(seen.add(code), "같은 코드가 두 번 나왔다: " + code);
        }
        // 정규화는 조회 전에 형식을 거른다. 길이가 다르면 DB를 보지 않는다.
        assertEquals("ABC234", InviteCodeService.normalize(" abc-234 "));
        assertEquals("", InviteCodeService.normalize(null));
    }

    private AdminUser signUp(String handle, String phone, String business) {
        return signUpWith(handle, phone, business, "");
    }

    private AdminUser signUpWith(String handle, String phone, String business, String code) {
        signup.signUp(new AdminSignupService.Request(
                "secret1234", "secret1234", handle + "@test.com", handle + "대표", phone,
                handle + "상회", business, true, LegalConsentPolicy.TERMS_VERSION,
                true, LegalConsentPolicy.ADMIN_PRIVACY_VERSION, LegalConsentPolicy.MARKETING_SMS_VERSION,
                false, false, false, "", code), "https://example.test", "203.0.113." + (handle.hashCode() & 0x7f));
        return admins.findByEmail(handle + "@test.com").orElseThrow();
    }
}
