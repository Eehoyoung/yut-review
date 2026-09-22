package com.yutreview;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 국세청 진위확인의 판정과 실패 처리.
 *
 * 진짜 국세청을 때리지 않는다. 할당량이 있고, 네트워크가 끊기면 테스트가 우리 코드와 무관하게
 * 빨개지며, 무엇보다 실제 사업자등록번호를 fixture에 넣게 된다. 대신 같은 규격으로 답하는 작은
 * 서버를 띄워 응답을 우리가 정한다 — 이 테스트가 보는 것은 국세청이 아니라 우리 판정 로직이다.
 *
 * 엔드포인트가 상수라 서비스를 그대로 쓸 수 없다. 응답 본문을 만들어 같은 판정을 거치게 하는
 * 것이 목적이므로, 여기서는 그 판정 부분을 분리해 검증한다.
 */
class BusinessRegistryTest {
    private HttpServer server;
    private final AtomicReference<String> lastRequestBody = new AtomicReference<>();
    private final AtomicReference<String> response = new AtomicReference<>();
    private final AtomicReference<Integer> statusCode = new AtomicReference<>(200);

    @BeforeEach void startFakeNts() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/nts-businessman/v1/validate", exchange -> {
            lastRequestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = response.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(statusCode.get(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach void stopFakeNts() { server.stop(0); }

    private BusinessRegistryService service() {
        return new BusinessRegistryService(true, "test-key") {
            @Override java.net.URI endpoint() {
                return java.net.URI.create("http://127.0.0.1:" + server.getAddress().getPort()
                        + "/api/nts-businessman/v1/validate?serviceKey=test-key&returnType=JSON");
            }
        };
    }

    @Test void aMatchingBusinessPasses() {
        response.set("""
            {"status_code":"OK","request_cnt":1,"valid_cnt":1,
             "data":[{"b_no":"1234567890","valid":"01","status":{"b_stt_cd":"01"}}]}""");
        assertDoesNotThrow(() -> service().verify("1234567890", "20200101", "홍길동"));

        // 국세청이 요구하는 세 값을 그대로 보냈는지. 하나라도 빠지면 국세청은 411로 답한다.
        String sent = lastRequestBody.get();
        assertTrue(sent.contains("\"b_no\":\"1234567890\""), sent);
        assertTrue(sent.contains("\"start_dt\":\"20200101\""), sent);
        assertTrue(sent.contains("\"p_nm\":\"홍길동\""), sent);
    }

    @Test void aMismatchIsRejected() {
        response.set("""
            {"status_code":"OK","request_cnt":1,"valid_cnt":0,
             "data":[{"b_no":"1234567890","valid":"02","valid_msg":"확인할 수 없습니다"}]}""");
        assertEquals("BUSINESS_NOT_VERIFIED", assertThrows(AppException.class,
                () -> service().verify("1234567890", "20200101", "아무개")).code);
    }

    @Test void closedAndSuspendedBusinessesAreRejectedEvenWhenTheyMatch() {
        // 진위확인만 보면 폐업자도 "일치"다. 등록은 실제로 있었기 때문이다.
        response.set("""
            {"status_code":"OK","data":[{"b_no":"1234567890","valid":"01","status":{"b_stt_cd":"03"}}]}""");
        assertEquals("BUSINESS_CLOSED", assertThrows(AppException.class,
                () -> service().verify("1234567890", "20200101", "홍길동")).code);

        response.set("""
            {"status_code":"OK","data":[{"b_no":"1234567890","valid":"01","status":{"b_stt_cd":"02"}}]}""");
        assertEquals("BUSINESS_SUSPENDED", assertThrows(AppException.class,
                () -> service().verify("1234567890", "20200101", "홍길동")).code);
    }

    /**
     * 국세청에 닿지 못하면 막는다.
     *
     * 열어 주면 장애 시간 동안 아무 번호나 들어온다. 가입은 손님 흐름이 아니라 급하지 않고,
     * 막고 나중에 다시 받는 쪽이 맞다. 503인 것은 사용자 잘못이 아니라는 뜻이 코드에 담겨야 해서다.
     */
    @Test void anUpstreamFailureClosesTheDoor() {
        statusCode.set(500);
        response.set("{\"status_code\":\"ERROR\"}");
        AppException e = assertThrows(AppException.class, () -> service().verify("1234567890", "20200101", "홍길동"));
        assertEquals("BUSINESS_VERIFICATION_UNAVAILABLE", e.code);
        assertEquals(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, e.status);

        statusCode.set(200);
        response.set("not json at all");
        assertEquals("BUSINESS_VERIFICATION_UNAVAILABLE", assertThrows(AppException.class,
                () -> service().verify("1234567890", "20200101", "홍길동")).code);

        // data가 비면 판정할 근거가 없다. 통과시키지 않는다.
        response.set("{\"status_code\":\"OK\",\"data\":[]}");
        assertEquals("BUSINESS_VERIFICATION_UNAVAILABLE", assertThrows(AppException.class,
                () -> service().verify("1234567890", "20200101", "홍길동")).code);

        // 규격에 없는 valid 값도 통과시키지 않는다. 모르는 값은 "아니오"다.
        response.set("{\"status_code\":\"OK\",\"data\":[{\"valid\":\"99\"}]}");
        assertEquals("BUSINESS_NOT_VERIFIED", assertThrows(AppException.class,
                () -> service().verify("1234567890", "20200101", "홍길동")).code);
    }

    @Test void verificationIsSkippedEntirelyWhenTurnedOff() {
        BusinessRegistryService off = new BusinessRegistryService(false, "");
        assertFalse(off.enabled());
        // 꺼져 있으면 네트워크에 나가지 않는다. 나갔다면 위의 가짜 서버가 기록을 남겼을 것이다.
        lastRequestBody.set(null);
        assertDoesNotThrow(() -> off.verify("0000000000", "", ""));
        assertNull(lastRequestBody.get());
    }

    @Test void turningItOnWithoutAKeyStopsStartup() {
        // 키 없이 켜면 조용히 통과하는 것이 최악이다. 기동을 막아 배포 시점에 드러나게 한다.
        assertThrows(IllegalStateException.class, () -> new BusinessRegistryService(true, "  "));
    }

    @Test void openingDatesAreNormalisedAndCheckedBeforeTheCallIsMade() {
        assertEquals("20200101", Inputs.openingDate("2020-01-01"));
        assertEquals("20200101", Inputs.openingDate("2020.01.01"));
        for (String bad : new String[] { "", "2020", "20201301", "20200132", "18991231", "abcd" }) {
            assertEquals("INVALID_OPENING_DATE",
                    assertThrows(AppException.class, () -> Inputs.openingDate(bad)).code, bad);
        }
    }
}
