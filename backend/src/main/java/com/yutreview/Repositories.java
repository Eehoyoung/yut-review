package com.yutreview;

import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

interface AdminUserRepository extends JpaRepository<AdminUser,Long> { Optional<AdminUser> findByEmail(String email); }
interface StoreRepository extends JpaRepository<Store,Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select s from Store s where s.id=:id") Optional<Store> findForUpdate(@Param("id") Long id);
}
interface MembershipRepository extends JpaRepository<AdminStoreMembership,Long> {
    boolean existsByAdminIdAndStoreId(Long adminId, Long storeId);
    List<AdminStoreMembership> findByAdminId(Long adminId);
}
interface QrRepository extends JpaRepository<StoreQrCode,Long> {
    Optional<StoreQrCode> findByPublicToken(String token);
    List<StoreQrCode> findByStoreIdOrderByCreatedAtDesc(Long storeId);
    Optional<StoreQrCode> findFirstByStoreIdAndStatus(Long storeId,QrStatus status);
}
interface PrizeRepository extends JpaRepository<Prize,Long> {
    List<Prize> findByStoreIdOrderByTier(Long storeId); Optional<Prize> findByStoreIdAndTier(Long storeId,Tier tier);
}
interface GameRepository extends JpaRepository<GamePlay,Long> {
    Optional<GamePlay> findByPublicId(String id); Optional<GamePlay> findByIdempotencyKey(String key);
    Optional<GamePlay> findFirstByStoreIdAndPhoneHashOrderByPlayedDateDesc(Long storeId,String phoneHash);
    List<GamePlay> findByStoreIdOrderByPlayedAtDesc(Long storeId); long countByStoreId(Long storeId); long countByStoreIdAndPlayedDate(Long storeId,LocalDate date); long countByStoreIdAndYutResult(Long storeId,YutResult result);
}
interface CouponRepository extends JpaRepository<Coupon,Long> {
    Optional<Coupon> findByCouponToken(String token); Optional<Coupon> findByGamePlayId(Long gamePlayId);
    Optional<Coupon> findFirstByStoreIdAndPhoneHashAndStatusOrderByIssuedAtDesc(Long storeId,String hash,CouponStatus status);
    List<Coupon> findByStoreIdOrderByIssuedAtDesc(Long storeId); long countByStoreIdAndStatus(Long storeId,CouponStatus status);
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select c from Coupon c join fetch c.store where c.couponToken=:token")
    Optional<Coupon> findForUpdate(@Param("token") String token);
}
interface StaffVerificationRepository extends JpaRepository<StaffVerification,Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select v from StaffVerification v where v.token=:token") Optional<StaffVerification> findForUpdate(@Param("token") String token);
}
