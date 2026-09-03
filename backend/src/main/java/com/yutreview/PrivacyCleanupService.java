package com.yutreview;

import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class PrivacyCleanupService {
    static final int RETENTION_DAYS = 120;
    static final int BATCH_SIZE = 1000;
    static final String ANONYMIZED = "ANONYMIZED";
    static final String ANONYMIZED_PHONE_HASH = "0".repeat(64);
    static final String ANONYMIZED_PHONE_LAST4 = "****";

    private final EntityManager entityManager;
    private final TransactionTemplate transactions;
    private final Clock clock;

    PrivacyCleanupService(EntityManager entityManager, TransactionTemplate transactions, Clock clock) {
        this.entityManager = entityManager;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Scheduled(cron = "0 15 3 * * *", zone = "Asia/Seoul")
    void scheduledCleanup() {
        cleanup(BATCH_SIZE);
    }

    int cleanup(int batchSize) {
        if (batchSize < 1) throw new IllegalArgumentException("batchSize must be positive");
        LocalDate cutoff = LocalDate.now(clock).minusDays(RETENTION_DAYS);
        Instant now = clock.instant();
        int total = 0;
        int changed;
        do {
            changed = transactions.execute(status -> anonymizeBatch(cutoff, now, batchSize));
            total += changed;
        } while (changed == batchSize);
        return total;
    }

    private int anonymizeBatch(LocalDate cutoff, Instant now, int batchSize) {
        List<Long> ids = entityManager.createQuery("""
                select g.id from GamePlay g
                where g.playedDate < :cutoff
                  and g.customerNameEncrypted <> :anonymized
                  and not exists (
                    select c.id from Coupon c
                    where c.gamePlay = g
                      and c.status = :issued
                      and c.expiresAt >= :now)
                order by g.id
                """, Long.class)
                .setParameter("cutoff", cutoff)
                .setParameter("anonymized", ANONYMIZED)
                .setParameter("issued", CouponStatus.ISSUED)
                .setParameter("now", now)
                .setMaxResults(batchSize)
                .getResultList();
        if (ids.isEmpty()) return 0;

        entityManager.createQuery("""
                update GamePlay g set
                  g.customerNameEncrypted = :anonymized,
                  g.phoneEncrypted = :anonymized,
                  g.phoneHash = :phoneHash,
                  g.phoneLast4 = :last4
                where g.id in :ids
                """)
                .setParameter("anonymized", ANONYMIZED)
                .setParameter("phoneHash", ANONYMIZED_PHONE_HASH)
                .setParameter("last4", ANONYMIZED_PHONE_LAST4)
                .setParameter("ids", ids)
                .executeUpdate();
        entityManager.createQuery("update Coupon c set c.phoneHash = :phoneHash where c.gamePlay.id in :ids")
                .setParameter("phoneHash", ANONYMIZED_PHONE_HASH)
                .setParameter("ids", ids)
                .executeUpdate();
        entityManager.clear();
        return ids.size();
    }
}
