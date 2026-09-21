package com.yutreview;

import jakarta.persistence.EntityManager;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * HMAC 키 회전의 마지막 단계.
 *
 * 키를 바꾸면 `phone_hash`로 잡아 두던 2일 쿨타임과 미사용 쿠폰 조회가 전부 빗나간다. 그래서
 * 회전 중에는 current/previous 두 해시를 함께 조회하고, 여기서 저장된 해시를 현재 키로 옮긴다.
 * 이 작업이 끝나야 이전 키를 폐기할 수 있다.
 *
 * 되돌릴 수 있는 이유는 원문 전화번호가 AES-GCM으로 암호화돼 남아 있기 때문이다. 해시에서
 * 번호를 복원하는 것이 아니다.
 *
 * 익명화된 행(보존기간이 지나 `phone_hash`가 0으로 채워진 행)은 건드리지 않는다. 되살릴 번호가
 * 없을뿐더러, 되살리면 개인정보 파기가 무효가 된다.
 */
@Service class PhoneHashMigrationService {
    static final int BATCH_SIZE=500;
    private static final Logger log=LoggerFactory.getLogger(PhoneHashMigrationService.class);

    private final EntityManager entityManager;private final TransactionTemplate transactions;private final PhoneService phones;
    PhoneHashMigrationService(EntityManager entityManager,TransactionTemplate transactions,PhoneService phones){
        this.entityManager=entityManager;this.transactions=transactions;this.phones=phones;
    }

    /** 결과는 건수뿐이다. 해시도 번호도 키도 로그에 남기지 않는다. */
    Map<String,Object> rehashAll(){
        long lastId=0,scanned=0,rehashed=0;
        while(true){
            long cursor=lastId;
            long[] result=transactions.execute(status->rehashBatch(cursor));
            if(result[0]==0)break;
            lastId=result[0];scanned+=result[1];rehashed+=result[2];
        }
        log.info("phone hash rehash finished: scanned={} rehashed={}",scanned,rehashed);
        return Map.of("scanned",scanned,"rehashed",rehashed);
    }

    /** @return {마지막 id, 훑은 수, 바꾼 수}. 마지막 id가 0이면 더 볼 행이 없다. */
    private long[] rehashBatch(long after){
        List<Object[]> rows=entityManager.createQuery("""
                select g.id, g.phoneEncrypted, g.phoneHash from GamePlay g
                where g.id > :after and g.phoneEncrypted <> :anonymized
                order by g.id
                """,Object[].class)
                .setParameter("after",after)
                .setParameter("anonymized",PrivacyCleanupService.ANONYMIZED)
                .setMaxResults(BATCH_SIZE)
                .getResultList();
        if(rows.isEmpty())return new long[]{0,0,0};

        long lastId=0,changed=0;
        for(Object[] row:rows){
            Long id=(Long)row[0];lastId=id;
            String current=phones.hash(phones.decrypt((String)row[1]));
            if(current.equals(row[2]))continue;
            entityManager.createQuery("update GamePlay g set g.phoneHash=:hash where g.id=:id")
                .setParameter("hash",current).setParameter("id",id).executeUpdate();
            entityManager.createQuery("update Coupon c set c.phoneHash=:hash where c.gamePlay.id=:id")
                .setParameter("hash",current).setParameter("id",id).executeUpdate();
            changed++;
        }
        entityManager.clear();
        return new long[]{lastId,rows.size(),changed};
    }
}
