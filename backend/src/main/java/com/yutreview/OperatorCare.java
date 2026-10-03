package com.yutreview;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.TypedQuery;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

/*
 * 운영자가 매장 사장을 "대신해서" 하는 일과, 소담랩스가 사업 전체를 보는 숫자.
 *
 * 경계: 매장 운영에는 손대지 않는다. 상품·확률·등급 수·쿠폰·쿠폰 사용기한·게임 기록은 여기서
 * 읽지도 쓰지도 않는다. 예외 하나: 직원 PIN 재발급은 사장이 못 하고 여기서만 한다(2026-10-03 사용자 결정).
 * 그래도 운영자는 PIN을 보지 않는다 — 새 PIN은 사장 화면에만 보인다. 운영자가 바꾸는 것은 사장이 전화로 부탁하는 연락처성 정보(매장명·전화·주소·
 * 지도 링크)와, 소담랩스가 사장에게 지는 계약(결제일·요금제)뿐이다. 사업자등록번호·대표자명·개업일자는
 * 국세청 진위확인 값이라 운영자도 바꾸지 않는다.
 *
 * 모든 쓰기는 사유를 받아 `operator_store_actions`에 남긴다. 사장에게 "왜 결제일이 바뀌었나"를 설명할
 * 근거이자, 운영자끼리 누가 무엇을 해 줬는지 확인하는 유일한 자리다.
 */

/**
 * 운영자가 매장에 해 준 일. 새 테이블이라 enum CHECK 제약 문제(기존 테이블에 값 추가)가 없다.
 * 값을 더하면 운영 DB의 CHECK 제약이 옛 목록이라 INSERT가 실패한다. {@link AdminRoleMigration}이 기동 때
 * 이 enum 전체로 제약을 다시 걸어 준다(2026-10-03 STAFF_PIN_RESET부터).
 */
enum StoreCareAction { PROFILE_UPDATED, BILLING_POSTPONED, COMPLIMENTARY_PLAN_GRANTED, COMPLIMENTARY_PLAN_ENDED, STATS_EXPORTED, STAFF_PIN_RESET }

@Entity @Table(name="operator_store_actions",indexes={@Index(columnList="store_id,created_at"),@Index(columnList="created_at")})
class StoreCareEvent {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) Long id;
    @ManyToOne(optional=false) @JoinColumn(name="store_id") Store store;
    /** 계정이 사라져도 사건은 남아야 해서 nullable이고, 이메일과 매장명을 사건 시점 값으로 동결한다. */
    @ManyToOne @JoinColumn(name="actor_admin_user_id") AdminUser actor;
    @Column(name="actor_email",length=255) String actorEmail;
    @Column(name="store_name",length=255) String storeName;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=40) StoreCareAction action;
    @Column(nullable=false,length=200) String reason;
    /** 무엇이 어떻게 바뀌었나(이전 → 이후). 고객 개인정보는 들어가지 않는다. */
    @Column(length=1000) String detail;
    @Column(name="created_at",nullable=false) Instant createdAt;
}

interface StoreCareEventRepository extends JpaRepository<StoreCareEvent,Long> {
    List<StoreCareEvent> findTop100ByStoreIdOrderByCreatedAtDescIdDesc(Long storeId);
    List<StoreCareEvent> findTop200ByOrderByCreatedAtDescIdDesc();
    Optional<StoreCareEvent> findFirstByStoreIdAndActionOrderByCreatedAtDesc(Long storeId,StoreCareAction action);
}

/**
 * 하루치 플랫폼 지표 스냅숏.
 *
 * 고객 전화번호 해시는 120일이 지나면 비식별 처리된다({@link PrivacyCleanupService}). 그래서 "3월의 MAU"는
 * 7월에는 다시 셀 수 없다. 셀 수 있을 때 세어 두는 것이 이 테이블이다. 결제 지표(MRR·결제 매장)는 그날
 * 찍은 값만 의미가 있어서, 과거 날짜를 채울 때는 비워 둔다.
 */
@Entity @Table(name="platform_daily_metrics")
class PlatformDailyMetric {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) Long id;
    @Column(name="metric_date",nullable=false,unique=true) LocalDate metricDate;
    @Column(nullable=false) long dau; @Column(nullable=false) long wau; @Column(nullable=false) long mau;
    @Column(nullable=false) long games;
    @Column(name="coupons_issued",nullable=false) long couponsIssued;
    @Column(name="coupons_redeemed",nullable=false) long couponsRedeemed;
    @Column(nullable=false) long signups;
    /** 최근 30일 안에 게임이 1건 이상 있었던 매장 수(매장 MAU). */
    @Column(name="engaged_stores",nullable=false) long engagedStores;
    @Column(name="paying_stores") Long payingStores;
    @Column(name="mrr_krw") Long mrrKrw;
    @Column(name="created_at",nullable=false) Instant createdAt;
}

interface PlatformDailyMetricRepository extends JpaRepository<PlatformDailyMetric,Long> {
    boolean existsByMetricDate(LocalDate date);
    Optional<PlatformDailyMetric> findByMetricDate(LocalDate date);
    List<PlatformDailyMetric> findByMetricDateBetweenOrderByMetricDate(LocalDate from,LocalDate to);
}

@Service class OperatorStoreCareService {
    static final int MAX_POSTPONE_DAYS=90;
    static final int MAX_COMPLIMENTARY_DAYS=365;
    private static final Duration LOCK=Duration.ofMinutes(2);
    /** 재발급 직후 같은 요청이 또 와도(두 번 클릭, 재전송) PIN을 또 바꾸지 않는 간격. QR 재발급과 같다. */
    static final Duration STAFF_PIN_RESET_COOLDOWN=Duration.ofMinutes(10);

    private final StoreRepository stores;private final MembershipRepository memberships;
    private final StoreSubscriptionRepository subscriptions;private final SubscriptionPaymentRepository payments;
    private final StoreCareEventRepository events;private final ServiceAccessPolicy policy;
    private final OperatorOverviewService overview;private final AnalyticsService analytics;private final AiContextService context;
    private final PortOneClient portone;private final Clock clock;
    private final org.springframework.security.crypto.password.PasswordEncoder encoder;private final PhoneService crypto;private final java.security.SecureRandom random;
    OperatorStoreCareService(StoreRepository stores,MembershipRepository memberships,StoreSubscriptionRepository subscriptions,
        SubscriptionPaymentRepository payments,StoreCareEventRepository events,ServiceAccessPolicy policy,
        OperatorOverviewService overview,AnalyticsService analytics,AiContextService context,PortOneClient portone,Clock clock,
        org.springframework.security.crypto.password.PasswordEncoder encoder,PhoneService crypto,java.security.SecureRandom random){
        this.stores=stores;this.memberships=memberships;this.subscriptions=subscriptions;this.payments=payments;
        this.events=events;this.policy=policy;this.overview=overview;this.analytics=analytics;this.context=context;
        this.portone=portone;this.clock=clock;this.encoder=encoder;this.crypto=crypto;this.random=random;
    }

    record Profile(String name,String phone,String address,String naverPlaceUrl){}

    /** 매장 한 곳의 상담 화면. 매장·계정·요금제·결제·이용 숫자·지원 기록. 고객 개인정보는 없다. */
    @Transactional(readOnly=true) Map<String,Object> detail(Long storeId){
        Store s=store(storeId);
        Map<String,Object> out=new LinkedHashMap<>();
        Map<String,Object> info=new LinkedHashMap<>();
        info.put("id",s.id);info.put("name",s.name);info.put("status",s.status.name());info.put("createdAt",s.createdAt);
        info.put("businessNumber",text(s.businessNumber));info.put("representativeName",text(s.representativeName));
        info.put("openingDate",text(s.openingDate));info.put("businessVerifiedAt",s.businessVerifiedAt);
        info.put("phone",text(s.phone));info.put("address",text(s.address));info.put("naverPlaceUrl",text(s.naverPlaceUrl));
        out.put("store",info);
        out.put("members",memberships.findByStoreId(storeId).stream().map(m->{
            Map<String,Object> v=new LinkedHashMap<>();
            v.put("name",m.admin.name);v.put("email",m.admin.email);v.put("phone",text(m.admin.phone));
            v.put("role",m.role.name());v.put("since",m.createdAt);return v;}).toList());
        out.put("subscription",subscription(storeId));
        out.put("payments",payments.findByStoreIdOrderByCreatedAtDesc(storeId,PageRequest.of(0,50)).map(OperatorStoreCareService::payment).getContent());
        Map<String,Object> usage=new LinkedHashMap<>(overview.storeStats(List.of(storeId)).get(storeId));
        usage.put("last30Days",context.periodSummary(storeId,context.window(Plan.PRO,null,null)));
        out.put("usage",usage);
        out.put("careLog",events.findTop100ByStoreIdOrderByCreatedAtDescIdDesc(storeId).stream().map(OperatorStoreCareService::event).toList());
        return out;
    }

    /**
     * 매장 기본 정보 수정. 사장이 직접 못 바꾸는 매장명(인쇄물과 같아야 함)도 여기서는 바꾼다 —
     * 상호가 실제로 바뀐 경우의 창구가 운영자다. 바꾸면 인쇄물을 다시 보내야 하는지 운영자가 판단한다.
     */
    @Transactional Map<String,Object> updateProfile(AdminUser actor,Long storeId,Profile p,String reason){
        String why=reason(reason);
        Store s=stores.findForUpdate(storeId).orElseThrow(OperatorStoreCareService::notFound);
        List<String> changes=new ArrayList<>();
        if(p.name()!=null){String v=Inputs.required(p.name(),"매장명을 입력해 주세요.");if(!v.equals(s.name)){changes.add("매장명: "+s.name+" → "+v);s.name=v;}}
        if(p.phone()!=null){String v=Inputs.storePhone(p.phone());if(!v.equals(s.phone)){changes.add("매장 전화: "+text(s.phone)+" → "+v);s.phone=v;}}
        if(p.address()!=null){String v=blankToNull(p.address());if(!java.util.Objects.equals(v,s.address)){changes.add("주소: "+text(s.address)+" → "+text(v));s.address=v;}}
        if(p.naverPlaceUrl()!=null){String v=blankToNull(p.naverPlaceUrl());if(!java.util.Objects.equals(v,s.naverPlaceUrl)){changes.add("지도 링크: "+text(s.naverPlaceUrl)+" → "+text(v));s.naverPlaceUrl=v;}}
        if(changes.isEmpty())throw new AppException("NO_CHANGE","바뀐 값이 없어요.");
        s.updatedAt=clock.instant();
        record(actor,s,StoreCareAction.PROFILE_UPDATED,why,String.join("; ",changes));
        return Map.of("changed",changes);
    }

    /**
     * 결제일 연기. 소담랩스 쪽 실수를 보상할 때 쓴다. 다음 결제예정일을 N일 뒤로 민다.
     *
     * - 무료체험 중이면 체험 종료(PRO → BASIC 전환 시각)도 같이 민다. 체험 종료일이 곧 첫 결제일이라
     *   둘이 갈라지면 체험이 끝난 뒤 BASIC으로 떨어진 채 기다리게 된다.
     * - 유예·이용 제한 중이어도 된다. 밀린 날짜가 미래면 이용 제한이 그 자리에서 풀린다.
     * - 결제 잠금을 잡는다. 스케줄러가 청구하는 바로 그 순간 날짜를 옮기면 청구와 연기가 둘 다 일어난다.
     * - 실패 횟수를 0으로 되돌린다. 갱신 paymentId는 결제예정일로 만들어지므로 새 날짜에서 새 번호로 시작한다.
     */
    @Transactional Map<String,Object> postponeBilling(AdminUser actor,Long storeId,int days,String reason){
        String why=reason(reason);
        if(days<1||days>MAX_POSTPONE_DAYS)throw new AppException("INVALID_REQUEST","연기 일수는 1~"+MAX_POSTPONE_DAYS+"일이에요.");
        Store store=store(storeId);
        Instant now=clock.instant();
        // 잠금을 먼저 건다. 엔터티를 나중에 읽어야 영속성 컨텍스트가 잠금이 걸린 값을 본다.
        int locked=subscriptions.lockBilling(storeId,now,now.plus(LOCK));
        StoreSubscription s=subscriptions.findByStoreId(storeId).orElse(null);
        if(s==null||s.nextBillingAt==null)
            throw new AppException("NOT_BILLING_TARGET","자동결제 대상이 아닌 매장이에요(결제예정일 없음). 연기할 결제일이 없습니다.");
        if(locked==0)throw new AppException("BILLING_BUSY","지금 이 매장의 결제를 처리하고 있어요. 잠시 후 다시 시도해 주세요.",HttpStatus.CONFLICT);
        Instant before=s.nextBillingAt;ServiceState stateBefore=policy.state(s);
        s.nextBillingAt=plusDays(before,days);
        String trial="";
        if(s.trialEndsAt!=null&&now.isBefore(s.trialEndsAt)){
            Instant trialBefore=s.trialEndsAt;
            s.trialEndsAt=plusDays(trialBefore,days);s.trialEndedAt=s.trialEndsAt;
            trial="; 무료체험 종료: "+date(trialBefore)+" → "+date(s.trialEndsAt);
        }
        s.renewalFailures=0;s.billingLockUntil=null;s.updatedAt=now;
        s.note="운영자 결제일 연기 "+days+"일";
        record(actor,store,StoreCareAction.BILLING_POSTPONED,why,
            "결제예정일: "+date(before)+" → "+date(s.nextBillingAt)+" (+"+days+"일)"+trial+"; 이용 상태: "+stateBefore+" → "+policy.state(s));
        return subscription(storeId);
    }

    /**
     * 보상 등급(무상 상향). 기간 동안 기능만 높은 등급으로 열고 청구는 원래 등급 그대로다.
     * `plan` 칸을 바꾸는 수동 변경({@link SubscriptionService#changePlan})을 보상에 쓰면 다음 자동결제가
     * 높은 등급 요금으로 나간다. 그래서 보상은 따로 둔다.
     */
    @Transactional Map<String,Object> grantComplimentary(AdminUser actor,Long storeId,Plan plan,int days,String reason){
        String why=reason(reason);
        if(days<1||days>MAX_COMPLIMENTARY_DAYS)throw new AppException("INVALID_REQUEST","제공 기간은 1~"+MAX_COMPLIMENTARY_DAYS+"일이에요.");
        Store store=store(storeId);
        Instant now=clock.instant();
        StoreSubscription s=subscriptions.findByStoreId(storeId).orElseGet(()->{
            // 구독 행이 없으면 BASIC과 같다(SubscriptionService.planOf). 보상을 붙일 자리로 BASIC 행을 만든다.
            StoreSubscription row=new StoreSubscription();
            row.store=store;row.plan=Plan.BASIC;row.status=SubscriptionStatus.ACTIVE;row.startedAt=now;row.updatedAt=now;
            return row;
        });
        if(plan.ordinal()<=s.plan.ordinal())
            throw new AppException("COMPLIMENTARY_NOT_HIGHER","지금 등급("+s.plan+")보다 높은 등급만 제공할 수 있어요.");
        String before=describeComplimentary(s,now);
        // 오늘을 1일째로 센다. 7일이면 7일째 되는 날 자정까지다.
        s.complimentaryPlan=plan;
        s.complimentaryUntil=LocalDate.now(clock).plusDays(days).atStartOfDay(clock.getZone()).toInstant();
        s.updatedAt=now;
        subscriptions.save(s);
        record(actor,store,StoreCareAction.COMPLIMENTARY_PLAN_GRANTED,why,
            "보상 등급: "+before+" → "+plan+" ("+date(s.complimentaryUntil.minusSeconds(1))+"까지, "+days+"일); 청구 등급 "+s.plan+" 유지");
        return subscription(storeId);
    }

    @Transactional Map<String,Object> endComplimentary(AdminUser actor,Long storeId,String reason){
        String why=reason(reason);
        Store store=store(storeId);
        StoreSubscription s=subscriptions.findByStoreId(storeId).orElse(null);
        Instant now=clock.instant();
        if(s==null||s.complimentaryPlan==null||s.complimentaryUntil==null||!now.isBefore(s.complimentaryUntil))
            throw new AppException("NO_COMPLIMENTARY","제공 중인 보상 등급이 없어요.");
        String before=describeComplimentary(s,now);
        s.complimentaryPlan=null;s.complimentaryUntil=null;s.updatedAt=now;
        record(actor,store,StoreCareAction.COMPLIMENTARY_PLAN_ENDED,why,"보상 등급 종료: "+before);
        return subscription(storeId);
    }

    /**
     * 직원 PIN 재발급. 사장 API는 없다 — 바꾸는 순간 매장 직원 전원이 쓰던 PIN이 죽는다.
     *
     * 실수 방지 세 겹(QR 재발급과 같다): 매장명을 그대로 다시 입력해야 하고, 사유가 필수이며, 마지막 재발급 후
     * 10분 안에는 다시 바꾸지 않는다. 매장 행을 잠가 동시 요청 두 개가 둘 다 통과하지 못하게 한다.
     * 새 PIN은 응답에도 기록에도 남기지 않는다. 사장이 자기 화면(직원 PIN)에서 본다.
     */
    @Transactional Map<String,Object> resetStaffPin(AdminUser actor,Long storeId,String confirmName,String reason){
        String why=reason(reason);
        Store s=stores.findForUpdate(storeId).orElseThrow(OperatorStoreCareService::notFound);
        if(confirmName==null||!confirmName.trim().equals(s.name.trim()))
            throw new AppException("STAFF_PIN_RESET_CONFIRM_MISMATCH","매장명을 정확히 입력해야 재발급됩니다.");
        Instant now=clock.instant();
        if(events.findFirstByStoreIdAndActionOrderByCreatedAtDesc(storeId,StoreCareAction.STAFF_PIN_RESET)
            .filter(e->e.createdAt.isAfter(now.minus(STAFF_PIN_RESET_COOLDOWN))).isPresent())
            throw new AppException("STAFF_PIN_RECENTLY_RESET","방금 재발급한 PIN입니다. 10분 뒤에 다시 시도해 주세요.",HttpStatus.CONFLICT);
        String pin=Integer.toString(100000+random.nextInt(900000));
        s.staffPinHash=encoder.encode(pin);s.staffPinEncrypted=crypto.encrypt(pin);s.updatedAt=now;
        record(actor,s,StoreCareAction.STAFF_PIN_RESET,why,"직원 PIN 재발급(기존 PIN 즉시 무효, 새 PIN은 매장 관리자 화면에서 확인)");
        return Map.of("storeId",storeId,"resetAt",now);
    }

    /** 사장 대신 뽑는 상세 통계. 요금제와 무관하게 PRO 구간 규칙(보관기간 상한 없음)으로 본다. */
    @Transactional(readOnly=true) Map<String,Object> report(Long storeId,LocalDate from,LocalDate to){
        store(storeId);
        return analytics.report(storeId,Plan.PRO,context.window(Plan.PRO,from,to));
    }

    record Export(String filename,String body){}

    /** 집계 CSV. 누가 언제 어느 구간을 뽑아 줬는지 남긴다(집계라 개인정보는 없지만 사장 데이터다). */
    @Transactional Export export(AdminUser actor,Long storeId,String kind,LocalDate from,LocalDate to,String reason){
        Store store=store(storeId);
        AiContextService.Window w=context.window(Plan.PRO,from,to);
        String body=switch(kind){
            case "daily"->analytics.dailyCsv(storeId,w);
            case "prize"->analytics.prizeCsv(storeId,w);
            default->throw new AppException("INVALID_REQUEST","내보낼 수 있는 항목은 daily, prize입니다.");
        };
        String why=reason==null||reason.isBlank()?"매장 통계 추출":reason(reason);
        record(actor,store,StoreCareAction.STATS_EXPORTED,why,(kind.equals("daily")?"일자별":"상품별")+" CSV "+w.from()+" ~ "+w.to());
        return new Export(AnalyticsService.safeFileName(store.name,kind,w.from(),w.to()),body);
    }

    /** 요금제·결제 상태. 기능 등급(effective)과 청구 등급(billing)을 나눠서 보여 준다. */
    Map<String,Object> subscription(Long storeId){
        Map<String,Object> out=new LinkedHashMap<>();
        StoreSubscription s=subscriptions.findByStoreId(storeId).orElse(null);
        Instant now=clock.instant();
        if(s==null){
            out.put("billingPlan",Plan.BASIC.name());out.put("effectivePlan",Plan.BASIC.name());
            out.put("serviceState",ServiceState.OPEN.name());out.put("billingTarget",false);
            return out;
        }
        out.put("billingPlan",s.plan.name());out.put("effectivePlan",SubscriptionService.effective(s,now).name());
        out.put("monthlyPriceKrw",s.plan.monthlyPriceKrw);
        out.put("serviceState",policy.state(s).name());out.put("billingTarget",s.nextBillingAt!=null);
        if(s.complimentaryPlan!=null&&s.complimentaryUntil!=null&&now.isBefore(s.complimentaryUntil)){
            out.put("complimentaryPlan",s.complimentaryPlan.name());out.put("complimentaryUntil",s.complimentaryUntil);
        }
        if(s.trialEndsAt!=null)out.put("trialEndsAt",s.trialEndsAt);
        if(s.nextBillingAt!=null){out.put("nextBillingAt",s.nextBillingAt);out.put("restrictedFrom",policy.restrictedFrom(s.nextBillingAt));}
        if(s.lastPaidAt!=null)out.put("lastPaidAt",s.lastPaidAt);
        out.put("nextPlan",s.nextPlan==null?null:s.nextPlan.name());
        out.put("autoRenew",Boolean.TRUE.equals(s.autoRenew));out.put("hasCard",s.billingKey!=null);
        out.put("pg",s.billingChannelKey==null?null:portone.channels().get(s.billingChannelKey));
        out.put("renewalFailures",s.renewalFailures==null?0:s.renewalFailures);
        out.put("note",text(s.note));
        return out;
    }

    static Map<String,Object> payment(SubscriptionPayment p){
        Map<String,Object> m=new LinkedHashMap<>();
        m.put("id",p.id);m.put("paymentId",p.paymentId);m.put("storeId",p.store.id);m.put("storeName",p.store.name);
        m.put("plan",p.plan.name());m.put("amount",p.amount);m.put("status",p.status.name());
        m.put("failureReason",text(p.failureReason));m.put("createdAt",p.createdAt);m.put("paidAt",p.paidAt);
        return m;
    }

    static Map<String,Object> event(StoreCareEvent e){
        Map<String,Object> m=new LinkedHashMap<>();
        m.put("action",e.action.name());m.put("actor",e.actorEmail);m.put("reason",e.reason);
        m.put("detail",text(e.detail));m.put("createdAt",e.createdAt);
        return m;
    }

    private void record(AdminUser actor,Store store,StoreCareAction action,String reason,String detail){
        StoreCareEvent e=new StoreCareEvent();
        e.store=store;e.actor=actor;e.actorEmail=actor.email;e.storeName=store.name;e.action=action;e.reason=reason;
        e.detail=detail.length()>1000?detail.substring(0,1000):detail;e.createdAt=clock.instant();
        events.save(e);
    }

    private String describeComplimentary(StoreSubscription s,Instant now){
        return s.complimentaryPlan!=null&&s.complimentaryUntil!=null&&now.isBefore(s.complimentaryUntil)
            ?s.complimentaryPlan+"("+date(s.complimentaryUntil.minusSeconds(1))+"까지)":"없음";
    }

    private Store store(Long id){return stores.findById(id).orElseThrow(OperatorStoreCareService::notFound);}
    private static AppException notFound(){return new AppException("STORE_NOT_FOUND","매장을 찾을 수 없습니다.",HttpStatus.NOT_FOUND);}
    private Instant plusDays(Instant at,int days){return at.atZone(clock.getZone()).plusDays(days).toInstant();}
    private String date(Instant at){return at.atZone(clock.getZone()).toLocalDate().toString();}
    private static String reason(String v){
        String r=Inputs.required(v,"사유를 입력해 주세요. 사장님께 설명하고 다른 운영자가 확인하는 근거가 됩니다.");
        if(r.length()>200)throw new AppException("INVALID_REQUEST","사유는 200자 이하로 입력해 주세요.");
        return r;
    }
    private static String blankToNull(String v){return v==null||v.isBlank()?null:v.trim();}
    private static String text(String v){return v==null?"":v;}
}

/**
 * 소담랩스 전체 지표와 결제 원장.
 *
 * 고객 수는 전화번호 해시의 고유 개수로 센다. 해시는 서비스 전체에서 같은 키로 만들어지므로 여러 매장을
 * 도는 손님은 한 명이다. 120일이 지나 비식별된 행(해시가 0으로 채워짐)은 세지 않는다.
 */
@Service class OperatorMetricsService {
    static final int SNAPSHOT_BACKFILL_DAYS=90;
    private static final int MONTHS=12;

    private final EntityManager em;private final StoreRepository stores;private final StoreSubscriptionRepository subscriptions;
    private final PlatformDailyMetricRepository snapshots;private final ServiceAccessPolicy policy;
    private final Clock clock;private final TransactionTemplate writes;
    OperatorMetricsService(EntityManager em,StoreRepository stores,StoreSubscriptionRepository subscriptions,
        PlatformDailyMetricRepository snapshots,ServiceAccessPolicy policy,Clock clock,PlatformTransactionManager tx){
        this.em=em;this.stores=stores;this.subscriptions=subscriptions;this.snapshots=snapshots;this.policy=policy;
        this.clock=clock;this.writes=new TransactionTemplate(tx);
    }

    /** 지금 숫자. 첫 화면 위쪽 카드들이 쓴다. */
    @Transactional(readOnly=true) Map<String,Object> live(){
        LocalDate today=LocalDate.now(clock);ZoneId zone=clock.getZone();Instant now=clock.instant();
        YearMonth month=YearMonth.from(today);
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("date",today.toString());
        out.put("customers",Map.of("dau",customers(today,today),"wau",customers(today.minusDays(6),today),"mau",customers(today.minusDays(29),today)));

        long totalStores=stores.count();
        Map<String,Object> st=new LinkedHashMap<>();
        st.put("total",totalStores);st.put("active",stores.countByStatus(StoreStatus.ACTIVE));
        st.put("pending",stores.countByStatus(StoreStatus.PENDING_APPROVAL));
        st.put("engaged30",engagedStores(today));
        st.put("newThisMonth",count("select count(s) from Store s where s.createdAt >= :f and s.createdAt < :t",start(month,zone),start(month.plusMonths(1),zone)));
        out.put("stores",st);

        // 요금제와 이용 상태는 저장값이 아니라 결제 규칙과 같은 계산(ServiceAccessPolicy, effective)으로 센다.
        Map<Plan,Long> plans=new EnumMap<>(Plan.class);for(Plan p:Plan.values())plans.put(p,0L);
        Map<ServiceState,Long> states=new EnumMap<>(ServiceState.class);for(ServiceState s:ServiceState.values())states.put(s,0L);
        long mrr=0,paying=0,complimentary=0,autoRenewOff=0,failing=0,trialEnded=0,converted=0;
        List<StoreSubscription> rows=subscriptions.findAll();
        for(StoreSubscription s:rows){
            ServiceState state=policy.state(s);
            plans.merge(SubscriptionService.effective(s,now),1L,Long::sum);states.merge(state,1L,Long::sum);
            if(s.complimentaryPlan!=null&&s.complimentaryUntil!=null&&now.isBefore(s.complimentaryUntil))complimentary++;
            boolean paid=s.lastPaidAt!=null&&s.nextBillingAt!=null&&(state==ServiceState.ACTIVE||state==ServiceState.GRACE);
            if(paid&&Boolean.TRUE.equals(s.autoRenew)){paying++;mrr+=s.plan.monthlyPriceKrw;}
            if(paid&&!Boolean.TRUE.equals(s.autoRenew))autoRenewOff++;
            if(s.renewalFailures!=null&&s.renewalFailures>0)failing++;
            if(s.trialEndedAt!=null&&!now.isBefore(s.trialEndedAt)){trialEnded++;if(s.lastPaidAt!=null)converted++;}
        }
        // 구독 행이 없는 매장은 BASIC이고 결제 대상이 아니다(OPEN).
        long withoutRow=Math.max(0,totalStores-rows.size());
        plans.merge(Plan.BASIC,withoutRow,Long::sum);states.merge(ServiceState.OPEN,withoutRow,Long::sum);
        Map<String,Object> rev=new LinkedHashMap<>();
        rev.put("mrr",mrr);rev.put("arr",mrr*12);rev.put("payingStores",paying);
        rev.put("arpu",paying==0?0:mrr/paying);
        rev.put("paidThisMonth",paidSum(month,zone));rev.put("paidLastMonth",paidSum(month.minusMonths(1),zone));
        rev.put("failedThisMonth",count("select count(p) from SubscriptionPayment p where p.status = :s and p.createdAt >= :f and p.createdAt < :t",
            SubscriptionPaymentStatus.FAILED,start(month,zone),start(month.plusMonths(1),zone)));
        // 10분 넘게 PENDING이면 결과를 못 받은 결제다. 포트원 콘솔과 대조해야 한다.
        rev.put("pendingToReconcile",em.createQuery("select count(p) from SubscriptionPayment p where p.status = :s and p.createdAt < :t",Long.class)
            .setParameter("s",SubscriptionPaymentStatus.PENDING).setParameter("t",now.minus(Duration.ofMinutes(10))).getSingleResult());
        out.put("revenue",rev);
        out.put("plans",names(plans));out.put("serviceStates",names(states));
        Map<String,Object> health=new LinkedHashMap<>();
        health.put("complimentary",complimentary);health.put("autoRenewOff",autoRenewOff);health.put("renewalFailing",failing);
        health.put("trialEnded",trialEnded);health.put("trialConverted",converted);
        health.put("trialConversionPercent",trialEnded==0?null:Math.round(converted*1000.0/trialEnded)/10.0);
        out.put("health",health);

        Instant mf=start(month,zone),mt=start(month.plusMonths(1),zone);
        long games=em.createQuery("select count(g) from GamePlay g where g.playedDate >= :f",Long.class).setParameter("f",month.atDay(1)).getSingleResult();
        long issued=count("select count(c) from Coupon c where c.issuedAt >= :f and c.issuedAt < :t",mf,mt);
        long redeemed=count("select count(c) from Coupon c where c.redeemedAt >= :f and c.redeemedAt < :t",mf,mt);
        long engaged=(Long)st.get("engaged30");
        out.put("engagementThisMonth",Map.of("games",games,"couponsIssued",issued,"couponsRedeemed",redeemed,
            "redemptionPercent",issued==0?0:Math.round(redeemed*1000.0/issued)/10.0,
            "gamesPerEngagedStore",engaged==0?0:Math.round(games*10.0/engaged)/10.0));
        return out;
    }

    /** 최근 12개월. 결제·가입·게임·쿠폰은 원장에서 다시 세고, MAU는 월말 스냅숏에서 읽는다. */
    @Transactional(readOnly=true) List<Map<String,Object>> monthly(){
        ZoneId zone=clock.getZone();LocalDate today=LocalDate.now(clock);YearMonth current=YearMonth.from(today);
        List<Map<String,Object>> out=new ArrayList<>();
        for(int i=MONTHS-1;i>=0;i--){
            YearMonth m=current.minusMonths(i);Instant f=start(m,zone),t=start(m.plusMonths(1),zone);
            Map<String,Object> row=new LinkedHashMap<>();
            row.put("month",m.toString());
            row.put("signups",count("select count(s) from Store s where s.createdAt >= :f and s.createdAt < :t",f,t));
            row.put("games",em.createQuery("select count(g) from GamePlay g where g.playedDate >= :f and g.playedDate < :t",Long.class)
                .setParameter("f",m.atDay(1)).setParameter("t",m.plusMonths(1).atDay(1)).getSingleResult());
            row.put("couponsIssued",count("select count(c) from Coupon c where c.issuedAt >= :f and c.issuedAt < :t",f,t));
            row.put("couponsRedeemed",count("select count(c) from Coupon c where c.redeemedAt >= :f and c.redeemedAt < :t",f,t));
            row.put("revenue",paidSum(m,zone));
            row.put("payments",count("select count(p) from SubscriptionPayment p where p.status = :s and p.paidAt >= :f and p.paidAt < :t",SubscriptionPaymentStatus.PAID,f,t));
            // 진행 중인 달은 지금 값, 지난 달은 말일 스냅숏. 스냅숏이 없는 달(도입 전)은 비워 둔다 — 0이 아니다.
            LocalDate end=m.equals(current)?today:m.atEndOfMonth();
            Object mau=m.equals(current)?customers(today.minusDays(29),today)
                :snapshots.findByMetricDate(end).map(x->(Object)x.mau).orElse(null);
            row.put("mau",mau);
            out.add(row);
        }
        return out;
    }

    /** 일별 추이(최근 90일). 스냅숏에서 읽는다. */
    @Transactional(readOnly=true) List<Map<String,Object>> daily(){
        LocalDate today=LocalDate.now(clock);
        return snapshots.findByMetricDateBetweenOrderByMetricDate(today.minusDays(SNAPSHOT_BACKFILL_DAYS),today.minusDays(1)).stream().map(m->{
            Map<String,Object> row=new LinkedHashMap<>();
            row.put("date",m.metricDate.toString());row.put("dau",m.dau);row.put("wau",m.wau);row.put("mau",m.mau);
            row.put("games",m.games);row.put("couponsIssued",m.couponsIssued);row.put("couponsRedeemed",m.couponsRedeemed);
            row.put("signups",m.signups);row.put("engagedStores",m.engagedStores);
            row.put("payingStores",m.payingStores);row.put("mrr",m.mrrKrw);return row;}).toList();
    }

    String monthlyCsv(){
        StringWriter out=new StringWriter();
        out.write('﻿');
        out.write("월,신규매장,게임,쿠폰발급,쿠폰사용,결제매출(원),결제건수,MAU(월말 기준 30일 고유 고객)\n");
        for(Map<String,Object> r:monthly())
            out.write(r.get("month")+","+r.get("signups")+","+r.get("games")+","+r.get("couponsIssued")+","+r.get("couponsRedeemed")+","
                +r.get("revenue")+","+r.get("payments")+","+(r.get("mau")==null?"":r.get("mau"))+"\n");
        return out.toString();
    }

    /**
     * 빠진 날의 스냅숏을 채운다. 30일 창 전체가 비식별 전 기간 안에 들어오는 날(최근 90일)까지만 채운다 —
     * 그보다 오래된 날은 고객 수가 실제보다 적게 세어진다. 날마다 자기 트랜잭션이라 동시에 두 번 돌아도
     * 유니크 충돌 한 건만 버리고 나머지는 남는다.
     */
    /** 어제 스냅숏이 이미 있으면 아무것도 하지 않는다. 화면을 열 때마다 90일을 훑지 않게 한다. */
    void ensureRecentSnapshots(){
        if(!snapshots.existsByMetricDate(LocalDate.now(clock).minusDays(1)))fillMissingSnapshots();
    }

    int fillMissingSnapshots(){
        LocalDate yesterday=LocalDate.now(clock).minusDays(1);int created=0;
        for(LocalDate d=yesterday.minusDays(SNAPSHOT_BACKFILL_DAYS-1);!d.isAfter(yesterday);d=d.plusDays(1)){
            LocalDate day=d;
            if(snapshots.existsByMetricDate(day))continue;
            try{writes.executeWithoutResult(t->snapshots.save(snapshot(day,day.equals(yesterday))));created++;}
            catch(DataIntegrityViolationException raced){/* 다른 요청이 같은 날을 먼저 채웠다 */}
        }
        return created;
    }

    private PlatformDailyMetric snapshot(LocalDate day,boolean withRevenue){
        ZoneId zone=clock.getZone();Instant f=day.atStartOfDay(zone).toInstant(),t=day.plusDays(1).atStartOfDay(zone).toInstant();
        PlatformDailyMetric m=new PlatformDailyMetric();
        m.metricDate=day;m.dau=customers(day,day);m.wau=customers(day.minusDays(6),day);m.mau=customers(day.minusDays(29),day);
        m.games=em.createQuery("select count(g) from GamePlay g where g.playedDate = :d",Long.class).setParameter("d",day).getSingleResult();
        m.couponsIssued=count("select count(c) from Coupon c where c.issuedAt >= :f and c.issuedAt < :t",f,t);
        m.couponsRedeemed=count("select count(c) from Coupon c where c.redeemedAt >= :f and c.redeemedAt < :t",f,t);
        m.signups=count("select count(s) from Store s where s.createdAt >= :f and s.createdAt < :t",f,t);
        m.engagedStores=engagedStores(day);
        if(withRevenue){
            @SuppressWarnings("unchecked") Map<String,Object> rev=(Map<String,Object>)live().get("revenue");
            m.payingStores=(Long)rev.get("payingStores");m.mrrKrw=(Long)rev.get("mrr");
        }
        m.createdAt=clock.instant();
        return m;
    }

    /** 결제 원장. 전 매장의 결제 시도를 최근 순으로. 매장명으로 찾고 상태로 거른다. */
    @Transactional(readOnly=true) AdminController.PageView<Map<String,Object>> ledger(SubscriptionPaymentStatus status,String q,int page,int size){
        if(page<0||size<1||size>100)throw new AppException("INVALID_REQUEST","page는 0 이상, size는 1~100이어야 합니다.");
        String text=q==null?"":q.trim();
        if(text.length()>100)throw new AppException("INVALID_REQUEST","검색어는 100자 이하로 입력해 주세요.");
        String where=" from SubscriptionPayment p join p.store s where 1=1"+(status==null?"":" and p.status = :status")
            +(text.isEmpty()?"":" and (lower(s.name) like :name or p.paymentId like :raw)");
        TypedQuery<SubscriptionPayment> rows=bind(em.createQuery("select p"+where+" order by p.createdAt desc, p.id desc",SubscriptionPayment.class),status,text);
        long total=bind(em.createQuery("select count(p)"+where,Long.class),status,text).getSingleResult();
        List<Map<String,Object>> content=rows.setFirstResult(page*size).setMaxResults(size).getResultList().stream().map(OperatorStoreCareService::payment).toList();
        return new AdminController.PageView<>(content,page,size,total,(int)((total+size-1)/size));
    }

    private <T> TypedQuery<T> bind(TypedQuery<T> query,SubscriptionPaymentStatus status,String text){
        if(status!=null)query.setParameter("status",status);
        if(!text.isEmpty()){query.setParameter("name","%"+text.toLowerCase(Locale.ROOT)+"%");query.setParameter("raw","%"+text+"%");}
        return query;
    }

    private long customers(LocalDate from,LocalDate to){
        return em.createQuery("select count(distinct g.phoneHash) from GamePlay g where g.playedDate between :f and :t and g.phoneHash <> :anon",Long.class)
            .setParameter("f",from).setParameter("t",to).setParameter("anon",PrivacyCleanupService.ANONYMIZED_PHONE_HASH).getSingleResult();
    }

    private long engagedStores(LocalDate day){
        return em.createQuery("select count(distinct g.store.id) from GamePlay g where g.playedDate between :f and :t",Long.class)
            .setParameter("f",day.minusDays(29)).setParameter("t",day).getSingleResult();
    }

    private long paidSum(YearMonth m,ZoneId zone){
        Long v=em.createQuery("select sum(p.amount) from SubscriptionPayment p where p.status = :s and p.paidAt >= :f and p.paidAt < :t",Long.class)
            .setParameter("s",SubscriptionPaymentStatus.PAID).setParameter("f",start(m,zone)).setParameter("t",start(m.plusMonths(1),zone)).getSingleResult();
        return v==null?0:v;
    }

    private long count(String jpql,Instant f,Instant t){
        return em.createQuery(jpql,Long.class).setParameter("f",f).setParameter("t",t).getSingleResult();
    }
    private long count(String jpql,Object s,Instant f,Instant t){
        return em.createQuery(jpql,Long.class).setParameter("s",s).setParameter("f",f).setParameter("t",t).getSingleResult();
    }
    private static Instant start(YearMonth m,ZoneId zone){return m.atDay(1).atStartOfDay(zone).toInstant();}
    private static <E extends Enum<E>> Map<String,Long> names(Map<E,Long> in){
        Map<String,Long> out=new LinkedHashMap<>();in.forEach((k,v)->out.put(k.name(),v));return out;
    }
}

/** 매일 03:05(KST). 개인정보 비식별(03:15)보다 먼저 돌아야 어제 고객 수가 온전히 세어진다. */
@Service class PlatformMetricsScheduler {
    private final OperatorMetricsService metrics;
    PlatformMetricsScheduler(OperatorMetricsService metrics){this.metrics=metrics;}
    @Scheduled(cron="0 5 3 * * *",zone="Asia/Seoul") void run(){metrics.fillMissingSnapshots();}
}

@RestController @RequestMapping("/api/operator") class OperatorCareController {
    private final StoreApprovalService approvals;private final OperatorStoreCareService care;private final OperatorMetricsService metrics;
    OperatorCareController(StoreApprovalService approvals,OperatorStoreCareService care,OperatorMetricsService metrics){
        this.approvals=approvals;this.care=care;this.metrics=metrics;
    }

    record ProfileBody(@Size(max=100) String name,@Size(max=30) String phone,@Size(max=255) String address,
        @Size(max=500) @Pattern(regexp="|https?://.*") String naverPlaceUrl,@NotBlank @Size(max=200) String reason){}
    record PostponeBody(int days,@NotBlank @Size(max=200) String reason){}
    record ComplimentaryBody(@NotNull Plan plan,int days,@NotBlank @Size(max=200) String reason){}
    record ReasonBody(@NotBlank @Size(max=200) String reason){}
    record ConfirmBody(@NotBlank @Size(max=100) String confirmName,@NotBlank @Size(max=200) String reason){}

    @GetMapping("/stores/{id}/care") ApiResponse<?> detail(@PathVariable Long id,Authentication auth){
        approvals.requireOperator(adminId(auth));
        return ApiResponse.ok(care.detail(id));
    }

    @PutMapping("/stores/{id}/profile") ApiResponse<?> profile(@PathVariable Long id,@Valid @RequestBody ProfileBody b,Authentication auth){
        AdminUser actor=approvals.requireOperator(adminId(auth));
        return ApiResponse.ok(care.updateProfile(actor,id,new OperatorStoreCareService.Profile(b.name(),b.phone(),b.address(),b.naverPlaceUrl()),b.reason()));
    }

    @PostMapping("/stores/{id}/billing/postpone") ApiResponse<?> postpone(@PathVariable Long id,@Valid @RequestBody PostponeBody b,Authentication auth){
        AdminUser actor=approvals.requireOperator(adminId(auth));
        return ApiResponse.ok(care.postponeBilling(actor,id,b.days(),b.reason()));
    }

    @PostMapping("/stores/{id}/complimentary") ApiResponse<?> grant(@PathVariable Long id,@Valid @RequestBody ComplimentaryBody b,Authentication auth){
        AdminUser actor=approvals.requireOperator(adminId(auth));
        return ApiResponse.ok(care.grantComplimentary(actor,id,b.plan(),b.days(),b.reason()));
    }

    @PostMapping("/stores/{id}/complimentary/end") ApiResponse<?> end(@PathVariable Long id,@Valid @RequestBody ReasonBody b,Authentication auth){
        AdminUser actor=approvals.requireOperator(adminId(auth));
        return ApiResponse.ok(care.endComplimentary(actor,id,b.reason()));
    }

    @PostMapping("/stores/{id}/staff-pin/reset") ApiResponse<?> resetStaffPin(@PathVariable Long id,@Valid @RequestBody ConfirmBody b,Authentication auth){
        AdminUser actor=approvals.requireOperator(adminId(auth));
        return ApiResponse.ok(care.resetStaffPin(actor,id,b.confirmName(),b.reason()));
    }

    @GetMapping("/stores/{id}/report") ApiResponse<?> report(@PathVariable Long id,
        @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to,Authentication auth){
        approvals.requireOperator(adminId(auth));
        return ApiResponse.ok(care.report(id,from,to));
    }

    @GetMapping(value="/stores/{id}/report/export/{kind}",produces="text/csv; charset=UTF-8") ResponseEntity<String> export(@PathVariable Long id,@PathVariable String kind,
        @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to,
        @RequestParam(required=false) String reason,Authentication auth){
        AdminUser actor=approvals.requireOperator(adminId(auth));
        OperatorStoreCareService.Export e=care.export(actor,id,kind,from,to,reason);
        return csv(e.filename(),e.body());
    }

    @GetMapping("/payments") ApiResponse<?> payments(@RequestParam(required=false) SubscriptionPaymentStatus status,@RequestParam(required=false) String q,
        @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="30") int size,Authentication auth){
        approvals.requireOperator(adminId(auth));
        return ApiResponse.ok(metrics.ledger(status,q,page,size));
    }

    /** 사업 지표. 스냅숏이 비어 있으면(첫 배포 직후) 이 자리에서 채운다 — 새벽까지 기다리지 않게. */
    @GetMapping("/metrics") ApiResponse<?> metrics(Authentication auth){
        approvals.requireOperator(adminId(auth));
        metrics.ensureRecentSnapshots();
        Map<String,Object> out=new LinkedHashMap<>(metrics.live());
        out.put("monthly",metrics.monthly());
        out.put("daily",metrics.daily());
        return ApiResponse.ok(out);
    }

    @GetMapping(value="/metrics/monthly.csv",produces="text/csv; charset=UTF-8") ResponseEntity<String> monthlyCsv(Authentication auth){
        approvals.requireOperator(adminId(auth));
        return csv("sodam-hanpan_monthly_metrics.csv",metrics.monthlyCsv());
    }

    private static ResponseEntity<String> csv(String filename,String body){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(filename,StandardCharsets.UTF_8).build().toString()).body(body);
    }
    private Long adminId(Authentication a){return a==null?null:(Long)a.getPrincipal();}
}
