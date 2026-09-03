package com.yutreview;

import jakarta.persistence.*;
import java.time.*;

enum AdminRole { SYSTEM_ADMIN, STORE_ADMIN }
enum MembershipRole { OWNER, MANAGER }
enum StoreStatus { ACTIVE, INACTIVE }
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
    BASIC(9900, 90), STANDARD(19900, 365), PRO(29900, 0);
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

@Entity @Table(name="admin_users") class AdminUser {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) Long id;
    String phone;
    @Column(nullable=false,unique=true) String email;
    @Column(nullable=false) String passwordHash;
    @Column(nullable=false) String name;
    @Enumerated(EnumType.STRING) @Column(nullable=false) AdminRole role;
    @Column(nullable=false) Instant createdAt;
}
@Entity @Table(name="stores",uniqueConstraints=@UniqueConstraint(columnNames="business_number")) class Store {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) Long id;
    @Column(nullable=false) String name;
    String businessNumber; @Column(nullable=false) String phone; String address; String naverPlaceUrl;
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
    @Lob @Column(nullable=false,columnDefinition="text") String contentBase64;
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
@Entity @Table(name="store_subscriptions") class StoreSubscription {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) Long id;
    @OneToOne(optional=false) @JoinColumn(name="store_id",unique=true) Store store;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=20) Plan plan;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=20) SubscriptionStatus status;
    @Column(nullable=false) Instant startedAt; @Column(nullable=false) Instant updatedAt;
    /** 결제 연동 전이라 관리자가 바꾼 사유만 남긴다. */
    @Column(length=200) String note;
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
