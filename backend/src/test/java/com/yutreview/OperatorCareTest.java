package com.yutreview;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 운영자가 사장을 대신해 하는 일(정보 수정·결제일 연기·보상 등급·통계 추출)과 사업 지표.
 * 모든 쓰기는 사유가 있어야 하고 기록이 남으며, 매장 운영 설정에는 닿지 않는다.
 */
@SpringBootTest @AutoConfigureMockMvc
class OperatorCareTest {
    @Autowired MockMvc mvc; @Autowired AdminSignupService signup; @Autowired AdminUserRepository admins;
    @Autowired StoreRepository stores; @Autowired StoreSubscriptionRepository subscriptions; @Autowired SubscriptionService plans;
    @Autowired SubscriptionPaymentRepository payments; @Autowired StoreCareEventRepository careEvents;
    @Autowired PlatformDailyMetricRepository snapshots; @Autowired ServiceAccessPolicy policy;
    @Autowired JwtService jwt; @Autowired PasswordEncoder encoder; @Autowired Clock clock;

    private record Fixture(Long storeId,String op,String owner){}
    private Fixture fixture(String key,String businessNumber){
        var p=signup.signUp(new AdminSignupService.Request("secret12345","secret12345",key+"@care.test","보살핌대표","01066660000",
            key+"상회",businessNumber),"https://example.test","203.0.113."+(Math.abs(key.hashCode())%200+20));
        AdminUser op=new AdminUser();op.email=key+"-op@care.test";op.passwordHash=encoder.encode("secret1234");op.name="운영자";
        op.role=AdminRole.OPERATOR;op.createdAt=clock.instant();admins.save(op);
        return new Fixture(p.store().id,"Bearer "+jwt.issueOperator(op),"Bearer "+jwt.issue(admins.findByEmail(key+"@care.test").orElseThrow()));
    }
    private StoreSubscription sub(Long storeId){return subscriptions.findByStoreId(storeId).orElseThrow();}

    @Test void operatorEditsContactInfoWithAReasonAndLeavesTheTaxValuesAlone() throws Exception {
        Fixture f=fixture("profile","5552220001");
        String before=stores.findById(f.storeId()).orElseThrow().businessNumber;
        // 사유 없이는 못 바꾼다.
        mvc.perform(put("/api/operator/stores/{id}/profile",f.storeId()).header("Authorization",f.op()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"address\":\"서울 중구 을지로 1\"}")).andExpect(status().isBadRequest());
        mvc.perform(put("/api/operator/stores/{id}/profile",f.storeId()).header("Authorization",f.op()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"새상호\",\"phone\":\"02-123-4567\",\"address\":\"서울 중구 을지로 1\",\"businessNumber\":\"1112223333\",\"reason\":\"사장님 전화 요청(상호 변경)\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.changed.length()").value(3));
        Store s=stores.findById(f.storeId()).orElseThrow();
        assertEquals("새상호",s.name);assertEquals("021234567",s.phone);assertEquals("서울 중구 을지로 1",s.address);
        assertEquals(before,s.businessNumber);
        // 같은 값이면 바뀐 것이 없다고 말한다(빈 기록을 남기지 않는다).
        mvc.perform(put("/api/operator/stores/{id}/profile",f.storeId()).header("Authorization",f.op()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"새상호\",\"reason\":\"재전송\"}")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("NO_CHANGE"));
        // 사장 토큰은 운영자 주소에 닿지 못한다.
        mvc.perform(get("/api/operator/stores/{id}/care",f.storeId()).header("Authorization",f.owner())).andExpect(status().isUnauthorized());

        mvc.perform(get("/api/operator/stores/{id}/care",f.storeId()).header("Authorization",f.op()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.store.name").value("새상호"))
            .andExpect(jsonPath("$.data.careLog[0].action").value("PROFILE_UPDATED"))
            .andExpect(jsonPath("$.data.careLog[0].reason").value("사장님 전화 요청(상호 변경)"));
        mvc.perform(get("/api/operator/audit").header("Authorization",f.op()))
            .andExpect(jsonPath("$.data[?(@.kind=='SUPPORT' && @.target=='새상호')]").exists());
        // 운영자 검색으로 찾는다(상호 일부, 사업자등록번호 일부).
        mvc.perform(get("/api/operator/stores").param("q","새상").header("Authorization",f.op())).andExpect(jsonPath("$.data.content[0].id").value(f.storeId()));
        mvc.perform(get("/api/operator/stores").param("q","555-2220-001").header("Authorization",f.op())).andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test void postponingBillingMovesTheTrialTooAndLiftsARestriction() throws Exception {
        Fixture f=fixture("postpone","5552220002");
        StoreSubscription s=sub(f.storeId());
        Instant next=s.nextBillingAt,trial=s.trialEndsAt;
        assertNotNull(next);assertNotNull(trial);
        mvc.perform(post("/api/operator/stores/{id}/billing/postpone",f.storeId()).header("Authorization",f.op()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"days\":7,\"reason\":\"결제창 장애 보상\"}")).andExpect(status().isOk());
        s=sub(f.storeId());
        assertEquals(next.plus(Duration.ofDays(7)),s.nextBillingAt);assertEquals(trial.plus(Duration.ofDays(7)),s.trialEndsAt);

        // 이용 제한 중인 매장: 결제예정일이 열흘 전이다. 연기하면 그 자리에서 풀린다.
        s.nextBillingAt=clock.instant().minus(Duration.ofDays(10));s.trialEndsAt=null;s.renewalFailures=3;subscriptions.save(s);
        assertEquals(ServiceState.RESTRICTED,policy.state(f.storeId()));
        mvc.perform(post("/api/operator/stores/{id}/billing/postpone",f.storeId()).header("Authorization",f.op()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"days\":14,\"reason\":\"운영 실수 보상\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.data.serviceState").value("ACTIVE"));
        assertEquals(0,sub(f.storeId()).renewalFailures);

        // 결제가 진행 중(잠금)이면 날짜를 옮기지 않는다.
        s=sub(f.storeId());s.billingLockUntil=clock.instant().plus(Duration.ofMinutes(1));subscriptions.save(s);
        mvc.perform(post("/api/operator/stores/{id}/billing/postpone",f.storeId()).header("Authorization",f.op()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"days\":1,\"reason\":\"테스트\"}")).andExpect(status().isConflict());
        s=sub(f.storeId());s.billingLockUntil=null;s.nextBillingAt=null;subscriptions.save(s);
        // 결제 대상이 아닌 매장(결제예정일 없음)은 연기할 날짜가 없다.
        mvc.perform(post("/api/operator/stores/{id}/billing/postpone",f.storeId()).header("Authorization",f.op()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"days\":1,\"reason\":\"테스트\"}")).andExpect(jsonPath("$.error.code").value("NOT_BILLING_TARGET"));
        mvc.perform(post("/api/operator/stores/{id}/billing/postpone",f.storeId()).header("Authorization",f.op()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"days\":91,\"reason\":\"테스트\"}")).andExpect(status().isBadRequest());
    }

    @Test void complimentaryPlanOpensFeaturesButNeverChangesWhatIsBilled() throws Exception {
        Fixture f=fixture("comp","5552220003");
        StoreSubscription s=sub(f.storeId());s.plan=Plan.BASIC;s.trialEndsAt=null;subscriptions.save(s);
        assertEquals(Plan.BASIC,plans.planOf(f.storeId()));
        // 사장 CSV는 BASIC이라 막혀 있다.
        mvc.perform(get("/api/admin/stores/{id}/analytics/export/daily",f.storeId()).header("Authorization",f.owner())).andExpect(status().isPaymentRequired());

        mvc.perform(post("/api/operator/stores/{id}/complimentary",f.storeId()).header("Authorization",f.op()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"plan\":\"PRO\",\"days\":7,\"reason\":\"AI 장애 보상\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.effectivePlan").value("PRO")).andExpect(jsonPath("$.data.billingPlan").value("BASIC"));
        assertEquals(Plan.PRO,plans.planOf(f.storeId()));assertEquals(Plan.BASIC,sub(f.storeId()).plan);
        mvc.perform(get("/api/admin/stores/{id}/subscription",f.storeId()).header("Authorization",f.owner()))
            .andExpect(jsonPath("$.data.plan").value("PRO")).andExpect(jsonPath("$.data.complimentaryPlan").value("PRO")).andExpect(jsonPath("$.data.billingPlan").value("BASIC"));
        mvc.perform(get("/api/admin/stores/{id}/analytics/export/daily",f.storeId()).header("Authorization",f.owner())).andExpect(status().isOk());

        // 같거나 낮은 등급은 보상이 아니다.
        mvc.perform(post("/api/operator/stores/{id}/complimentary",f.storeId()).header("Authorization",f.op()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"plan\":\"BASIC\",\"days\":7,\"reason\":\"x\"}")).andExpect(jsonPath("$.error.code").value("COMPLIMENTARY_NOT_HIGHER"));
        mvc.perform(post("/api/operator/stores/{id}/complimentary/end",f.storeId()).header("Authorization",f.op()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"사장님 요청으로 종료\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.data.effectivePlan").value("BASIC"));
        assertEquals(Plan.BASIC,plans.planOf(f.storeId()));

        // 기간이 지나면 저절로 끝난다.
        s=sub(f.storeId());s.complimentaryPlan=Plan.STANDARD;s.complimentaryUntil=clock.instant().minusSeconds(1);subscriptions.save(s);
        assertEquals(Plan.BASIC,plans.planOf(f.storeId()));
        assertEquals(2,careEvents.findTop100ByStoreIdOrderByCreatedAtDescIdDesc(f.storeId()).size());
    }

    @Test void operatorExportsStatsOnAnyPlanAndItIsRecorded() throws Exception {
        Fixture f=fixture("stats","5552220004");
        StoreSubscription s=sub(f.storeId());s.plan=Plan.BASIC;s.trialEndsAt=null;subscriptions.save(s);
        LocalDate today=LocalDate.now(clock);
        mvc.perform(get("/api/operator/stores/{id}/report/export/daily",f.storeId()).param("from",today.minusDays(6).toString())
                .param("to",today.toString()).param("reason","사장님 세무 자료 요청").header("Authorization",f.op()))
            .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("text/csv"));
        mvc.perform(get("/api/operator/stores/{id}/report",f.storeId()).header("Authorization",f.op()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.summary.plays").value(0));
        var log=careEvents.findTop100ByStoreIdOrderByCreatedAtDescIdDesc(f.storeId());
        assertEquals(StoreCareAction.STATS_EXPORTED,log.get(0).action);assertEquals("사장님 세무 자료 요청",log.get(0).reason);
    }

    @Test void ledgerAndPlatformMetricsCountPaymentsAndFillSnapshots() throws Exception {
        Fixture f=fixture("metrics","5552220005");
        Store store=stores.findById(f.storeId()).orElseThrow();
        Instant now=clock.instant();
        payment(store,"care-paid-"+System.nanoTime(),SubscriptionPaymentStatus.PAID,19900,now);
        payment(store,"care-fail-"+System.nanoTime(),SubscriptionPaymentStatus.FAILED,19900,null);

        mvc.perform(get("/api/operator/payments").param("status","FAILED").param("q","metrics").header("Authorization",f.op()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1))
            .andExpect(jsonPath("$.data.content[0].storeName").value("metrics상회"));
        mvc.perform(get("/api/operator/metrics").header("Authorization",f.op()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.monthly.length()").value(12))
            .andExpect(jsonPath("$.data.customers.mau").isNumber())
            .andExpect(jsonPath("$.data.stores.total").isNumber());
        assertTrue(snapshots.existsByMetricDate(LocalDate.now(clock).minusDays(1)));
        assertNotNull(snapshots.findByMetricDate(LocalDate.now(clock).minusDays(1)).orElseThrow().mrrKrw);
        mvc.perform(get("/api/operator/metrics/monthly.csv").header("Authorization",f.op())).andExpect(status().isOk());
        mvc.perform(get("/api/operator/metrics").header("Authorization",f.owner())).andExpect(status().isUnauthorized());
    }

    private void payment(Store store,String id,SubscriptionPaymentStatus status,int amount,Instant paidAt){
        SubscriptionPayment p=new SubscriptionPayment();
        p.store=store;p.paymentId=id;p.plan=Plan.PRO;p.amount=amount;p.status=status;p.createdAt=clock.instant();p.paidAt=paidAt;
        payments.save(p);
    }
}
