package com.yutreview;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest @AutoConfigureMockMvc @Transactional
class MarketingConsentTest {
    @Autowired AdminSignupService signup;
    @Autowired AdminUserRepository admins;
    @Autowired MarketingConsentEventRepository events;
    @Autowired MarketingConsentService consents;
    @Autowired JwtService jwt;
    @Autowired MockMvc mvc;

    @Test void signupRecordsThreeIndependentChoicesAndWithdrawalIsAppendOnly() throws Exception {
        signup.signUp(new AdminSignupService.Request("secret1234","secret1234","marketing@test.com","홍대표","01022223333","마케팅상회","1234567890",true,LegalConsentPolicy.TERMS_VERSION,true,LegalConsentPolicy.ADMIN_PRIVACY_VERSION,LegalConsentPolicy.MARKETING_SMS_VERSION,true,false,false));
        AdminUser admin=admins.findByEmail("marketing@test.com").orElseThrow();
        assertEquals(3,events.findByAdminIdOrderByChangedAtDescIdDesc(admin.id).size());
        assertTrue(consents.maySend(admin.id,MarketingService.YUT_REVIEW));
        assertFalse(consents.maySend(admin.id,MarketingService.REVIEW_PILOT));

        String token="Bearer "+jwt.issue(admin);
        mvc.perform(get("/api/admin/marketing-consents").header("Authorization",token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(3))
            .andExpect(jsonPath("$.data[0].service").value("YUT_REVIEW")).andExpect(jsonPath("$.data[0].agreed").value(true));
        mvc.perform(put("/api/admin/marketing-consents").header("Authorization",token).contentType(MediaType.APPLICATION_JSON)
            .content("{\"service\":\"YUT_REVIEW\",\"agreed\":false,\"version\":\"2026-09-23\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.agreed").value(false));
        assertFalse(consents.maySend(admin.id,MarketingService.YUT_REVIEW));
        assertEquals(4,events.findByAdminIdOrderByChangedAtDescIdDesc(admin.id).size(),"철회는 기존 동의 증적을 덮어쓰지 않는다");
    }

    @Test void staleConsentTextCannotBeAccepted() {
        signup.signUp(new AdminSignupService.Request("secret1234","secret1234","stale@test.com","김대표","01044445555","버전상회","1234567891"));
        AdminUser admin=admins.findByEmail("stale@test.com").orElseThrow();
        AppException error=assertThrows(AppException.class,()->consents.update(admin,MarketingService.SODAM,true,"old"));
        assertEquals("MARKETING_CONSENT_VERSION_INVALID",error.code);
    }
}
