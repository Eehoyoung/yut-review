package com.yutreview;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

class ProductionSecurityConfigurationTest {
    @Test void productionSecurityControlsFailClosedWhenEnvironmentVariablesAreMissing() throws IOException {
        List<PropertySource<?>> sources=new YamlPropertySourceLoader().load(
                "application-prod",new ClassPathResource("application-prod.yml"));
        assertEquals("${BUSINESS_VERIFICATION_ENABLED:true}",property(sources,"app.business-verification.enabled"));
        assertEquals("${STORE_APPROVAL_REQUIRED:true}",property(sources,"app.store-approval-required"));
        assertEquals("${OPERATOR_OTP_TTL_SECONDS:120}",property(sources,"app.operator-auth.otp-ttl-seconds"));
        assertEquals("${OPERATOR_SESSION_TTL_SECONDS:600}",property(sources,"app.operator-auth.session-ttl-seconds"));
    }

    private Object property(List<PropertySource<?>> sources,String name){
        return sources.stream().map(source->source.getProperty(name)).filter(java.util.Objects::nonNull)
                .findFirst().orElseThrow();
    }
}
