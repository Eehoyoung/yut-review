package com.yutreview;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.auth0.jwt.JWT;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties={
    "app.operator-auth.otp-ttl-seconds=120",
    "app.operator-auth.session-ttl-seconds=600"
})
@AutoConfigureMockMvc @Transactional
class OperatorOtpAuthTest {
    @Autowired OperatorOtpAuthService auth;@Autowired AdminUserRepository admins;
    @Autowired PasswordEncoder encoder;@Autowired Clock clock;@Autowired JwtService jwt;@Autowired MockMvc mvc;
    @MockitoBean OperatorOtpMailService mail;
    AdminUser operator;

    @BeforeEach void setup(){
        operator=new AdminUser();operator.email="otp-"+System.nanoTime()+"@example.com";
        operator.passwordHash=encoder.encode("secret1234");operator.name="운영자";
        operator.role=AdminRole.SYSTEM_ADMIN;operator.createdAt=clock.instant();admins.save(operator);
    }

    @Test void otpLivesTwoMinutesAndIssuesANonRenewingTenMinuteOperatorSession(){
        Map<String,Object> issued=auth.request(operator.email,"198.51.100.40");
        ArgumentCaptor<String> code=ArgumentCaptor.forClass(String.class);
        verify(mail).send(eq(operator.email),code.capture(),eq(120L));
        assertEquals(120L,issued.get("expiresInSeconds"));

        Instant before=clock.instant();
        Map<String,Object> session=auth.verify((String)issued.get("challengeToken"),code.getValue());
        String token=(String)session.get("accessToken");
        assertEquals(600L,session.get("expiresInSeconds"));
        assertEquals(operator.id,jwt.verify(token));
        assertTrue(jwt.isOperatorSession(token,operator.id));
        assertEquals("OPERATOR_EMAIL_OTP",JWT.decode(token).getClaim("session_type").asString());
        long lifetime=Duration.between(JWT.decode(token).getIssuedAtAsInstant(),JWT.decode(token).getExpiresAtAsInstant()).toSeconds();
        assertEquals(600,lifetime,"요청 활동과 무관한 고정 10분 세션이어야 한다");
        assertFalse(((Instant)session.get("expiresAt")).isBefore(before.plusSeconds(599)));

        assertEquals("OPERATOR_OTP_INVALID",assertThrows(AppException.class,
            ()->auth.verify((String)issued.get("challengeToken"),code.getValue())).code,
            "OTP는 한 번만 쓸 수 있다");
    }

    @Test void unknownAndStoreAdminEmailsDoNotReceiveAnOtp(){
        Map<String,Object> unknown=auth.request("missing-"+System.nanoTime()+"@example.com","198.51.100.41");
        assertEquals(120L,unknown.get("expiresInSeconds"));
        verifyNoInteractions(mail);

        AdminUser storeAdmin=new AdminUser();storeAdmin.email="store-"+System.nanoTime()+"@example.com";
        storeAdmin.passwordHash=encoder.encode("secret1234");storeAdmin.name="사장";
        storeAdmin.role=AdminRole.STORE_ADMIN;storeAdmin.createdAt=clock.instant();admins.save(storeAdmin);
        auth.request(storeAdmin.email,"198.51.100.42");
        verifyNoInteractions(mail);
    }

    @Test void systemAdminCannotUseTheOrdinaryPasswordLogin() throws Exception {
        mvc.perform(post("/api/admin/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\""+operator.email+"\",\"password\":\"secret1234\"}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code").value("OPERATOR_OTP_REQUIRED"));
    }

    @Test void operatorApisRejectOrdinaryJwtAndAcceptOnlyTheOtpSession() throws Exception {
        mvc.perform(get("/api/admin/operator/summary")
                .header("Authorization","Bearer "+jwt.issue(operator)))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").value("OPERATOR_SESSION_REQUIRED"));

        Map<String,Object> issued=auth.request(operator.email,"198.51.100.43");
        ArgumentCaptor<String> code=ArgumentCaptor.forClass(String.class);
        verify(mail).send(eq(operator.email),code.capture(),eq(120L));
        Map<String,Object> session=auth.verify((String)issued.get("challengeToken"),code.getValue());
        mvc.perform(get("/api/admin/operator/summary")
                .header("Authorization","Bearer "+session.get("accessToken")))
            .andExpect(status().isOk());
    }
}
