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

@Entity @Table(name="admin_users") class AdminUser {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) Long id;
    @Column(unique=true,length=20) String loginId; String phone;
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
@Entity @Table(name="coupons", indexes={@Index(columnList="store_id,phone_hash,status"),@Index(columnList="store_id,status")}) class Coupon {
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
