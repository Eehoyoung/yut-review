package com.yutreview;

import jakarta.persistence.*;
import java.time.*;

enum AdminRole { SYSTEM_ADMIN, STORE_ADMIN }
enum MembershipRole { OWNER, MANAGER }
/**
 * 매장 상태. 셀프 신청은 PENDING_APPROVAL로 시작하고 운영자 승인에서만 ACTIVE가 된다.
 * 기존 매장은 전부 ACTIVE라 백필이 필요 없다.
 */
enum StoreStatus { PENDING_APPROVAL, ACTIVE, INACTIVE, REJECTED }
enum QrStatus { ACTIVE, REVOKED }
enum RedeemPolicy { SAME_DAY, NEXT_DAY, ANYTIME }
/** The five physical throws. Which prize rank each one awards is store configuration, not a property of the throw. */
enum YutResult { DO, GAE, GEOL, YUT, MO }
enum GameStatus { CREATED, REVEALED, CANCELLED }
enum CouponStatus { ISSUED, REDEEMED, EXPIRED, CANCELLED }
/**
 * 요금제. 윷놀이 게임과 핵심 고객 경험(QR·상품·쿠폰·직원 PIN·쿨타임)은 등급과 무관하게 모두 동일하다.
 * 차등은 분석 깊이, AI, 브랜딩 같은 매장 운영 기능에서만 만든다.
 */
enum Plan {
    BASIC(9900, 90), STANDARD(14900, 365), PRO(19900, 0);
    final int monthlyPriceKrw;
    /** 분석용 집계 데이터를 거슬러 볼 수 있는 일수. 0은 상한 없음(장기 집계). 고객 개인정보 보존은 별개다. */
    final int analyticsRetentionDays;
    Plan(int monthlyPriceKrw, int analyticsRetentionDays) {
        this.monthlyPriceKrw = monthlyPriceKrw;
        this.analyticsRetentionDays = analyticsRetentionDays;
    }
}
enum SubscriptionStatus { ACTIVE, CANCELLED }
/** 요금제로 잠기는 매장 운영 기능. 게임 관련 기능은 여기에 넣지 않는다. */
enum Entitlement { BASIC_ANALYTICS, ADVANCED_ANALYTICS, CSV_EXPORT, BRANDING }
/** 과금·쿼터 단위가 되는 AI 기능. */
enum AiFeature { AI_EVENT_COPY, AI_REPORT, AI_IMPROVEMENT, AI_CHAT }
/** PRO 안내물에 적용하는 검증된 고대비 팔레트. 임의 색상 입력으로 QR 가독성이 깨지지 않게 프리셋만 허용한다. */
enum PosterBrandTheme { SODAM, FOREST, PLUM }
enum MarketingService { YUT_REVIEW, REVIEW_PILOT, SODAM }
enum AccountRecoveryPurpose { FIND_EMAIL, RESET_PASSWORD }

@Entity @Table(name="admin_users") class AdminUser {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) Long id;
    String phone;
    @Column(nullable=false,unique=true) String email;
    @Column(nullable=false) String passwordHash;
    @Column(nullable=false) String name;
    @Enumerated(EnumType.STRING) @Column(nullable=false) AdminRole role;
    String termsVersion; Instant termsAgreedAt;
    String privacyVersion; Instant privacyAgreedAt;
    /**
     * 지인에게 알려 주는 초대코드. 계정당 하나다.
     *
     * 이 기능이 생기기 전 계정은 비어 있고, 자기 코드를 처음 볼 때 채워진다(InviteCodeService).
     * 그래서 nullable이다. 한 번 정해지면 바꾸지 않는다 — 이미 남에게 알려 준 값이다.
     */
    @Column(name="invite_code",unique=true,length=12) String inviteCode;
    /** 나를 데려온 사람. 리워드 정책이 생기면 이 열을 센다. */
    @ManyToOne @JoinColumn(name="invited_by_admin_user_id") AdminUser invitedBy;
    /**
     * 그때 실제로 입력된 코드. FK가 있는데도 남기는 것은 초대한 계정이 사라져도 "어느 코드로
     * 들어왔는가"가 남아야 해서다. 감사 로그가 대상 이메일을 동결하는 것과 같은 이유다.
     */
    @Column(name="invited_by_code",length=12) String invitedByCode;
    @Column(nullable=false) Instant createdAt;
}
@Entity @Table(name="stores",uniqueConstraints=@UniqueConstraint(columnNames="business_number")) class Store {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) Long id;
    @Column(nullable=false) String name;
    String businessNumber; @Column(nullable=false) String phone; String address; String naverPlaceUrl;
    /**
     * 국세청 진위확인에 쓴 대표자성명과 개업일자.
     *
     * 계정(AdminUser.name)이 아니라 매장에 둔다. 소유권 이전으로 계정이 바뀌어도 이 매장이
     * 어느 사업자로 확인됐는지는 그대로 남아야 한다. 사업자등록번호와 한 쌍으로만 의미가 있다.
     *
     * 검증을 끄고 만든 매장은 비어 있다. 그래서 nullable이고, 있는지 여부가 곧 "확인된 매장인가"다.
     */
    @Column(name="representative_name",length=50) String representativeName;
    /** YYYYMMDD. 국세청 규격이 그렇고, 날짜 연산을 하지 않아 문자열로 둔다. */
    @Column(name="opening_date",length=8) String openingDate;
    @Column(name="business_verified_at") Instant businessVerifiedAt;
    /** 안내물에 넣는 매장 한 줄. PRO 브랜딩 권한에서만 설정된다. */
    @Column(length=60) String posterTagline;
    /** 기존 매장은 null을 SODAM으로 해석한다. */
    @Enumerated(EnumType.STRING) @Column(name="poster_brand_theme",length=20) PosterBrandTheme posterBrandTheme;
    @Column(nullable=false) String staffPinHash;
    @Enumerated(EnumType.STRING) @Column(nullable=false) StoreStatus status;
    @Column(nullable=false) Instant createdAt; @Column(nullable=false) Instant updatedAt;
}
@Entity @Table(name="admin_store_memberships",uniqueConstraints=@UniqueConstraint(columnNames={"admin_user_id","store_id"})) class AdminStoreMembership {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) Long id;
    @ManyToOne(optional=false) @JoinColumn(name="admin_user_id") AdminUser admin;
    @ManyToOne(optional=false) @JoinColumn(name="store_id") Store store;
    @Enumerated(EnumType.STRING) @Column(nullable=false) MembershipRole role;
    @Column(nullable=false) Instant createdAt;
}
/** Append-only proof of each service-specific advertising SMS consent or withdrawal. */
@Entity @Table(name="marketing_consent_events",indexes=@Index(name="idx_marketing_consent_latest",columnList="admin_user_id,service,changed_at")) class MarketingConsentEvent {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) Long id;
    @ManyToOne(optional=false) @JoinColumn(name="admin_user_id",nullable=false) AdminUser admin;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=30) MarketingService service;
    @Column(nullable=false) boolean agreed;
    @Column(nullable=false,length=20) String consentVersion;
    @Column(nullable=false,length=30) String source;
    @Column(name="changed_at",nullable=false) Instant changedAt;
}
@Entity @Table(name="store_qr_codes") class StoreQrCode {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) Long id;
    @ManyToOne(optional=false) Store store;
    @Column(nullable=false,unique=true,length=100) String publicToken;
    @Enumerated(EnumType.STRING) @Column(nullable=false) QrStatus status;
    @Column(nullable=false) Instant createdAt; Instant revokedAt;
}
@Entity @Table(name="store_posters") class StorePoster {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) Long id;
    @OneToOne(optional=false) @JoinColumn(name="store_id",nullable=false,unique=true) Store store;
    /**
     * base64 PNG를 컬럼에 그대로 담는다.
     *
     * `@Lob`을 붙이면 PostgreSQL에서 String이 large object로 매핑돼, 이 컬럼에는 OID 숫자만 들어가고
     * 실제 바이트는 `pg_largeobject`에 따로 산다. 그 객체는 행을 덮어써도 회수되지 않아서
     * 안내물을 다시 만들 때마다 수백 KB가 영구히 샌다(실측: 3회 재생성에 +414KB). 매장 정보를
     * 수정할 때마다 안내물을 다시 만들므로 2GB VM에서 조용히 차오르는 경로였다.
     * 붙이지 말 것. H2 PostgreSQL 모드는 이 차이를 재현하지 못한다.
     */
    @Column(nullable=false,columnDefinition="text") String contentBase64;
    @Column(nullable=false,length=500) String publicOrigin;
    @Column(nullable=false) Instant createdAt; @Column(nullable=false) Instant updatedAt;
}
@Entity @Table(name="prizes",uniqueConstraints=@UniqueConstraint(columnNames={"store_id","prize_rank"})) class Prize {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) Long id;
    @ManyToOne(optional=false) Store store;
    /** 1 is the best prize. A store has 1..MAX_RANK of these; the count is whatever its outcome config uses. */
    @Column(name="prize_rank",nullable=false) int rank;
    @Column(nullable=false) String name; String description;
    @Enumerated(EnumType.STRING) @Column(nullable=false) RedeemPolicy redeemPolicy;
    @Column(nullable=false) boolean active;
    @Column(nullable=false) Instant createdAt; @Column(nullable=false) Instant updatedAt;
}
@Entity @Table(name="store_outcomes",uniqueConstraints=@UniqueConstraint(columnNames={"store_id","yut_result"})) class StoreOutcome {
    static final int MAX_RANK=5, MAX_WEIGHT=1000;
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) Long id;
    @ManyToOne(optional=false) Store store;
    @Enumerated(EnumType.STRING) @Column(name="yut_result",nullable=false) YutResult yutResult;
    /** Relative weight; the probability of this throw is weight / sum(weights) for the store. */
    @Column(nullable=false) int weight;
    @Column(name="prize_rank",nullable=false) int prizeRank;
    @Column(nullable=false) Instant updatedAt;
}
/**
 * 매장별 이벤트 운영 설정. 지금은 쿠폰 사용 기한 하나뿐인데도 Store 컬럼이 아니라 별도 테이블인
 * 이유는, 이 값이 "매장이 무엇인가"가 아니라 "매장이 이벤트를 어떻게 운영하는가"이기 때문이다.
 * 앞으로 늘어날 이벤트 설정도 여기로 온다.
 *
 * 행이 없는 매장은 기본값으로 동작한다. 기존 매장을 일괄 백필하지 않아도 되고, 컬럼이 nullable로
 * 남지도 않는다(StoreSubscription과 같은 전략).
 */
@Entity @Table(name="store_event_settings") class StoreEventSettings {
    static final int DEFAULT_COUPON_VALIDITY_DAYS=90, MIN_COUPON_VALIDITY_DAYS=1, MAX_COUPON_VALIDITY_DAYS=365;
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) Long id;
    @OneToOne(optional=false) @JoinColumn(name="store_id",nullable=false,unique=true) Store store;
    /** 신규 발급 쿠폰에만 적용된다. 이미 발급된 쿠폰의 expiresAt은 이 값을 바꿔도 움직이지 않는다. */
    @Column(name="coupon_validity_days",nullable=false) int couponValidityDays;
    @Column(nullable=false) Instant createdAt; @Column(nullable=false) Instant updatedAt;
}
@Entity @Table(name="store_subscriptions") class StoreSubscription {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) Long id;
    @OneToOne(optional=false) @JoinColumn(name="store_id",unique=true) Store store;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=20) Plan plan;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=20) SubscriptionStatus status;
    @Column(nullable=false) Instant startedAt; @Column(nullable=false) Instant updatedAt;
    /** 가입일 기준 PRO 체험 종료 시각. null이면 체험 구독이 아니다. */
    @Column(name="trial_ends_at") Instant trialEndsAt;
    /** 마지막 변경 사유(운영자 조작, 체험 종료, 결제). */
    @Column(length=200) String note;
    /**
     * 포트원 빌링키. 카드 번호가 아니라 PG가 준 대리값이다. 아래 결제 칸은 모두 nullable이다 —
     * `ddl-auto=update`가 기존 행에 NOT NULL 컬럼을 붙이지 못하고, 결제하지 않은 매장이 대부분이다.
     */
    @Column(name="billing_key",length=200) String billingKey;
    @Column(name="billing_channel_key",length=100) String billingChannelKey;
    /** 마지막으로 결제가 성공한 시각. */
    @Column(name="last_paid_at") Instant lastPaidAt;
    /**
     * 다음 결제예정일. 이 날짜(KST)부터 D+2까지 결제를 시도하며 서비스하고, D+3 00:00부터 이용을 제한한다.
     * null이면 결제 대상이 아니다 — 2026-09-27 이전 가입 매장은 그대로 둔다(사용자 결정).
     */
    @Column(name="next_billing_at") Instant nextBillingAt;
    /** 결제 기간 중 내리기를 고르면 다음 갱신에서 이 등급으로 청구한다. */
    @Enumerated(EnumType.STRING) @Column(name="next_plan",length=20) Plan nextPlan;
    @Column(name="auto_renew") Boolean autoRenew;
    @Column(name="renewal_failures") Integer renewalFailures;
    /** 같은 매장의 결제가 동시에 두 번 나가지 않게 잡는 짧은 잠금(조건부 UPDATE). */
    @Column(name="billing_lock_until") Instant billingLockUntil;
}
enum SubscriptionPaymentStatus { PENDING, PAID, FAILED }
/**
 * 요금제 결제 한 건. PG를 부르기 **전에** PENDING으로 먼저 남긴다. 결제는 됐는데 우리 쪽 기록이
 * 실패하는 경우, 이 행이 PENDING으로 남아 포트원 콘솔과 대조할 실마리가 된다.
 */
@Entity @Table(name="subscription_payments",indexes=@Index(columnList="store_id,created_at")) class SubscriptionPayment {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) Long id;
    @ManyToOne(optional=false) @JoinColumn(name="store_id") Store store;
    @Column(name="payment_id",nullable=false,unique=true,length=64) String paymentId;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=20) Plan plan;
    @Column(nullable=false) int amount;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=20) SubscriptionPaymentStatus status;
    @Column(name="failure_reason",length=200) String failureReason;
    @Column(name="created_at",nullable=false) Instant createdAt;
    @Column(name="paid_at") Instant paidAt;
}
@Entity @Table(name="admin_recovery_challenges",indexes=@Index(columnList="expires_at")) class AdminRecoveryChallenge {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) Long id;
    @ManyToOne(optional=false) @JoinColumn(name="admin_user_id",nullable=false) AdminUser admin;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=30) AccountRecoveryPurpose purpose;
    @Column(name="token_hash",nullable=false,unique=true,length=64) String tokenHash;
    @Column(name="code_hash",nullable=false,length=64) String codeHash;
    @Column(nullable=false) int attempts;
    @Column(name="expires_at",nullable=false) Instant expiresAt;
    @Column(name="used_at") Instant usedAt;
    @Column(name="created_at",nullable=false) Instant createdAt;
}
/**
 * 한 매장·한 기능·한 달의 사용량. (store, feature, month)에 유니크를 걸어 두고 증가는 조건부 UPDATE로만
 * 한다. 동시에 두 요청이 들어와도 한도를 넘겨 쓸 수 없다.
 */
@Entity @Table(name="ai_monthly_quotas",uniqueConstraints=@UniqueConstraint(columnNames={"store_id","feature","quota_month"})) class AiMonthlyQuota {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) Long id;
    @ManyToOne(optional=false) @JoinColumn(name="store_id") Store store;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=30) AiFeature feature;
    /** yyyy-MM (Asia/Seoul). */
    @Column(name="quota_month",nullable=false,length=7) String quotaMonth;
    @Column(nullable=false) int used;
    @Column(nullable=false) int limitPerMonth;
    @Column(nullable=false) Instant updatedAt;
}
/** 호출 1건의 기록. 프롬프트 원문과 고객 개인정보는 저장하지 않는다. */
@Entity @Table(name="ai_usage_events",indexes=@Index(columnList="store_id,created_at")) class AiUsageEvent {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) Long id;
    @ManyToOne(optional=false) @JoinColumn(name="store_id") Store store;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=30) AiFeature feature;
    @Column(nullable=false,length=60) String model;
    @Column(nullable=false,length=60) String promptVersion;
    @Column(nullable=false) int inputTokens; @Column(nullable=false) int outputTokens;
    @Column(nullable=false) boolean succeeded;
    @Column(length=60) String failureCode;
    @Column(nullable=false) Instant createdAt;
}
/** 생성된 리포트 본문. 매장이 다시 열어볼 수 있어야 하고, 재호출 비용을 줄인다. */
@Entity @Table(name="ai_reports",indexes=@Index(columnList="store_id,created_at")) class AiReport {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) Long id;
    @ManyToOne(optional=false) @JoinColumn(name="store_id") Store store;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=30) AiFeature feature;
    @Column(nullable=false,length=60) String promptVersion;
    @Column(nullable=false) LocalDate periodFrom; @Column(nullable=false) LocalDate periodTo;
    @Column(nullable=false,columnDefinition="text") String contentJson;
    @Column(nullable=false) Instant createdAt;
}
@Entity @Table(name="game_plays", indexes={@Index(columnList="store_id,phone_hash,played_date"),@Index(columnList="store_id,played_at")}) class GamePlay {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) Long id;
    @Column(nullable=false,unique=true,length=36) String publicId;
    @ManyToOne(optional=false) Store store; @ManyToOne(optional=false) StoreQrCode qrCode;
    @Column(nullable=false,columnDefinition="text") String customerNameEncrypted; @Column(nullable=false,length=64) String phoneHash; @Column(nullable=false,columnDefinition="text") String phoneEncrypted; @Column(nullable=false,length=4) String phoneLast4;
    @Enumerated(EnumType.STRING) @Column(nullable=false) YutResult yutResult;
    @Column(name="prize_rank",nullable=false) int prizeRank;
    @Enumerated(EnumType.STRING) @Column(nullable=false) GameStatus status;
    @Column(nullable=false) String animationSeed; @Column(nullable=false,unique=true) String idempotencyKey;
    String privacyConsentVersion; Instant privacyConsentedAt;
    @Column(nullable=false) LocalDate playedDate; @Column(nullable=false) Instant playedAt; Instant revealedAt;
}
@Entity @Table(name="coupons", indexes={@Index(columnList="store_id,phone_hash,status"),@Index(columnList="store_id,status"),@Index(columnList="store_id,issued_at")}) class Coupon {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) Long id;
    @ManyToOne(optional=false) Store store; @OneToOne(optional=false) GamePlay gamePlay; @ManyToOne(optional=false) Prize prize;
    @Column(nullable=false,unique=true) String couponToken; @Column(nullable=false,length=64) String phoneHash;
    @Column(nullable=false) String prizeNameSnapshot; String prizeDescriptionSnapshot;
    /** Frozen at issue time so later config changes never rewrite a coupon a customer already holds. */
    @Column(name="prize_rank_snapshot",nullable=false) int prizeRankSnapshot;
    @Enumerated(EnumType.STRING) @Column(nullable=false) RedeemPolicy redeemPolicySnapshot;
    @Enumerated(EnumType.STRING) @Column(nullable=false) CouponStatus status;
    @Column(nullable=false) Instant validFrom; @Column(nullable=false) Instant expiresAt; @Column(nullable=false) Instant issuedAt; Instant redeemedAt;
}
