package com.yutreview;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * prod는 actuator를 관리 포트(8081)로 분리하고 그 포트만 호스트 루프백에 게시한다(운영 콘솔 연동, 2026-09-26).
 * prod 프로필은 운영 비밀값이 있어야 떠서, 같은 management 설정을 속성으로 넣어 확인한다.
 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "management.server.port=0",
        "management.endpoints.web.exposure.include=health,metrics"})
class ManagementPortTest {
    @LocalServerPort int apiPort;
    @LocalManagementPort int managementPort;
    private final HttpClient http=HttpClient.newHttpClient();

    @Test void actuatorLivesOnlyOnManagementPortAndStaysAnonymous() throws Exception {
        assertNotEquals(apiPort,managementPort);
        assertEquals(200,get(managementPort,"/actuator/health"));
        assertEquals(200,get(managementPort,"/actuator/metrics"));
        assertEquals(200,get(managementPort,"/actuator/metrics/jvm.memory.used"));
        // API 포트에는 actuator가 없다. 인증 없는 요청이라 보안 체인이 먼저 막아도(401) 노출이 아니다.
        int onApi=get(apiPort,"/actuator/metrics");
        assertTrue(onApi==404||onApi==401,"api port actuator: "+onApi);
        assertNotEquals(200,get(apiPort,"/actuator/health"));
    }

    @Test void productionPinsManagementPort() throws Exception {
        List<PropertySource<?>> sources=new YamlPropertySourceLoader().load(
                "application-prod",new ClassPathResource("application-prod.yml"));
        assertEquals(8081,sources.stream().map(s->s.getProperty("management.server.port"))
                .filter(java.util.Objects::nonNull).findFirst().orElseThrow());
    }

    private int get(int port,String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
