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
 * 2026-10-01 역할·주소 분리. "admin"은 매장 관리자, "operator"는 소담랩스 운영자.
 * 운영자 API는 `/api/operator/**`에만 있고 거기는 운영자 OTP 세션만 닿는다. 예전 `/api/admin/operator/**`는 없다.
 */
@SpringBootTest @AutoConfigureMockMvc
class RoleSeparationTest {
    @Autowired MockMvc mvc; @Autowired AdminSignupService signup; @Autowired AdminUserRepository admins;
    @Autowired JwtService jwt; @Autowired PasswordEncoder encoder; @Autowired Clock clock;
    @Autowired SubscriptionService subscriptions; @Autowired PrizeRepository prizes;

    @Test void operatorApiLivesOnlyUnderOperatorPath() throws Exception {
        String op="Bearer "+jwt.issueOperator(operator("sep-op1@test.com"));
        mvc.perform(get("/api/operator/summary").header("Authorization",op)).andExpect(status().isOk());
        mvc.perform(get("/api/admin/operator/summary").header("Authorization",op)).andExpect(status().isNotFound());
        mvc.perform(post("/api/admin/operator-auth/request").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"sep-op1@test.com\"}"))
            .andExpect(status().is4xxClientError());
        // 운영자 역할 값은 OPERATOR로 내려간다. 화면이 이 값으로 운영자 콘솔 링크를 띄운다.
        mvc.perform(get("/api/admin/me").header("Authorization",op)).andExpect(jsonPath("$.data.role").value("OPERATOR"));
    }

    @Test void planChangeIsOperatorOnlyAndOffTheStoreAdminPath() throws Exception {
        var p=signup.signUp(new AdminSignupService.Request("secret1234","secret1234","sep-owner1@test.com","분리대표","01055554444",
            "역할분리상회","5551110095"),"https://example.test","203.0.113.93");
        Long id=p.store().id;
        String owner="Bearer "+jwt.issue(admins.findByEmail("sep-owner1@test.com").orElseThrow());
        AdminUser opUser=operator("sep-op2@test.com");
        Plan before=subscriptions.planOf(id);
        Plan target=before==Plan.PRO?Plan.BASIC:Plan.PRO;
        String body="{\"plan\":\""+target+"\",\"note\":\"테스트\"}";

        // 매장 관리자 주소에는 더 이상 변경 API가 없다(GET만 남음).
        mvc.perform(put("/api/admin/stores/{id}/subscription",id).header("Authorization",owner).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isMethodNotAllowed());
        // 예전 사장 QR 재발급 주소도 없다.
        mvc.perform(post("/api/admin/stores/{id}/qr-codes/regenerate",id).header("Authorization",owner)).andExpect(status().isNotFound());
        // 운영자 주소는 매장 관리자 토큰으로 닿지 않는다(운영자 세션 필터).
        mvc.perform(put("/api/operator/stores/{id}/subscription",id).header("Authorization",owner).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isUnauthorized());
        // 운영자 역할이어도 비밀번호 세션 토큰(issue)은 안 된다. OTP 세션(issueOperator)만.
        mvc.perform(put("/api/operator/stores/{id}/subscription",id).header("Authorization","Bearer "+jwt.issue(opUser)).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isUnauthorized());
        assertEquals(before,subscriptions.planOf(id));

        mvc.perform(put("/api/operator/stores/{id}/subscription",id).header("Authorization","Bearer "+jwt.issueOperator(opUser))
            .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.data.plan").value(target.name()));
        assertEquals(target,subscriptions.planOf(id));
        // 조회는 매장 관리자 주소에 그대로 있다.
        mvc.perform(get("/api/admin/stores/{id}/subscription",id).header("Authorization",owner)).andExpect(status().isOk());
    }

    @Test void ownershipCannotBeHandedToAnOperator() throws Exception {
        var p=signup.signUp(new AdminSignupService.Request("secret1234","secret1234","sep-owner2@test.com","이전대표","01055556666",
            "소유권분리상회","5551110096"),"https://example.test","203.0.113.94");
        AdminUser opUser=operator("sep-op3@test.com");
        mvc.perform(post("/api/operator/stores/{id}/ownership",p.store().id).header("Authorization","Bearer "+jwt.issueOperator(opUser))
            .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"sep-op3@test.com\"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("OWNERSHIP_TO_OPERATOR"));
    }

    @Test void defaultPrizeHasNoStaffInstructionForCustomers() {
        var p=signup.signUp(new AdminSignupService.Request("secret1234","secret1234","sep-owner3@test.com","상품대표","01055557777",
            "기본상품상회","5551110097"),"https://example.test","203.0.113.95");
        assertFalse(prizes.findByStoreIdOrderByRank(p.store().id).isEmpty());
        prizes.findByStoreIdOrderByRank(p.store().id).forEach(prize->assertNull(prize.description,"손님 화면에 보이는 칸이라 관리 안내문을 넣지 않는다"));
    }

    private AdminUser operator(String email){
        AdminUser a=new AdminUser();a.email=email;a.passwordHash=encoder.encode("secret1234");a.name="운영자";
        a.role=AdminRole.OPERATOR;a.createdAt=clock.instant();return admins.save(a);
    }
}
