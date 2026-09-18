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
    /** 안내물에 넣는 매장 한 줄. STANDARD 이상(브랜딩 권한)에서만 설정된다. */
    @Column(length=60) String posterTagline;
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
/**
 * 운영자(SYSTEM_ADMIN) 콘솔의 2단계 인증 자격. 비밀번호 하나로 플랫폼 전체를 여는 문을 만들지
 * 않으려고 둔다. 비밀값은 {@link PhoneService}의 AES-256-GCM으로 암호화해 저장한다. DB 덤프만으로
 * 두 번째 인증 수단이 복제되면 2단계가 아니라 비밀번호 두 개일 뿐이다.
 */
@Entity @Table(name="admin_totp_credentials") class AdminTotpCredential {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) Long id;
    @OneToOne(optional=false) @JoinColumn(name="admin_user_id",nullable=false,unique=true) AdminUser admin;
    @Column(nullable=false,columnDefinition="text") String secretEncrypted;
    @Column(nullable=false) boolean confirmed;
    /** 이미 쓴 코드의 시간 슬롯. 같은 30초 코드를 두 번 받지 않는다(어깨너머로 본 코드 재사용 차단). */
    @Column(nullable=false) long lastUsedStep;
    @Column(nullable=false) Instant createdAt; Instant confirmedAt;
}
/**
 * 운영자 콘솔에서 일어난 일. 로그인 성공/실패와 모든 변경을 남긴다.
 *
 * 개인정보는 넣지 않는다. 남는 것은 운영자 계정 식별자와 무엇을 어느 매장에 했는지뿐이다.
 */
@Entity @Table(name="system_audit_logs",indexes=@Index(columnList="created_at")) class SystemAuditLog {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) Long id;
    Long actorAdminId;
    @Column(nullable=false,length=255) String actorEmail;
    @Column(nullable=false,length=60) String action;
    @Column(length=40) String targetType; Long targetId;
    @Column(length=500) String detail;
    @Column(length=64) String ip;
    @Column(nullable=false) boolean succeeded;
    @Column(nullable=false) Instant createdAt;
    /**
     * 변조 감지용 해시 사슬. 각 행은 앞 행의 해시를 포함해 자기 해시를 만든다. 중간 행을 고치거나
     * 지우면 그 뒤가 전부 어긋나므로 `GET /api/system/audit/verify`가 어디서 끊겼는지 짚어 준다.
     * 이 값들이 있다고 삭제를 막지는 못한다. 막는 게 아니라 숨길 수 없게 만드는 장치다.
     */
    @Column(length=64) String prevHash; @Column(length=64) String hash;
}
/** 운영자 콘솔 안에서의 권한 등급. 최소 권한 원칙: 조회만 필요한 사람에게 변경 권한을 주지 않는다. */
enum ConsoleRole {
    /** 조회만. */ VIEWER,
    /** 매장 요금제·운영 상태 변경까지. */ OPERATOR,
    /** 운영자 계정 관리와 감사 무결성 검증까지. */ OWNER
}
/**
 * 운영자 계정의 보안 상태. 로그인 자격(AdminUser)과 나눠 둔다. 매장 관리자에게는 없는 값들이고,
 * 여기 있는 값 하나하나가 콘솔 문을 여닫는 조건이라 한 곳에 모아 두는 편이 감사하기 쉽다.
 */
@Entity @Table(name="operator_security") class OperatorSecurity {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) Long id;
    @OneToOne(optional=false) @JoinColumn(name="admin_user_id",nullable=false,unique=true) AdminUser admin;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=20) ConsoleRole consoleRole;
    @Column(nullable=false) boolean disabled;
    @Column(nullable=false) int failedAttempts;
    /** 연속 실패로 잠긴 계정이 다시 열리는 시각. 재시작해도 남아야 해서 DB에 둔다. */
    Instant lockedUntil;
    /** null이면 남이 정해 준 임시 비밀번호다. 바꾸기 전에는 조회 외 아무것도 하지 못한다. */
    Instant passwordChangedAt;
    /** 이 계정만의 접속 허용 IP/CIDR(쉼표 구분). 비어 있으면 전역 설정만 적용된다. */
    @Column(length=500) String allowedIps;
    Instant lastLoginAt; @Column(length=64) String lastLoginIp;
    @Column(nullable=false) Instant createdAt; @Column(nullable=false) Instant updatedAt;
}
/**
 * 발급된 콘솔 세션 하나. JWT만 쓰면 로그아웃도 권한 회수도 토큰 만료까지 기다려야 한다.
 * 토큰의 `jti`가 이 행을 가리키고, 요청마다 이 행을 확인하므로 즉시 끊을 수 있다.
 */
@Entity @Table(name="operator_sessions",indexes=@Index(columnList="admin_user_id")) class OperatorSession {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) Long id;
    @Column(nullable=false,unique=true,length=64) String tokenId;
    @ManyToOne(optional=false) @JoinColumn(name="admin_user_id") AdminUser admin;
    @Column(nullable=false) Instant createdAt;
    /** 마지막 요청 시각. 여기서부터 유휴 제한을 센다. */
    @Column(nullable=false) Instant lastSeenAt;
    /** 활동과 무관하게 이 시각이 지나면 끝난다(토큰 만료와 같은 시각). */
    @Column(nullable=false) Instant absoluteExpiresAt;
    /** 마지막으로 2단계 인증을 통과한 시각. 위험한 조작은 이 값이 최근일 때만 허용한다. */
    Instant stepUpAt;
    Instant revokedAt; @Column(length=40) String revokedReason;
    @Column(length=64) String ip;
    /** User-Agent 원문 대신 해시. 토큰이 다른 기기로 옮겨 가면 값이 달라진다. */
    @Column(length=64) String userAgentHash;
    @Column(length=120) String userAgentLabel;
}
/**
 * 2단계 인증 복구 코드. 폰을 잃어버린 운영자가 콘솔에서 영구히 잠기지 않게 한다.
 * 한 번 쓰면 끝이고, 비밀번호와 같은 방식(BCrypt)으로만 저장한다.
 */
@Entity @Table(name="operator_backup_codes",indexes=@Index(columnList="admin_user_id")) class OperatorBackupCode {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) Long id;
    @ManyToOne(optional=false) @JoinColumn(name="admin_user_id") AdminUser admin;
    @Column(nullable=false) String codeHash;
    @Column(nullable=false) Instant createdAt; Instant usedAt;
}
