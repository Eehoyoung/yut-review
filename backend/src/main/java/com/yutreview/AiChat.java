package com.yutreview;

import jakarta.persistence.*;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 서버가 소유하는 AI 대화 이력.
 *
 * 예전에는 화면이 보낸 history를 그대로 공급자 메시지에 복사했다. 현재 질문만 개인정보 필터를
 * 지났기 때문에, 요청을 직접 만드는 쪽에서는 history 칸에 전화번호든 쿠폰 토큰이든 JWT든 넣어
 * 모델로 흘려보낼 수 있었다. 이제 이력은 서버에만 있고, 저장 전과 사용 전 양쪽에서 같은 필터를 지난다.
 *
 * 저장되는 것은 필터를 통과한 관리자 텍스트와 모델 답변뿐이다. 고객 개인정보는 애초에 들어오지 못한다.
 */
@Entity @Table(name="ai_chat_turns",indexes=@Index(columnList="store_id,created_at"))
class AiChatTurn {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) Long id;
    @ManyToOne(optional=false) @JoinColumn(name="store_id",nullable=false) Store store;
    @Column(nullable=false,length=20) String role;
    @Column(nullable=false,columnDefinition="text") String content;
    @Column(name="created_at",nullable=false) Instant createdAt;
}

interface AiChatTurnRepository extends JpaRepository<AiChatTurn,Long> {
    List<AiChatTurn> findTop12ByStoreIdOrderByCreatedAtDescIdDesc(Long storeId);

    @Modifying(flushAutomatically=true,clearAutomatically=true)
    @Query("delete from AiChatTurn t where t.store.id=:storeId")
    int clear(@Param("storeId") Long storeId);

    @Modifying(flushAutomatically=true,clearAutomatically=true)
    @Query("delete from AiChatTurn t where t.createdAt<:cutoff")
    int purge(@Param("cutoff") Instant cutoff);
}

@Service class AiChatHistoryService {
    /** 모델에 실어 보내는 최대 턴 수. 비용과 프롬프트 길이를 같이 묶는 숫자다. */
    static final int MAX_TURNS=12;
    /** 보관 기간. 운영 텍스트이고 고객 개인정보가 아니라 분석 보존기간과는 별개다. */
    static final int RETENTION_DAYS=30;

    private final AiChatTurnRepository turns;private final Clock clock;
    AiChatHistoryService(AiChatTurnRepository turns,Clock clock){this.turns=turns;this.clock=clock;}

    /** 오래된 것부터. 필터를 통과하지 못한 턴은 조용히 빠진다(그 한 줄 때문에 대화가 영영 막히면 안 된다). */
    @Transactional(readOnly=true) List<AiChatTurn> recent(Long storeId){
        List<AiChatTurn> found=new ArrayList<>(turns.findTop12ByStoreIdOrderByCreatedAtDescIdDesc(storeId));
        java.util.Collections.reverse(found);
        return found;
    }

    @Transactional void append(Store store,String role,String content){
        AiChatTurn t=new AiChatTurn();
        t.store=store;t.role=role;t.content=content;t.createdAt=clock.instant();
        turns.save(t);
    }

    @Transactional int clear(Long storeId){return turns.clear(storeId);}

    @Scheduled(cron="0 35 3 * * *",zone="Asia/Seoul")
    @Transactional void scheduledPurge(){turns.purge(clock.instant().minus(RETENTION_DAYS,ChronoUnit.DAYS));}
}
