package com.yutreview;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.ZoneId;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication(exclude=UserDetailsServiceAutoConfiguration.class)
public class YutReviewApplication {
    public static void main(String[] args) { SpringApplication.run(YutReviewApplication.class, args); }
    @Bean Clock clock() { return Clock.system(ZoneId.of("Asia/Seoul")); }
    @Bean SecureRandom secureRandom() { return new SecureRandom(); }
}

/**
 * 운영 프로파일의 fail-closed 검사.
 *
 * 운영이 현장테스트 설정으로 뜨는 것이 이 서비스에서 가장 조용한 사고다. 시드 계정이 살아 있고
 * QR·포스터 주소가 요청 Host로 만들어지며, 그 무엇도 에러를 내지 않는다. 그래서 기동을 멈춘다.
 * 값은 메시지에 넣지 않는다.
 */
@org.springframework.context.annotation.Configuration
@org.springframework.context.annotation.Profile("prod")
class ProductionConfigGuard {
    ProductionConfigGuard(@org.springframework.beans.factory.annotation.Value("${app.public-origin:}") String publicOrigin,
                          @org.springframework.beans.factory.annotation.Value("${app.bootstrap.enabled:false}") boolean bootstrapEnabled) {
        if (!PublicOriginResolver.normalize(publicOrigin).startsWith("https://"))
            throw new IllegalStateException("APP_PUBLIC_ORIGIN must be an absolute https origin in the prod profile");
        if (bootstrapEnabled)
            throw new IllegalStateException("FIELD_TEST_BOOTSTRAP_ENABLED must be false in the prod profile");
    }
}
