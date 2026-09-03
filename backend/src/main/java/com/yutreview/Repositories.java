package com.yutreview;

import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

interface AdminUserRepository extends JpaRepository<AdminUser,Long> {
    Optional<AdminUser> findByEmail(String email);
    boolean existsByEmail(String email);
}
interface StoreRepository extends JpaRepository<Store,Long> {
    boolean existsByBusinessNumber(String businessNumber);
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select s from Store s where s.id=:id") Optional<Store> findForUpdate(@Param("id") Long id);
}
interface MembershipRepository extends JpaRepository<AdminStoreMembership,Long> {
    long countByAdminId(Long adminId);
    boolean existsByAdminIdAndStoreId(Long adminId, Long storeId);
    List<AdminStoreMembership> findByAdminId(Long adminId);
}
interface QrRepository extends JpaRepository<StoreQrCode,Long> {
    Optional<StoreQrCode> findByPublicToken(String token);
    List<StoreQrCode> findByStoreIdOrderByCreatedAtDesc(Long storeId);
    Optional<StoreQrCode> findFirstByStoreIdAndStatus(Long storeId,QrStatus status);
}
interface StorePosterRepository extends JpaRepository<StorePoster,Long> {
    Optional<StorePoster> findByStoreId(Long storeId);
}
interface PrizeRepository extends JpaRepository<Prize,Long> {
    List<Prize> findByStoreIdOrderByRank(Long storeId); Optional<Prize> findByStoreIdAndRank(Long storeId,int rank);
}
interface StoreOutcomeRepository extends JpaRepository<StoreOutcome,Long> {
    List<StoreOutcome> findByStoreId(Long storeId);
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
