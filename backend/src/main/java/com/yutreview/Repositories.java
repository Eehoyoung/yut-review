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
    /**
     * 되돌리기도 조건부 UPDATE여야 한다. 엔티티를 읽어 -1 하고 저장하면, 그 사이 다른 요청이 올린
     * 값을 덮어써서 원자적 차감이 통째로 무의미해진다.
     */
    @Modifying @Query("update AiMonthlyQuota q set q.used=q.used-1,q.updatedAt=:now "
        + "where q.store.id=:storeId and q.feature=:feature and q.quotaMonth=:month and q.used>0")
    int refund(@Param("storeId") Long storeId,@Param("feature") AiFeature feature,
        @Param("month") String month,@Param("now") java.time.Instant now);
}
interface AiUsageEventRepository extends JpaRepository<AiUsageEvent,Long> {
    List<AiUsageEvent> findTop20ByStoreIdOrderByCreatedAtDesc(Long storeId);
    /**
     * 누적 토큰 계산용. 월 경계는 호출부가 매장 시간(Asia/Seoul)으로 계산해 Instant로 넘긴다.
     * DB의 날짜 포맷 함수(to_char 등)는 방언마다 달라 H2와 PostgreSQL이 갈린다.
     */
    List<AiUsageEvent> findByStoreIdAndCreatedAtBetween(Long storeId,java.time.Instant from,java.time.Instant to);
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

    /**
      * 시간대는 반드시 매장 시간(Asia/Seoul)으로 센다. playedAt은 Instant라 DB가 세션 타임존으로
      * 해석하는데, 그 값이 배포 환경마다 달라지면 "피크 시간"이 통째로 9시간 밀린다. 실제로 H2는
      * UTC로, PostgreSQL은 세션 타임존으로 해석해 테스트와 운영이 갈렸다. 그래서 존을 쿼리에 박는다.
      */
    @Query(value="select extract(hour from (played_at at time zone 'Asia/Seoul')) as h, count(*) "
        + "from game_plays where store_id = :storeId and played_date between :from and :to "
        + "group by h",nativeQuery=true)
    List<Object[]> hourCounts(@Param("storeId") Long storeId,@Param("from") LocalDate from,@Param("to") LocalDate to);

    /**
      * 요일은 playedDate에서 뽑는다. 이 값은 이미 매장 시간 기준의 날짜라 타임존 해석이 끼어들지
      * 않는다. 1=일요일 ... 7=토요일 (HQL day of week 규약).
      */
    @Query("select extract(day of week from g.playedDate), count(g) from GamePlay g where g.store.id=:storeId and g.playedDate between :from and :to group by extract(day of week from g.playedDate)")
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
interface StoreEventSettingsRepository extends JpaRepository<StoreEventSettings,Long> {
    Optional<StoreEventSettings> findByStoreId(Long storeId);
}
interface CouponRepository extends JpaRepository<Coupon,Long> {
    Optional<Coupon> findByCouponToken(String token); Optional<Coupon> findByGamePlayId(Long gamePlayId);
    Optional<Coupon> findFirstByStoreIdAndPhoneHashAndStatusOrderByIssuedAtDesc(Long storeId,String hash,CouponStatus status);
    Page<Coupon> findByStoreIdOrderByIssuedAtDesc(Long storeId,Pageable pageable); long countByStoreIdAndStatus(Long storeId,CouponStatus status);
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select c from Coupon c join fetch c.store where c.couponToken=:token")
    Optional<Coupon> findForUpdate(@Param("token") String token);
}
