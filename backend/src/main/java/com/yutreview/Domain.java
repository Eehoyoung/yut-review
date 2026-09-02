package com.yutreview;

import jakarta.persistence.*;
import java.time.*;

enum AdminRole { SYSTEM_ADMIN, STORE_ADMIN }
enum MembershipRole { OWNER, MANAGER }
enum StoreStatus { ACTIVE, INACTIVE }
enum QrStatus { ACTIVE, REVOKED }
enum Tier { TIER_1, TIER_2, TIER_3 }
enum RedeemPolicy { SAME_DAY, NEXT_DAY, ANYTIME }
enum YutResult {
    DO(Tier.TIER_1), GAE(Tier.TIER_1), GEOL(Tier.TIER_2), YUT(Tier.TIER_2), MO(Tier.TIER_3);
    final Tier tier; YutResult(Tier tier) { this.tier = tier; }
}
enum GameStatus { CREATED, REVEALED, CANCELLED }
enum CouponStatus { ISSUED, REDEEMED, EXPIRED, CANCELLED }

@Entity @Table(name="staff_verifications") class StaffVerification {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) Long id;
    @ManyToOne(optional=false) Store store;
    @Column(nullable=false,unique=true) String token;
    @Column(nullable=false) Instant expiresAt;
    @Column(nullable=false) boolean used;
}

@Entity @Table(name="admin_users") class AdminUser {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) Long id;
    @Column(nullable=false,unique=true) String email;
    @Column(nullable=false) String passwordHash;
    @Column(nullable=false) String name;
    @Enumerated(EnumType.STRING) @Column(nullable=false) AdminRole role;
    @Column(nullable=false) Instant createdAt;
}
@Entity @Table(name="stores") class Store {
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
@Entity @Table(name="prizes",uniqueConstraints=@UniqueConstraint(columnNames={"store_id","tier"})) class Prize {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) Long id;
    @ManyToOne(optional=false) Store store;
    @Enumerated(EnumType.STRING) @Column(nullable=false) Tier tier;
    @Column(nullable=false) String name; String description;
    @Enumerated(EnumType.STRING) @Column(nullable=false) RedeemPolicy redeemPolicy;
    @Column(nullable=false) boolean active;
    @Column(nullable=false) Instant createdAt; @Column(nullable=false) Instant updatedAt;
}
@Entity @Table(name="game_plays", indexes={@Index(columnList="store_id,phone_hash,played_date"),@Index(columnList="store_id,played_at")}) class GamePlay {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) Long id;
    @Column(nullable=false,unique=true,length=36) String publicId;
    @ManyToOne(optional=false) Store store; @ManyToOne(optional=false) StoreQrCode qrCode;
    @Column(nullable=false,columnDefinition="text") String customerNameEncrypted; @Column(nullable=false,length=64) String phoneHash; @Column(nullable=false,columnDefinition="text") String phoneEncrypted; @Column(nullable=false,length=4) String phoneLast4;
    @Enumerated(EnumType.STRING) @Column(nullable=false) YutResult yutResult;
    @Enumerated(EnumType.STRING) @Column(nullable=false) Tier rewardTier;
    @Enumerated(EnumType.STRING) @Column(nullable=false) GameStatus status;
    @Column(nullable=false) String animationSeed; @Column(nullable=false,unique=true) String idempotencyKey;
    @Column(nullable=false) LocalDate playedDate; @Column(nullable=false) Instant playedAt; Instant revealedAt;
}
@Entity @Table(name="coupons", indexes={@Index(columnList="store_id,phone_hash,status"),@Index(columnList="store_id,status")}) class Coupon {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) Long id;
    @ManyToOne(optional=false) Store store; @OneToOne(optional=false) GamePlay gamePlay; @ManyToOne(optional=false) Prize prize;
    @Column(nullable=false,unique=true) String couponToken; @Column(nullable=false,length=64) String phoneHash;
    @Column(nullable=false) String prizeNameSnapshot; String prizeDescriptionSnapshot;
    @Enumerated(EnumType.STRING) @Column(nullable=false) RedeemPolicy redeemPolicySnapshot;
    @Enumerated(EnumType.STRING) @Column(nullable=false) CouponStatus status;
    @Column(nullable=false) Instant validFrom; @Column(nullable=false) Instant expiresAt; @Column(nullable=false) Instant issuedAt; Instant redeemedAt;
}
