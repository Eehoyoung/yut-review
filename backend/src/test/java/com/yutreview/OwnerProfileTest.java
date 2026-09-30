package com.yutreview;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 매장 관리자가 스스로 고치는 정보. 매장: 주소·전화·링크는 바꾸고 매장명·사업자 정보는 못 바꾼다.
 * 계정: 연락처·비밀번호는 현재 비밀번호를 확인한 뒤에만 바꾸고 이름·이메일은 못 바꾼다.
 */
@SpringBootTest @AutoConfigureMockMvc
class OwnerProfileTest {
    @Autowired MockMvc mvc; @Autowired AdminSignupService signup; @Autowired AdminUserRepository admins;
    @Autowired StoreRepository stores; @Autowired JwtService jwt; @Autowired PasswordEncoder encoder; @Autowired Clock clock;

    private record Owner(Long storeId,String token,String email){}
    private Owner owner(String email,String businessNumber){
        var p=signup.signUp(new AdminSignupService.Request("secret12345","secret12345",email,"프로필대표","01055550000",
            "프로필상회","555111"+businessNumber),"https://example.test","203.0.113.97");
        return new Owner(p.store().id,"Bearer "+jwt.issue(admins.findByEmail(email).orElseThrow()),email);
    }
    private String json(String... kv){StringBuilder b=new StringBuilder("{");for(int i=0;i<kv.length;i+=2){if(i>0)b.append(',');b.append('"').append(kv[i]).append("\":\"").append(kv[i+1]).append('"');}return b.append('}').toString();}

    @Test void ownerEditsAddressAndPhoneButNotTheStoreName() throws Exception {
        Owner o=owner("profile-store@test.com","0101");
        mvc.perform(put("/api/admin/stores/{id}",o.storeId()).header("Authorization",o.token()).contentType(MediaType.APPLICATION_JSON)
                .content(json("name","프로필상회","phone","02-123-4567","address","  서울시 강남구 테헤란로 1, 2층  ")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.phone").value("021234567")).andExpect(jsonPath("$.data.address").value("서울시 강남구 테헤란로 1, 2층"));
        // 매장명 없이 보내도 된다(새 화면은 매장명을 보내지 않는다).
        mvc.perform(put("/api/admin/stores/{id}",o.storeId()).header("Authorization",o.token()).contentType(MediaType.APPLICATION_JSON)
                .content(json("phone","1588-1234"))).andExpect(status().isOk()).andExpect(jsonPath("$.data.phone").value("15881234"));
        mvc.perform(put("/api/admin/stores/{id}",o.storeId()).header("Authorization",o.token()).contentType(MediaType.APPLICATION_JSON)
                .content(json("name","다른상호"))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("STORE_NAME_LOCKED"));
        mvc.perform(put("/api/admin/stores/{id}",o.storeId()).header("Authorization",o.token()).contentType(MediaType.APPLICATION_JSON)
                .content(json("phone","12345"))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_STORE_PHONE"));
        Store s=stores.findById(o.storeId()).orElseThrow();
        assertEquals("프로필상회",s.name);assertEquals("15881234",s.phone);
        // 수정 불가 항목은 보여 주기만 한다.
        mvc.perform(get("/api/admin/stores/{id}",o.storeId()).header("Authorization",o.token()))
            .andExpect(jsonPath("$.data.businessNumber").value("5551110101")).andExpect(jsonPath("$.data.address").value("서울시 강남구 테헤란로 1, 2층"));
        // 빈 주소는 지운다.
        mvc.perform(put("/api/admin/stores/{id}",o.storeId()).header("Authorization",o.token()).contentType(MediaType.APPLICATION_JSON)
                .content(json("address","  "))).andExpect(jsonPath("$.data.address").value(""));
    }

    @Test void contactPhoneChangeNeedsTheCurrentPassword() throws Exception {
        Owner o=owner("profile-phone@test.com","0102");
        mvc.perform(put("/api/admin/me/phone").header("Authorization",o.token()).contentType(MediaType.APPLICATION_JSON)
                .content(json("currentPassword","wrong-pass1","phone","01099998888")))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("CURRENT_PASSWORD_INVALID"));
        assertEquals("01055550000",admins.findByEmail(o.email()).orElseThrow().phone);
        mvc.perform(put("/api/admin/me/phone").header("Authorization",o.token()).contentType(MediaType.APPLICATION_JSON)
                .content(json("currentPassword","secret12345","phone","02-123-4567")))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_PHONE"));
        mvc.perform(put("/api/admin/me/phone").header("Authorization",o.token()).contentType(MediaType.APPLICATION_JSON)
                .content(json("currentPassword","secret12345","phone","010-9999-8888")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.phone").value("01099998888"));
        mvc.perform(get("/api/admin/me").header("Authorization",o.token())).andExpect(jsonPath("$.data.phone").value("01099998888"))
            .andExpect(jsonPath("$.data.email").value(o.email()));
    }

    @Test void passwordChangeFollowsSignupRulesAndOldPasswordStopsWorking() throws Exception {
        Owner o=owner("profile-pass@test.com","0103");
        String url="/api/admin/me/password";
        mvc.perform(put(url).header("Authorization",o.token()).contentType(MediaType.APPLICATION_JSON)
                .content(json("currentPassword","secret12345","newPassword","newpass12345","newPasswordConfirm","newpass99999")))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("PASSWORD_MISMATCH"));
        mvc.perform(put(url).header("Authorization",o.token()).contentType(MediaType.APPLICATION_JSON)
                .content(json("currentPassword","secret12345","newPassword","secret12345","newPasswordConfirm","secret12345")))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("PASSWORD_SAME"));
        mvc.perform(put(url).header("Authorization",o.token()).contentType(MediaType.APPLICATION_JSON)
                .content(json("currentPassword","secret12345","newPassword","newpass12345","newPasswordConfirm","newpass12345")))
            .andExpect(status().isOk());
        mvc.perform(post("/api/admin/auth/login").contentType(MediaType.APPLICATION_JSON).content(json("email",o.email(),"password","secret12345")))
            .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/admin/auth/login").contentType(MediaType.APPLICATION_JSON).content(json("email",o.email(),"password","newpass12345")))
            .andExpect(status().isOk());
    }

    @Test void wrongCurrentPasswordIsRateLimitedLikeLogin() throws Exception {
        Owner o=owner("profile-limit@test.com","0104");
        for(int i=0;i<LoginAttemptLimiter.PER_MINUTE;i++)
            mvc.perform(put("/api/admin/me/phone").header("Authorization",o.token()).contentType(MediaType.APPLICATION_JSON)
                    .content(json("currentPassword","wrong-pass"+i,"phone","01099998888"))).andExpect(status().isBadRequest());
        mvc.perform(put("/api/admin/me/phone").header("Authorization",o.token()).contentType(MediaType.APPLICATION_JSON)
                .content(json("currentPassword","secret12345","phone","01099998888"))).andExpect(status().isTooManyRequests());
        assertEquals("01055550000",admins.findByEmail(o.email()).orElseThrow().phone);
    }

    @Test void operatorsHaveNoPasswordToChangeHere() throws Exception {
        AdminUser op=new AdminUser();op.email="profile-op@test.com";op.passwordHash=encoder.encode("secret12345");op.name="운영자";
        op.role=AdminRole.OPERATOR;op.createdAt=clock.instant();admins.save(op);
        mvc.perform(put("/api/admin/me/phone").header("Authorization","Bearer "+jwt.issueOperator(op)).contentType(MediaType.APPLICATION_JSON)
                .content(json("currentPassword","secret12345","phone","01099998888")))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("STORE_ADMIN_ONLY"));
    }
}
