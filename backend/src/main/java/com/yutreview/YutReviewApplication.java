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
