package com.yutreview;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 운영자 접근 통제.
 *
 * 브라우저 없이 실제 ES256 키쌍으로 assertion을 만들어 서명 검증 경로를 통째로 돈다. 서명을
 * 검증하는 척만 하고 통과시키는 구현은 이 테스트에서 살아남지 못한다.
 *
 * rpId/origin은 테스트 프로퍼티로 고정한다. 기본값(localhost)에 기대면 운영 설정이 바뀔 때
 * 테스트가 조용히 다른 것을 재게 된다.
 */
@SpringBootTest(properties = {
    "app.operator-access.enabled=true",
    "app.operator-access.allowed-cidrs=203.0.113.0/24",
    "app.operator-access.rp-id=yut.test",
    "app.public-origin=https://yut.test"
})
class OperatorAccessTest {
    private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();

    @Autowired OperatorAccessService access;
    @Autowired OperatorAccessPolicy policy;
    @Autowired OperatorDeviceRepository devices;
    @Autowired OperatorChallengeRepository challenges;
    @Autowired OperatorSessionRepository sessions;
    @Autowired AdminUserRepository admins;
    @Autowired PasswordEncoder encoder;
    @Autowired Clock clock;

    AdminUser operator;
    KeyPair keys;

    @BeforeEach void setup() throws Exception {
        AdminUser a = new AdminUser();
        a.email = "device-" + System.nanoTime() + "@test.com";
        a.passwordHash = encoder.encode("secret1234");
        a.name = "운영자"; a.role = AdminRole.SYSTEM_ADMIN; a.createdAt = clock.instant();
        operator = admins.save(a);

        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        keys = generator.generateKeyPair();
    }

    @Test void allowlistedAddressesOpenWithoutADeviceAndOthersDoNot() {
        assertTrue(policy.enabled());
        assertTrue(policy.allowedIp("203.0.113.7"));
        assertFalse(policy.allowedIp("112.152.226.119"), "허용 목록 밖은 열리지 않는다");
        // 빈 문자열이나 호스트명은 주소가 아니다. 통과시키면 위조 헤더 한 줄로 문이 열린다.
        assertFalse(policy.allowedIp(""));
        assertFalse(policy.allowedIp("evil.example.com"));
        assertEquals("yut.test", policy.rpId());
    }

    @Test void aRegisteredDeviceCanSignItsWayInAndGetsAShortLivedPass() throws Exception {
        registerDevice("업무용 노트북");

        Map<String, Object> issued = assertion();
        String token = (String) issued.get("deviceToken");
        assertNotNull(token);
        assertEquals(operator.id, access.sessionAdmin(token), "통행증은 그 계정에만 붙는다");

        // 평문은 저장하지 않는다. DB를 읽을 수 있어도 통행증을 그대로 쓸 수 없어야 한다.
        OperatorSession stored = sessions.findByTokenHash(OperatorAccessService.hash(token)).orElseThrow();
        assertNotEquals(token, stored.tokenHash);
        assertTrue(sessions.findByTokenHash(token).isEmpty());

        assertNull(access.sessionAdmin("wrong-token"));
        assertNull(access.sessionAdmin(null));
        assertNull(access.sessionAdmin(""));

        // 기기를 지우면 그 기기로 받은 통행증도 같이 죽는다.
        OperatorDevice device = devices.findByAdminIdOrderByCreatedAtAsc(operator.id).get(0);
        access.remove(operator.id, device.id);
        assertNull(access.sessionAdmin(token), "지운 기기의 통행증은 즉시 끊긴다");
    }

    @Test void aForgedSignatureNeverPasses() throws Exception {
        registerDevice("위조 대상");
        OperatorDevice device = devices.findByAdminIdOrderByCreatedAtAsc(operator.id).get(0);

        String challenge = newChallenge(OperatorChallengePurpose.AUTHENTICATE);
        String clientData = clientDataJson("webauthn.get", challenge);
        byte[] authenticatorData = authenticatorData(1);

        // 다른 키로 서명한다. 서명 형식은 완벽하지만 등록된 공개키와 짝이 아니다.
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        byte[] forged = sign(generator.generateKeyPair(), authenticatorData, clientData);

        assertEquals("DEVICE_ASSERTION_INVALID", assertThrows(AppException.class,
                () -> access.authenticate(operator.id, device.credentialId, clientData,
                        URL.encodeToString(authenticatorData), URL.encodeToString(forged))).code);
    }

    @Test void aChallengeIsSpentOnFirstUseSoTheSameSignatureCannotBeReplayed() throws Exception {
        registerDevice("재전송 대상");
        OperatorDevice device = devices.findByAdminIdOrderByCreatedAtAsc(operator.id).get(0);

        String challenge = newChallenge(OperatorChallengePurpose.AUTHENTICATE);
        String clientData = clientDataJson("webauthn.get", challenge);
        byte[] authenticatorData = authenticatorData(5);
        String signature = URL.encodeToString(sign(keys, authenticatorData, clientData));
        String authB64 = URL.encodeToString(authenticatorData);

        assertNotNull(access.authenticate(operator.id, device.credentialId, clientData, authB64, signature)
                .get("deviceToken"));

        // 똑같은 바이트를 그대로 다시 보낸다. 챌린지가 소모됐으므로 통과하면 안 된다.
        assertEquals("DEVICE_ASSERTION_INVALID", assertThrows(AppException.class,
                () -> access.authenticate(operator.id, device.credentialId, clientData, authB64, signature)).code);
        assertTrue(challenges.findByChallenge(challenge).isEmpty(), "쓴 챌린지는 남지 않는다");
    }

    @Test void theWrongRelyingPartyOrOriginIsRejected() throws Exception {
        registerDevice("경계 확인");
        OperatorDevice device = devices.findByAdminIdOrderByCreatedAtAsc(operator.id).get(0);

        // 1) rpIdHash가 다르다 — 피싱 사이트가 중계한 assertion이 이 모습이다.
        String challenge = newChallenge(OperatorChallengePurpose.AUTHENTICATE);
        String clientData = clientDataJson("webauthn.get", challenge);
        byte[] wrongRp = authenticatorData(sha256("evil.test".getBytes(StandardCharsets.UTF_8)), 1, true);
        assertEquals("DEVICE_ASSERTION_INVALID", assertThrows(AppException.class,
                () -> access.authenticate(operator.id, device.credentialId, clientData,
                        URL.encodeToString(wrongRp), URL.encodeToString(sign(keys, wrongRp, clientData)))).code);

        // 2) origin이 다르다.
        String challenge2 = newChallenge(OperatorChallengePurpose.AUTHENTICATE);
        String evilOrigin = "{\"type\":\"webauthn.get\",\"challenge\":\"" + challenge2
                + "\",\"origin\":\"https://evil.test\"}";
        byte[] data2 = authenticatorData(2);
        assertEquals("DEVICE_ASSERTION_INVALID", assertThrows(AppException.class,
                () -> access.authenticate(operator.id, device.credentialId, evilOrigin,
                        URL.encodeToString(data2), URL.encodeToString(sign(keys, data2, evilOrigin)))).code);

        // 3) User Present 비트가 꺼져 있다 — 사람이 기기를 만지지 않았다는 뜻이다.
        String challenge3 = newChallenge(OperatorChallengePurpose.AUTHENTICATE);
        String clientData3 = clientDataJson("webauthn.get", challenge3);
        byte[] notPresent = authenticatorData(sha256("yut.test".getBytes(StandardCharsets.UTF_8)), 3, false);
        assertEquals("DEVICE_ASSERTION_INVALID", assertThrows(AppException.class,
                () -> access.authenticate(operator.id, device.credentialId, clientData3,
                        URL.encodeToString(notPresent), URL.encodeToString(sign(keys, notPresent, clientData3)))).code);
    }

    @Test void anotherOperatorsDeviceCannotBeUsed() throws Exception {
        registerDevice("남의 기기");
        OperatorDevice device = devices.findByAdminIdOrderByCreatedAtAsc(operator.id).get(0);

        AdminUser other = new AdminUser();
        other.email = "other-" + System.nanoTime() + "@test.com";
        other.passwordHash = encoder.encode("secret1234"); other.name = "다른 운영자";
        other.role = AdminRole.SYSTEM_ADMIN; other.createdAt = clock.instant();
        admins.save(other);

        String challenge = newChallenge(OperatorChallengePurpose.AUTHENTICATE);
        String clientData = clientDataJson("webauthn.get", challenge);
        byte[] data = authenticatorData(1);
        assertEquals("DEVICE_ASSERTION_INVALID", assertThrows(AppException.class,
                () -> access.authenticate(other.id, device.credentialId, clientData,
                        URL.encodeToString(data), URL.encodeToString(sign(keys, data, clientData)))).code);

        // 남의 기기를 지우지도 못한다.
        assertEquals("DEVICE_NOT_FOUND", assertThrows(AppException.class,
                () -> access.remove(other.id, device.id)).code);
    }

    @Test void aRollingBackSignCounterMeansTheAuthenticatorWasCloned() throws Exception {
        registerDevice("복제 감지");
        OperatorDevice device = devices.findByAdminIdOrderByCreatedAtAsc(operator.id).get(0);

        assertNotNull(assertionWithCounter(device, 10).get("deviceToken"));
        assertEquals(10, devices.findById(device.id).orElseThrow().signCount);

        // 더 낮은 카운터는 같은 자격증명이 두 군데 존재한다는 뜻이다.
        assertEquals("DEVICE_ASSERTION_INVALID", assertThrows(AppException.class,
                () -> assertionWithCounter(device, 9)).code);
        assertEquals("DEVICE_ASSERTION_INVALID", assertThrows(AppException.class,
                () -> assertionWithCounter(device, 10)).code);
        // 앞으로 간 카운터는 정상이다.
        assertNotNull(assertionWithCounter(device, 11).get("deviceToken"));

        // 카운터를 쓰지 않는 인증기(대부분의 패스키)는 0을 계속 보낸다. 막으면 안 된다.
        assertNotNull(assertionWithCounter(device, 0).get("deviceToken"));
        assertNotNull(assertionWithCounter(device, 0).get("deviceToken"));
    }

    @Test void registrationRequiresAFreshChallengeAndRejectsDuplicates() throws Exception {
        String credentialId = registerDevice("첫 기기");

        assertEquals("DEVICE_CHALLENGE_INVALID", assertThrows(AppException.class, () -> access.register(
                operator.id, "가짜", "another-credential",
                Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()), -7,
                clientDataJson("webauthn.create", "never-issued-challenge"))).code);

        // 같은 자격증명을 두 번 등록할 수 없다.
        String challenge = newChallenge(OperatorChallengePurpose.REGISTER);
        assertEquals("DEVICE_ALREADY_REGISTERED", assertThrows(AppException.class, () -> access.register(
                operator.id, "중복", credentialId,
                Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()), -7,
                clientDataJson("webauthn.create", challenge))).code);

        // 읽을 수 없는 공개키는 등록되지 않는다. 등록해 두면 인증 때마다 조용히 실패한다.
        String challenge2 = newChallenge(OperatorChallengePurpose.REGISTER);
        assertEquals("DEVICE_KEY_INVALID", assertThrows(AppException.class, () -> access.register(
                operator.id, "깨진 키", "broken-credential",
                Base64.getEncoder().encodeToString("not a key".getBytes(StandardCharsets.UTF_8)), -7,
                clientDataJson("webauthn.create", challenge2))).code);
    }

    // ---------------------------------------------------------------- 도구

    /** 실제 브라우저가 하는 일을 그대로 한다. 등록 챌린지를 받고 SPKI 공개키를 보낸다. */
    private String registerDevice(String name) throws Exception {
        String challenge = newChallenge(OperatorChallengePurpose.REGISTER);
        String credentialId = "credential-" + System.nanoTime();
        access.register(operator.id, name, credentialId,
                Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()), -7,
                clientDataJson("webauthn.create", challenge));
        return credentialId;
    }

    private Map<String, Object> assertion() throws Exception {
        return assertionWithCounter(devices.findByAdminIdOrderByCreatedAtAsc(operator.id).get(0), 1);
    }

    private Map<String, Object> assertionWithCounter(OperatorDevice device, int counter) throws Exception {
        String challenge = newChallenge(OperatorChallengePurpose.AUTHENTICATE);
        String clientData = clientDataJson("webauthn.get", challenge);
        byte[] data = authenticatorData(counter);
        return access.authenticate(operator.id, device.credentialId, clientData,
                URL.encodeToString(data), URL.encodeToString(sign(keys, data, clientData)));
    }

    private String newChallenge(OperatorChallengePurpose purpose) {
        return (String) access.challenge(operator.id, purpose).get("challenge");
    }

    private static String clientDataJson(String type, String challenge) {
        return "{\"type\":\"" + type + "\",\"challenge\":\"" + challenge + "\",\"origin\":\"https://yut.test\"}";
    }

    private byte[] authenticatorData(int counter) {
        return authenticatorData(sha256("yut.test".getBytes(StandardCharsets.UTF_8)), counter, true);
    }

    /** rpIdHash(32) + flags(1) + signCount(4). 브라우저가 보내는 것과 같은 모양이다. */
    private static byte[] authenticatorData(byte[] rpIdHash, int counter, boolean userPresent) {
        byte[] out = new byte[37];
        System.arraycopy(rpIdHash, 0, out, 0, 32);
        out[32] = (byte) (userPresent ? 0x05 : 0x04); // UP | UV
        out[33] = (byte) (counter >>> 24); out[34] = (byte) (counter >>> 16);
        out[35] = (byte) (counter >>> 8); out[36] = (byte) counter;
        return out;
    }

    private static byte[] sign(KeyPair pair, byte[] authenticatorData, String clientDataJson) throws Exception {
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(pair.getPrivate());
        signer.update(authenticatorData);
        signer.update(sha256(clientDataJson.getBytes(StandardCharsets.UTF_8)));
        return signer.sign();
    }

    private static byte[] sha256(byte[] input) {
        try { return MessageDigest.getInstance("SHA-256").digest(input); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
