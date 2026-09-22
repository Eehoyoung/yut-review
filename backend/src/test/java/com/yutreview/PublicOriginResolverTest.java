package com.yutreview;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class PublicOriginResolverTest {
    @Test void configuredProductionOriginOverridesRequestHost() {
        PublicOriginResolver resolver = new PublicOriginResolver("https://hanpan.sodamlabs.kr/");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/stores");
        request.setScheme("http");
        request.setServerName("attacker.example");
        request.setServerPort(80);
        assertEquals("https://hanpan.sodamlabs.kr", resolver.resolve(request));
    }

    @Test void emptyConfigurationKeepsDevelopmentRequestOrigin() {
        PublicOriginResolver resolver = new PublicOriginResolver("");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/stores");
        request.setScheme("http");
        request.setServerName("localhost");
        request.setServerPort(8088);
        assertEquals("http://localhost:8088", resolver.resolve(request));
    }

    @Test void configuredOriginRejectsPathsAndNonHttpSchemes() {
        assertThrows(IllegalArgumentException.class, () -> new PublicOriginResolver("https://hanpan.sodamlabs.kr/admin"));
        assertThrows(IllegalArgumentException.class, () -> new PublicOriginResolver("javascript:alert(1)"));
    }
}
