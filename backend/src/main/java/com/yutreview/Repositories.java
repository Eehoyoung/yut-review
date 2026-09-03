package com.yutreview;

import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
interface StoreSubscriptionRepository extends JpaRepository<StoreSubscription,Long> {
    Optional<StoreSubscription> findByStoreId(Long storeId);
}
interface AiMonthlyQuotaRepository extends JpaRepository<AiMonthlyQuota,Long> {
    Optional<AiMonthlyQuota> findByStoreIdAndFeatureAndQuotaMonth(Long storeId,AiFeature feature,String quotaMonth);
    List<AiMonthlyQuota> findByStoreIdAndQuotaMonth(Long storeId,String quotaMonth);
    /**
     * 남은 횟수가 있을 때만 1 올린다. 반환값이 0이면 이미 한도에 닿은 것이고, 동시 요청도 여기서 걸린다.
     * 애플리케이션에서 읽고-쓰면 두 요청이 같은 값을 읽어 한도를 넘길 수 있다.
     */
    @Modifying @Query("update AiMonthlyQuota q set q.used=q.used+1,q.updatedAt=:now where q.id=:id and q.used<q.limitPerMonth")
    int consume(@Param("id") Long id,@Param("now") java.time.Instant now);
    @Modifying @Query("update AiMonthlyQuota q set q.limitPerMonth=:limit,q.updatedAt=:now where q.id=:id")
    int relimit(@Param("id") Long id,@Param("limit") int limit,@Param("now") java.time.Instant now);
}
interface AiUsageEventRepository extends JpaRepository<AiUsageEvent,Long> {
    List<AiUsageEvent> findTop20ByStoreIdOrderByCreatedAtDesc(Long storeId);
}
interface AiReportRepository extends JpaRepository<AiReport,Long> {
    Optional<AiReport> findFirstByStoreIdAndFeatureOrderByCreatedAtDesc(Long storeId,AiFeature feature);
}
interface GameRepository extends JpaRepository<GamePlay,Long> {
    Optional<GamePlay> findByPublicId(String id); Optional<GamePlay> findByIdempotencyKey(String key);
    Optional<GamePlay> findFirstByStoreIdAndPhoneHashOrderByPlayedDateDesc(Long storeId,String phoneHash);
    Page<GamePlay> findByStoreIdOrderByPlayedAtDesc(Long storeId,Pageable pageable); long countByStoreId(Long storeId); long countByStoreIdAndPlayedDate(Long storeId,LocalDate date); long countByStoreIdAndYutResult(Long storeId,YutResult result);
}
/**
 * AI와 고급 분석이 쓰는 집계 전용 조회. 반환값은 모두 숫자와 공개 라벨뿐이며 이름·전화번호·해시·
 * 쿠폰 토큰은 어떤 쿼리도 내보내지 않는다. LLM에 들어갈 수 있는 것은 이 인터페이스가 만든 값뿐이다.
 */
interface AnalyticsRepository extends org.springframework.data.repository.Repository<GamePlay,Long> {
    @Query("select count(g) from GamePlay g where g.store.id=:storeId and g.playedDate between :from and :to")
    long countPlays(@Param("storeId") Long storeId,@Param("from") LocalDate from,@Param("to") LocalDate to);

    @Query("select g.yutResult, count(g) from GamePlay g where g.store.id=:storeId and g.playedDate between :from and :to group by g.yutResult")
    List<Object[]> resultCounts(@Param("storeId") Long storeId,@Param("from") LocalDate from,@Param("to") LocalDate to);

    @Query("select g.prizeRank, count(g) from GamePlay g where g.store.id=:storeId and g.playedDate between :from and :to group by g.prizeRank order by g.prizeRank")
    List<Object[]> rankCounts(@Param("storeId") Long storeId,@Param("from") LocalDate from,@Param("to") LocalDate to);

    /** date_part는 H2에 없다. HQL 표준 extract를 쓰면 방언별로 알아서 번역된다. */
    @Query("select extract(hour from g.playedAt), count(g) from GamePlay g where g.store.id=:storeId and g.playedDate between :from and :to group by extract(hour from g.playedAt)")
    List<Object[]> hourCounts(@Param("storeId") Long storeId,@Param("from") LocalDate from,@Param("to") LocalDate to);

    /** 1=일요일 ... 7=토요일 (HQL day of week 규약). */
    @Query("select extract(day of week from g.playedAt), count(g) from GamePlay g where g.store.id=:storeId and g.playedDate between :from and :to group by extract(day of week from g.playedAt)")
    List<Object[]> weekdayCounts(@Param("storeId") Long storeId,@Param("from") LocalDate from,@Param("to") LocalDate to);

    @Query("select c.prizeNameSnapshot, c.prizeRankSnapshot, count(c), sum(case when c.status='REDEEMED' then 1 else 0 end) "
        + "from Coupon c where c.store.id=:storeId and c.gamePlay.playedDate between :from and :to "
        + "group by c.prizeNameSnapshot, c.prizeRankSnapshot order by c.prizeRankSnapshot")
    List<Object[]> prizePerformance(@Param("storeId") Long storeId,@Param("from") LocalDate from,@Param("to") LocalDate to);

    @Query("select c.status, count(c) from Coupon c where c.store.id=:storeId and c.gamePlay.playedDate between :from and :to group by c.status")
    List<Object[]> couponStatusCounts(@Param("storeId") Long storeId,@Param("from") LocalDate from,@Param("to") LocalDate to);

    /**
     * 익명 재참여. 참여자 수와 2회 이상 참여한 사람 수만 센다. 그룹 기준인 phone_hash는 집계 안에서만
     * 쓰이고 밖으로 나가지 않는다.
     *
     * HQL은 FROM 절의 파생 테이블을 이 형태로 지원하지 않아 네이티브로 둔다. 표준 SQL이라 H2
     * PostgreSQL 모드와 실제 PostgreSQL에서 같이 동작한다.
     *
     * 익명화된 행(phone_hash가 0으로 채워진 값)은 제외한다. 보존기간이 지난 참여가 한 사람으로
     * 뭉쳐 있어서, 그대로 세면 재참여자가 없는데도 재참여율이 부풀어 오른다.
     */
    @Query(value="select count(*), coalesce(sum(case when t.plays > 1 then 1 else 0 end), 0) from "
        + "(select phone_hash, count(*) as plays from game_plays "
        + "where store_id = :storeId and played_date between :from and :to "
        + "and phone_hash <> :anonymized group by phone_hash) t",nativeQuery=true)
    List<Object[]> repeatMetrics(@Param("storeId") Long storeId,@Param("from") LocalDate from,
        @Param("to") LocalDate to,@Param("anonymized") String anonymizedPhoneHash);

    @Query("select min(g.playedDate) from GamePlay g where g.store.id=:storeId")
    LocalDate firstPlayedDate(@Param("storeId") Long storeId);
}
interface CouponRepository extends JpaRepository<Coupon,Long> {
    Optional<Coupon> findByCouponToken(String token); Optional<Coupon> findByGamePlayId(Long gamePlayId);
    Optional<Coupon> findFirstByStoreIdAndPhoneHashAndStatusOrderByIssuedAtDesc(Long storeId,String hash,CouponStatus status);
    Page<Coupon> findByStoreIdOrderByIssuedAtDesc(Long storeId,Pageable pageable); long countByStoreIdAndStatus(Long storeId,CouponStatus status);
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select c from Coupon c join fetch c.store where c.couponToken=:token")
    Optional<Coupon> findForUpdate(@Param("token") String token);
}
