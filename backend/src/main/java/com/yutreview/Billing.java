package com.yutreview;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

/**
 * 포트원 V2 REST 호출. 빌링키 조회·삭제와 빌링키 결제만 쓴다.
 *
 * 카드 정보는 PG 결제창에서 바로 PG로 가고, 우리는 빌링키(대리값)만 받는다. 카드 번호를 받는
 * API 빌링키 발급은 쓰지 않는다 — 그 순간 카드 정보가 우리 서버를 지나게 된다.
 *
 * 응답 본문을 로그에 남기지 말 것. 결제 오류 본문에 고객 이름·연락처가 되돌아올 수 있다.
 */
@Service class PortOneClient {
    private static final Logger log=LoggerFactory.getLogger(PortOneClient.class);
    private static final Duration TIMEOUT=Duration.ofSeconds(20);
    private final String apiBase;private final String secret;private final String storeId;
    private final Map<String,String> channels=new LinkedHashMap<>();
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper json=new ObjectMapper();

    PortOneClient(@Value("${app.portone.api-base:https://api.portone.io}") String apiBase,
        @Value("${app.portone.api-secret:}") String secret,@Value("${app.portone.store-id:}") String storeId,
        @Value("${app.portone.channels.tosspayments:}") String toss,@Value("${app.portone.channels.inicis:}") String inicis){
        this.apiBase=apiBase;this.secret=secret.trim();this.storeId=storeId.trim();
        if(!toss.isBlank())channels.put(toss.trim(),"TOSSPAYMENTS");
        if(!inicis.isBlank())channels.put(inicis.trim(),"INICIS");
    }

    /** 키가 하나라도 비면 결제를 받지 않는다. 설정 실수가 결제 없는 등급 상승으로 새지 않게 한다. */
    boolean enabled(){return !secret.isEmpty()&&!storeId.isEmpty()&&!channels.isEmpty();}
    String storeId(){return storeId;}
    /** channelKey → PG 이름. 화면이 결제창을 띄울 때 쓰는 목록이자 빌링키 검증의 허용 목록이다. */
    Map<String,String> channels(){return channels;}

    JsonNode billingKey(String billingKey){
        Reply r=send("GET","/billing-keys/"+enc(billingKey)+"?storeId="+enc(storeId),null);
        if(r.status==404)throw new AppException("BILLING_KEY_INVALID","등록한 결제수단을 찾지 못했어요. 다시 등록해 주세요.");
        if(r.status!=200)throw unavailable();
        return r.body;
    }

    void deleteBillingKey(String billingKey){
        try{send("DELETE","/billing-keys/"+enc(billingKey)+"?storeId="+enc(storeId),null);}
        catch(AppException e){log.warn("portone billing key delete failed");}
    }

    enum Outcome{PAID,DECLINED,UNKNOWN}
    record Charge(Outcome outcome,String reason){}

    Charge pay(String paymentId,String billingKey,String orderName,int amount,Map<String,Object> customer){
        Map<String,Object> body=new LinkedHashMap<>();
        body.put("storeId",storeId);body.put("billingKey",billingKey);body.put("orderName",orderName);
        body.put("amount",Map.of("total",amount));body.put("currency","KRW");body.put("customer",customer);
        Reply r;
        try{r=send("POST","/payments/"+enc(paymentId)+"/billing-key",body);}
        catch(AppException e){return confirm(paymentId);}
        if(r.status==200)return new Charge(Outcome.PAID,null);
        String type=r.body.path("type").asText("");
        // 같은 paymentId로 다시 부르면 여기로 온다. 앞선 호출이 결제됐다는 뜻이다.
        if("ALREADY_PAID".equals(type))return new Charge(Outcome.PAID,null);
        if(r.status>=500&&!"PG_PROVIDER".equals(type))return confirm(paymentId);
        return new Charge(Outcome.DECLINED,declineReason(r.body,type));
    }

    /** 카드사 거절 사유는 pgMessage에 온다(예: "[1254][실시간빌링실패|잔액부족]"). message는 비어 있을 때가 있다. */
    private static String declineReason(JsonNode body,String fallback){
        for(String field:new String[]{"pgMessage","message"}){
            String v=body.path(field).asText("");
            if(!v.isBlank())return shortReason(v);
        }
        return "카드사에서 승인하지 않았어요("+fallback+")";
    }

    /** 응답을 못 받았으면 결제됐는지 되물어 본다. 그래도 모르면 UNKNOWN으로 두고 사람이 대조한다. */
    private Charge confirm(String paymentId){
        try{
            Reply r=send("GET","/payments/"+enc(paymentId)+"?storeId="+enc(storeId),null);
            String status=r.body.path("status").asText("");
            if("PAID".equals(status))return new Charge(Outcome.PAID,null);
            if("FAILED".equals(status))return new Charge(Outcome.DECLINED,shortReason(r.body.path("failure").path("reason").asText("결제 실패")));
        }catch(AppException ignored){}
        return new Charge(Outcome.UNKNOWN,null);
    }

    private record Reply(int status,JsonNode body){}

    private Reply send(String method,String path,Object body){
        try{
            HttpRequest.Builder b=HttpRequest.newBuilder(URI.create(apiBase+path)).timeout(TIMEOUT)
                .header("Authorization","PortOne "+secret).header("Content-Type","application/json");
            b.method(method,body==null?HttpRequest.BodyPublishers.noBody()
                :HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body),StandardCharsets.UTF_8));
            HttpResponse<String> res=http.send(b.build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if(res.statusCode()>=400)log.warn("portone {} returned {}",method,res.statusCode());
            String text=res.body();
            return new Reply(res.statusCode(),text==null||text.isBlank()?json.createObjectNode():json.readTree(text));
        }catch(Exception e){
            log.warn("portone call failed: {}",e.getClass().getSimpleName());
            throw unavailable();
        }
    }

    private static String shortReason(String s){return s.length()>200?s.substring(0,200):s;}
    private static String enc(String s){return URLEncoder.encode(s,StandardCharsets.UTF_8);}
    static AppException unavailable(){
        return new AppException("BILLING_UNAVAILABLE","결제 서비스에 연결하지 못했어요. 잠시 후 다시 시도해 주세요.",HttpStatus.SERVICE_UNAVAILABLE);
    }
}

/** 매장이 지금 서비스를 받는 상태. 저장하지 않고 날짜에서 계산한다 — 스케줄러가 늦어도 판정이 밀리지 않는다. */
enum ServiceState { OPEN, TRIAL, ACTIVE, GRACE, RESTRICTED }

/**
 * 결제 상태로 매장 이용을 여닫는 유일한 자리.
 *
 * 결제예정일(D)이 지나도 D+2 23:59:59(KST)까지는 서비스하고, D+3 00:00부터 막는다. 막히면 사장
 * 화면은 요금제·결제 API만, 손님 화면은 아무것도 쓸 수 없다(게임·쿠폰 조회·쿠폰 사용). 데이터는
 * 지우지 않는다 — 결제하면 그대로 돌아온다.
 *
 * `nextBillingAt`이 없는 매장은 결제 대상이 아니어서 막히지 않는다. 2026-09-27 이전에 가입한
 * 매장이 여기에 해당하며, 사용자 결정으로 그대로 둔다.
 */
@Service class ServiceAccessPolicy {
    static final int GRACE_DAYS=2;
    private final StoreSubscriptionRepository subscriptions;private final Clock clock;
    ServiceAccessPolicy(StoreSubscriptionRepository subscriptions,Clock clock){this.subscriptions=subscriptions;this.clock=clock;}

    /** D+3 00:00(KST). 이 시각부터 이용 제한이다. */
    Instant restrictedFrom(Instant nextBillingAt){
        return nextBillingAt.atZone(clock.getZone()).toLocalDate().plusDays(GRACE_DAYS+1).atStartOfDay(clock.getZone()).toInstant();
    }

    ServiceState state(Long storeId){return subscriptions.findByStoreId(storeId).map(this::state).orElse(ServiceState.OPEN);}

    ServiceState state(StoreSubscription s){
        Instant now=clock.instant();
        if(s.nextBillingAt==null)return ServiceState.OPEN;
        if(now.isBefore(s.nextBillingAt))return s.trialEndsAt!=null&&now.isBefore(s.trialEndsAt)?ServiceState.TRIAL:ServiceState.ACTIVE;
        return now.isBefore(restrictedFrom(s.nextBillingAt))?ServiceState.GRACE:ServiceState.RESTRICTED;
    }

    boolean restricted(Long storeId){return state(storeId)==ServiceState.RESTRICTED;}

    /** 손님 경로. 결제 얘기를 손님에게 하지 않는다 — 안내 화면(StoreIntro)도 같은 원칙이다. */
    void requireOpenForCustomer(Long storeId){
        if(restricted(storeId))
            throw new AppException("STORE_PAYMENT_REQUIRED","지금은 이 매장의 이벤트를 이용할 수 없어요. 매장 직원에게 알려 주세요.",HttpStatus.FORBIDDEN);
    }

    /** 사장 경로. 화면이 이 코드를 보고 결제 안내로 보낸다. */
    void requireOpenForOwner(Long storeId){
        if(restricted(storeId))
            throw new AppException("SUBSCRIPTION_PAYMENT_REQUIRED","결제가 확인되지 않아 이용이 중지됐어요. 요금제 화면에서 결제수단을 확인해 주세요.",HttpStatus.PAYMENT_REQUIRED);
    }
}

/**
 * 막힌 매장의 사장 API를 한곳에서 끊는다. 요금제·결제 경로와 매장 상세 조회(`GET /stores/{id}`,
 * 화면이 `serviceSuspended`를 읽어 안내를 띄운다)만 열어 둔다 — 그래야 결제해서 풀 수 있다.
 * 컨트롤러마다 검사를 넣으면 새 엔드포인트가 생길 때마다 빠뜨린다.
 */
@org.springframework.context.annotation.Configuration class ServiceAccessWebConfig implements org.springframework.web.servlet.config.annotation.WebMvcConfigurer {
    private static final java.util.regex.Pattern STORE=java.util.regex.Pattern.compile("^/api/admin/stores/(\\d+)(/.*)?$");
    private final ServiceAccessPolicy policy;
    ServiceAccessWebConfig(ServiceAccessPolicy policy){this.policy=policy;}
    @Override public void addInterceptors(org.springframework.web.servlet.config.annotation.InterceptorRegistry registry){
        registry.addInterceptor(new org.springframework.web.servlet.HandlerInterceptor(){
            @Override public boolean preHandle(jakarta.servlet.http.HttpServletRequest req,jakarta.servlet.http.HttpServletResponse res,Object handler){
                java.util.regex.Matcher m=STORE.matcher(req.getRequestURI());
                if(!m.matches())return true;
                String rest=m.group(2)==null?"":m.group(2);
                if(rest.startsWith("/billing")||rest.startsWith("/subscription"))return true;
                if(rest.isEmpty()&&"GET".equals(req.getMethod()))return true;
                policy.requireOpenForOwner(Long.valueOf(m.group(1)));
                return true;
            }
        }).addPathPatterns("/api/admin/stores/**");
    }
}

/**
 * 요금제 자동결제.
 *
 * 흐름: 화면이 PG 결제창으로 빌링키를 받는다 → 여기서 빌링키가 이 매장 것인지 확인하고 청구한다 →
 * 매일 00:10 결제예정일(KST 날짜)이 된 매장을 청구한다. 성공하면 최근 결제일과 다음 결제일을 넘긴다.
 * 실패하면 D+2까지 매일 다시 시도하고, 그 뒤로는 {@link ServiceAccessPolicy}가 이용을 막는다.
 *
 * 되돌리면 안 되는 지점:
 * - PG 호출을 `@Transactional` 안에 넣지 말 것. 응답을 기다리는 동안 커넥션을 붙든다.
 *   쓰기는 {@link #writes}로 짧게 감싼다.
 * - PG를 부르기 전에 PENDING 행을 먼저 남길 것. 결제 후 기록이 실패해도 흔적이 남는다.
 * - 청구 전 매장 단위 잠금(조건부 UPDATE)을 뺄 것. 두 번 누르면 두 번 결제된다.
 * - 정기 갱신은 결제예정일을 기준으로 한 달씩 민다(결제가 하루 늦어도 주기는 그대로). 유예 기간에도
 *   서비스를 받았기 때문이다. 이용 제한 뒤 결제하면 그날부터 새 주기다.
 */
@Service class BillingService {
    private static final Duration LOCK=Duration.ofMinutes(2);
    private final PortOneClient portone;private final StoreSubscriptionRepository subscriptions;
    private final SubscriptionPaymentRepository payments;private final StoreRepository stores;
    private final MembershipRepository memberships;private final AdminUserRepository admins;
    private final ServiceAccessPolicy policy;private final Clock clock;private final TransactionTemplate writes;

    BillingService(PortOneClient portone,StoreSubscriptionRepository subscriptions,SubscriptionPaymentRepository payments,
        StoreRepository stores,MembershipRepository memberships,AdminUserRepository admins,ServiceAccessPolicy policy,Clock clock,PlatformTransactionManager tx){
        this.portone=portone;this.subscriptions=subscriptions;this.payments=payments;this.stores=stores;
        this.memberships=memberships;this.admins=admins;this.policy=policy;this.clock=clock;this.writes=new TransactionTemplate(tx);
    }

    static String customerId(Long storeId){return "store-"+storeId;}

    /** 결제는 매장 대표만. 매니저가 대표 카드로 등급을 올리는 일을 막는다. */
    AdminUser requireOwner(Long adminId,Long storeId){
        boolean owner=memberships.findByStoreId(storeId).stream()
            .anyMatch(m->m.admin.id.equals(adminId)&&m.role==MembershipRole.OWNER);
        if(!owner)throw new AppException("FORBIDDEN","결제는 매장 대표 계정만 할 수 있어요.",HttpStatus.FORBIDDEN);
        return admins.findById(adminId).orElseThrow();
    }

    /**
     * 결제수단을 등록하고 요금제를 산다.
     * - 무료체험 중: 카드만 등록한다. 체험 종료일(= 첫 결제예정일)에 고른 등급으로 청구한다.
     * - 결제 기간 중 같은 등급: 카드만 바꾼다. 더 낮은 등급: 다음 결제예정일부터 그 등급으로 청구한다.
     * - 결제 기간 중 더 높은 등급, 첫 결제, 유예·제한 중: 지금 청구한다.
     */
    StoreSubscription checkout(Long adminId,Long storeId,Plan plan,String billingKey){
        AdminUser owner=requireOwner(adminId,storeId);
        if(!portone.enabled())throw PortOneClient.unavailable();
        String channelKey=verifyBillingKey(storeId,billingKey);
        lock(storeId);
        try{
            StoreSubscription s=subscriptions.findByStoreId(storeId).orElseThrow();
            Instant now=clock.instant();
            ServiceState state=policy.state(s);
            boolean paidPeriod=state==ServiceState.ACTIVE&&s.lastPaidAt!=null;
            if(state==ServiceState.TRIAL||(paidPeriod&&plan.ordinal()<=s.plan.ordinal())){
                String old=s.billingKey;
                StoreSubscription saved=writes.execute(t->{
                    StoreSubscription row=subscriptions.findByStoreId(storeId).orElseThrow();
                    row.billingKey=billingKey;row.billingChannelKey=channelKey;row.autoRenew=true;
                    row.nextPlan=state==ServiceState.TRIAL||plan!=row.plan?plan:null;row.updatedAt=now;
                    return subscriptions.save(row);
                });
                if(old!=null&&!old.equals(billingKey))portone.deleteBillingKey(old);
                return saved;
            }
            // 유예 중 결제는 원래 주기를 잇는다. 올리기·첫 결제·제한 뒤 결제는 오늘부터 새 주기다.
            // ponytail: 올릴 때 남은 기간을 환산하지 않는다. 일할 계산은 요청이 생기면.
            Instant next=state==ServiceState.GRACE?oneMonthFrom(s.nextBillingAt):oneMonthFrom(now);
            String paymentId="sub-"+storeId+"-"+UUID.randomUUID().toString().replace("-","").substring(0,20);
            PortOneClient.Charge charge=charge(storeId,paymentId,plan,billingKey,customer(storeId,owner));
            if(charge.outcome()==PortOneClient.Outcome.DECLINED){
                if(!billingKey.equals(s.billingKey))portone.deleteBillingKey(billingKey);
                throw new AppException("PAYMENT_DECLINED","결제가 승인되지 않았어요: "+charge.reason(),HttpStatus.PAYMENT_REQUIRED);
            }
            if(charge.outcome()==PortOneClient.Outcome.UNKNOWN)
                throw new AppException("PAYMENT_PENDING","결제 결과를 확인하지 못했어요. 잠시 후 요금제 화면을 다시 확인해 주세요.",HttpStatus.BAD_GATEWAY);
            String old=s.billingKey;
            StoreSubscription saved=writes.execute(t->{
                StoreSubscription row=subscriptions.findByStoreId(storeId).orElseThrow();
                row.plan=plan;row.status=SubscriptionStatus.ACTIVE;row.trialEndsAt=null;row.nextPlan=null;
                row.billingKey=billingKey;row.billingChannelKey=channelKey;row.autoRenew=true;row.renewalFailures=0;
                row.lastPaidAt=now;row.nextBillingAt=next;row.updatedAt=now;row.note=plan.name()+" 결제";
                return subscriptions.save(row);
            });
            if(old!=null&&!old.equals(billingKey))portone.deleteBillingKey(old);
            return saved;
        }finally{unlock(storeId);}
    }

    /** 자동결제를 멈춘다. 다음 결제예정일까지 쓰고, 유예 2일이 지나면 이용이 제한된다. */
    StoreSubscription setAutoRenew(Long adminId,Long storeId,boolean on){
        requireOwner(adminId,storeId);
        return writes.execute(t->{
            StoreSubscription s=subscriptions.findByStoreId(storeId)
                .filter(row->row.nextBillingAt!=null&&row.billingKey!=null)
                .orElseThrow(()->new AppException("NO_PAID_SUBSCRIPTION","등록된 결제수단이 없어요."));
            s.autoRenew=on;if(!on)s.nextPlan=null;s.updatedAt=clock.instant();
            return subscriptions.save(s);
        });
    }

    /** 결제예정일이 된 매장 하나를 청구한다. 스케줄러와 테스트가 같은 길을 쓴다. */
    void renew(Long storeId){
        try{lock(storeId);}catch(AppException busy){return;}
        try{
            StoreSubscription s=subscriptions.findByStoreId(storeId).orElse(null);
            Instant now=clock.instant();
            if(s==null||s.nextBillingAt==null)return;
            if(s.nextBillingAt.atZone(clock.getZone()).toLocalDate().isAfter(now.atZone(clock.getZone()).toLocalDate()))return;
            // 제한이 시작되면 더 시도하지 않는다. 사장이 카드를 다시 등록해 결제하면 풀린다.
            if(policy.state(s)==ServiceState.RESTRICTED)return;
            if(!Boolean.TRUE.equals(s.autoRenew)||s.billingKey==null||!portone.enabled())return;
            Plan plan=s.nextPlan!=null?s.nextPlan:s.plan;
            int failures=s.renewalFailures==null?0:s.renewalFailures;
            // 같은 예정일·같은 시도 번호면 같은 id다. 스케줄러가 두 번 돌아도 포트원이 ALREADY_PAID로 막는다.
            String paymentId="renew-"+storeId+"-"+s.nextBillingAt.getEpochSecond()+"-"+failures;
            AdminUser owner=memberships.findByStoreId(storeId).stream().filter(m->m.role==MembershipRole.OWNER)
                .map(m->m.admin).findFirst().orElse(null);
            PortOneClient.Charge charge=charge(storeId,paymentId,plan,s.billingKey,customer(storeId,owner));
            switch(charge.outcome()){
                case PAID->writes.executeWithoutResult(t->{
                    StoreSubscription row=subscriptions.findByStoreId(storeId).orElseThrow();
                    row.lastPaidAt=now;row.nextBillingAt=oneMonthFrom(row.nextBillingAt);
                    row.plan=plan;row.nextPlan=null;row.trialEndsAt=null;row.renewalFailures=0;row.updatedAt=now;
                    row.note=plan.name()+" 자동결제";
                    subscriptions.save(row);
                });
                case DECLINED->writes.executeWithoutResult(t->{
                    StoreSubscription row=subscriptions.findByStoreId(storeId).orElseThrow();
                    row.renewalFailures=failures+1;row.updatedAt=now;
                    subscriptions.save(row);
                });
                // 모르면 아무것도 바꾸지 않는다. 다음 실행에서 같은 paymentId로 다시 부르면 결과가 드러난다.
                case UNKNOWN->{}
            }
        }finally{unlock(storeId);}
    }

    List<SubscriptionPayment> history(Long storeId){return payments.findTop12ByStoreIdOrderByCreatedAtDesc(storeId);}
    PortOneClient portone(){return portone;}
    ServiceAccessPolicy policy(){return policy;}

    private PortOneClient.Charge charge(Long storeId,String paymentId,Plan plan,String billingKey,Map<String,Object> customer){
        writes.executeWithoutResult(t->{
            if(payments.findByPaymentId(paymentId).isPresent())return;
            SubscriptionPayment p=new SubscriptionPayment();
            p.store=stores.getReferenceById(storeId);p.paymentId=paymentId;p.plan=plan;p.amount=plan.monthlyPriceKrw;
            p.status=SubscriptionPaymentStatus.PENDING;p.createdAt=clock.instant();
            payments.save(p);
        });
        PortOneClient.Charge charge=portone.pay(paymentId,billingKey,"소담한판 "+plan.name()+" 월 이용료",plan.monthlyPriceKrw,customer);
        if(charge.outcome()!=PortOneClient.Outcome.UNKNOWN)writes.executeWithoutResult(t->{
            SubscriptionPayment p=payments.findByPaymentId(paymentId).orElseThrow();
            boolean paid=charge.outcome()==PortOneClient.Outcome.PAID;
            p.status=paid?SubscriptionPaymentStatus.PAID:SubscriptionPaymentStatus.FAILED;
            p.paidAt=paid?clock.instant():null;p.failureReason=paid?null:charge.reason();
            payments.save(p);
        });
        return charge;
    }

    /** 이 매장 고객 id로, 우리가 허용한 채널에서 발급된, 살아 있는 빌링키인지. 남의 빌링키를 끼워 넣는 길을 막는다. */
    private String verifyBillingKey(Long storeId,String billingKey){
        JsonNode info=portone.billingKey(billingKey);
        String channelKey=info.path("channels").path(0).path("key").asText("");
        boolean ok="ISSUED".equals(info.path("status").asText())
            &&customerId(storeId).equals(info.path("customer").path("id").asText())
            &&portone.channels().containsKey(channelKey);
        if(!ok)throw new AppException("BILLING_KEY_INVALID","이 매장에서 등록한 결제수단이 아니에요. 다시 등록해 주세요.");
        return channelKey;
    }

    private Map<String,Object> customer(Long storeId,AdminUser owner){
        Map<String,Object> c=new LinkedHashMap<>();
        c.put("id",customerId(storeId));
        if(owner!=null){
            c.put("name",Map.of("full",owner.name));c.put("email",owner.email);
            if(owner.phone!=null)c.put("phoneNumber",owner.phone);
        }
        return c;
    }

    private Instant oneMonthFrom(Instant from){return from.atZone(clock.getZone()).plusMonths(1).toInstant();}

    private void lock(Long storeId){
        Instant now=clock.instant();
        Integer got=writes.execute(t->{
            if(subscriptions.findByStoreId(storeId).isEmpty()){
                // 구독 행이 없으면 BASIC과 같다. 잠금을 걸 행이 있어야 하므로 BASIC 행을 만든다.
                StoreSubscription s=new StoreSubscription();
                s.store=stores.findById(storeId).orElseThrow();s.plan=Plan.BASIC;s.status=SubscriptionStatus.ACTIVE;
                s.startedAt=now;s.updatedAt=now;
                subscriptions.saveAndFlush(s);
            }
            return subscriptions.lockBilling(storeId,now,now.plus(LOCK));
        });
        if(got==null||got==0)throw new AppException("BILLING_BUSY","결제를 처리하고 있어요. 잠시 후 다시 확인해 주세요.",HttpStatus.CONFLICT);
    }

    private void unlock(Long storeId){writes.executeWithoutResult(t->subscriptions.unlockBilling(storeId));}
}

/** 매일 00:10(Asia/Seoul). 체험 종료(00:01) 뒤에 돈다. 오늘(KST)이 결제예정일인 매장을 모두 본다. */
@Service class BillingRenewalScheduler {
    private final StoreSubscriptionRepository subscriptions;private final BillingService billing;private final Clock clock;
    BillingRenewalScheduler(StoreSubscriptionRepository subscriptions,BillingService billing,Clock clock){
        this.subscriptions=subscriptions;this.billing=billing;this.clock=clock;
    }
    @Scheduled(cron="0 10 0 * * *",zone="Asia/Seoul")
    void run(){
        Instant tomorrow=java.time.LocalDate.now(clock).plusDays(1).atStartOfDay(clock.getZone()).toInstant();
        for(StoreSubscription s:subscriptions.findByNextBillingAtBefore(tomorrow))billing.renew(s.store.id);
    }
}

@RestController @RequestMapping("/api/admin/stores/{storeId}/billing") class BillingController {
    private final BillingService billing;private final StoreAccessService access;private final StoreSubscriptionRepository subscriptions;private final AdminUserRepository admins;
    BillingController(BillingService billing,StoreAccessService access,StoreSubscriptionRepository subscriptions,AdminUserRepository admins){
        this.billing=billing;this.access=access;this.subscriptions=subscriptions;this.admins=admins;
    }

    record Checkout(@NotNull Plan plan,@NotBlank @Size(max=200) String billingKey){}
    record AutoRenew(boolean on){}

    /** 결제창에 넘길 값과 현재 결제 상태. 화면이 채널 키를 복제해 들고 있지 않게 서버가 준다. */
    @GetMapping ApiResponse<?> view(@PathVariable Long storeId,Authentication auth){
        Long adminId=(Long)auth.getPrincipal();
        access.member(adminId,storeId);
        PortOneClient portone=billing.portone();
        AdminUser me=admins.findById(adminId).orElseThrow();
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("enabled",portone.enabled());
        out.put("portoneStoreId",portone.storeId());
        // KG이니시스 PC 결제창은 이름·연락처·이메일이 없으면 열리지 않는다. 본인 정보만 본인에게 준다.
        Map<String,Object> customer=new LinkedHashMap<>();
        customer.put("customerId",BillingService.customerId(storeId));customer.put("fullName",me.name);
        customer.put("email",me.email);customer.put("phoneNumber",me.phone==null?"":me.phone);
        out.put("customer",customer);
        out.put("channels",portone.channels().entrySet().stream().map(e->Map.of("channelKey",e.getKey(),"pg",e.getValue())).toList());
        out.put("serviceState",billing.policy().state(storeId).name());
        subscriptions.findByStoreId(storeId).ifPresent(s->{
            if(s.lastPaidAt!=null)out.put("lastPaidAt",s.lastPaidAt);
            if(s.nextBillingAt!=null){
                out.put("nextBillingAt",s.nextBillingAt);
                out.put("restrictedFrom",billing.policy().restrictedFrom(s.nextBillingAt));
            }
            out.put("hasCard",s.billingKey!=null);
            out.put("autoRenew",Boolean.TRUE.equals(s.autoRenew));
            out.put("nextPlan",s.nextPlan==null?null:s.nextPlan.name());
            out.put("pg",s.billingChannelKey==null?null:portone.channels().get(s.billingChannelKey));
            out.put("renewalFailures",s.renewalFailures==null?0:s.renewalFailures);
        });
        out.put("payments",billing.history(storeId).stream().map(p->{
            Map<String,Object> m=new LinkedHashMap<>();
            m.put("plan",p.plan.name());m.put("amount",p.amount);m.put("status",p.status.name());
            m.put("createdAt",p.createdAt);m.put("failureReason",p.failureReason==null?"":p.failureReason);
            return m;
        }).toList());
        return ApiResponse.ok(out);
    }

    @PostMapping("/checkout") ApiResponse<?> checkout(@PathVariable Long storeId,@Valid @RequestBody Checkout body,Authentication auth){
        StoreSubscription s=billing.checkout((Long)auth.getPrincipal(),storeId,body.plan(),body.billingKey().trim());
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("plan",s.plan.name());out.put("nextBillingAt",s.nextBillingAt);out.put("lastPaidAt",s.lastPaidAt);
        return ApiResponse.ok(out);
    }

    @PutMapping("/auto-renew") ApiResponse<?> autoRenew(@PathVariable Long storeId,@RequestBody AutoRenew body,Authentication auth){
        StoreSubscription s=billing.setAutoRenew((Long)auth.getPrincipal(),storeId,body.on());
        return ApiResponse.ok(Map.of("autoRenew",Boolean.TRUE.equals(s.autoRenew)));
    }
}
