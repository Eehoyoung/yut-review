package com.yutreview;

import jakarta.persistence.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/**
 * 입점 키트(A6 안내물 3종 + 테이블 스티커 10장) 실물 발송 관리.
 *
 * 운영자가 매장을 찾아 인쇄소용 PDF를 받고, 인쇄·발송 진행 상태를 표시한다.
 * 행이 없으면 WAITING(인쇄 대기)이다. `store_event_settings`와 같은 전략이라 기존 매장 백필이 필요 없다.
 * 대상은 운영 중(ACTIVE) 매장뿐이다. 승인 전 매장의 안내물을 만들지 않는다는 규칙을 인쇄물에도 그대로 둔다.
 */
enum PrintKitStatus { WAITING, PRINTING, PRINTED, SHIPPED }

@Entity @Table(name="store_print_kits") class StorePrintKit {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) Long id;
    @OneToOne(optional=false) @JoinColumn(name="store_id",nullable=false,unique=true) Store store;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=20) PrintKitStatus status;
    /** 바꾼 운영자. 계정이 지워져도 누가 바꿨는지 남도록 사건 시점 이메일을 동결한다. */
    @Column(name="updated_by_email",length=255) String updatedByEmail;
    @Column(nullable=false) Instant createdAt; @Column(nullable=false) Instant updatedAt;
}

interface StorePrintKitRepository extends JpaRepository<StorePrintKit,Long> {
    Optional<StorePrintKit> findByStoreId(Long storeId);
    List<StorePrintKit> findByStoreIdIn(Collection<Long> storeIds);
}

@Service class PrintKitService {
    private final EntityManager em;private final StorePrintKitRepository kits;private final StoreRepository stores;
    private final QrRepository qrs;private final StorePosterRepository posters;private final Clock clock;
    PrintKitService(EntityManager em,StorePrintKitRepository kits,StoreRepository stores,QrRepository qrs,StorePosterRepository posters,Clock clock){
        this.em=em;this.kits=kits;this.stores=stores;this.qrs=qrs;this.posters=posters;this.clock=clock;
    }

    record Page(List<Map<String,Object>> content,int page,int size,long totalElements,int totalPages,Map<String,Long> counts){}

    /** q는 매장명(부분 일치) 또는 사업자등록번호(숫자만 비교, 하이픈 무시). */
    @Transactional(readOnly=true)
    Page search(String q,PrintKitStatus status,int page,int size,java.util.function.Function<Long,Map<String,Object>> owner){
        String text=q==null?"":q.trim(),digits=text.replaceAll("\\D","");
        StringBuilder where=new StringBuilder(" from Store s left join StorePrintKit k on k.store = s where s.status = :active");
        if(!text.isEmpty())where.append(digits.length()>=3?" and (lower(s.name) like :name or s.businessNumber like :digits)":" and lower(s.name) like :name");
        if(status==PrintKitStatus.WAITING)where.append(" and (k is null or k.status = :status)");
        else if(status!=null)where.append(" and k.status = :status");
        var rows=bind(em.createQuery("select s, k"+where+" order by s.createdAt desc",Object[].class),text,digits,status)
            .setFirstResult(page*size).setMaxResults(size).getResultList();
        long total=bind(em.createQuery("select count(s)"+where,Long.class),text,digits,status).getSingleResult();
        List<Map<String,Object>> content=new ArrayList<>();
        for(Object[] r:rows){Store s=(Store)r[0];StorePrintKit k=(StorePrintKit)r[1];
            Map<String,Object> m=new LinkedHashMap<>();
            m.put("storeId",s.id);m.put("name",s.name);m.put("businessNumber",s.businessNumber==null?"":s.businessNumber);
            m.put("representativeName",s.representativeName==null?"":s.representativeName);
            m.put("storePhone",s.phone);m.put("address",s.address==null?"":s.address);m.put("createdAt",s.createdAt);
            m.putAll(owner.apply(s.id));
            m.put("status",k==null?PrintKitStatus.WAITING:k.status);
            m.put("statusUpdatedAt",k==null?null:k.updatedAt);m.put("statusUpdatedBy",k==null?"":Objects.toString(k.updatedByEmail,""));
            content.add(m);}
        return new Page(content,page,size,total,(int)Math.ceil(total/(double)size),counts());
    }
    private <T> TypedQuery<T> bind(TypedQuery<T> query,String text,String digits,PrintKitStatus status){
        query.setParameter("active",StoreStatus.ACTIVE);
        if(!text.isEmpty()){query.setParameter("name","%"+text.toLowerCase(Locale.ROOT)+"%");if(digits.length()>=3)query.setParameter("digits","%"+digits+"%");}
        if(status!=null)query.setParameter("status",status);
        return query;
    }
    /** 상태별 개수. 행이 없는 운영 중 매장은 인쇄 대기로 센다. */
    private Map<String,Long> counts(){
        Map<String,Long> out=new LinkedHashMap<>();for(PrintKitStatus s:PrintKitStatus.values())out.put(s.name(),0L);
        long active=stores.countByStatus(StoreStatus.ACTIVE),tracked=0;
        for(Object[] r:em.createQuery("select k.status, count(k) from StorePrintKit k where k.store.status = :active group by k.status",Object[].class)
            .setParameter("active",StoreStatus.ACTIVE).getResultList()){out.put(((PrintKitStatus)r[0]).name(),(Long)r[1]);tracked+=(Long)r[1];}
        out.merge(PrintKitStatus.WAITING.name(),active-tracked,Long::sum);return out;
    }

    @Transactional
    Map<String,Object> change(Long storeId,PrintKitStatus status,AdminUser actor){
        Store store=activeStore(storeId);Instant now=clock.instant();
        StorePrintKit kit=kits.findByStoreId(storeId).orElseGet(()->{StorePrintKit k=new StorePrintKit();k.store=store;k.createdAt=now;return k;});
        kit.status=status;kit.updatedByEmail=actor.email;kit.updatedAt=now;kits.save(kit);
        return Map.of("storeId",storeId,"status",status,"statusUpdatedAt",now,"statusUpdatedBy",actor.email);
    }

    /**
     * 인쇄소용 PDF. QR 주소는 사장이 받는 안내물 저장본과 같은 origin을 쓴다(없으면 현재 공개 origin).
     * 여기서는 저장본을 만들지 않는다 — 운영자 다운로드가 매장 데이터를 바꾸지 않게 한다.
     */
    @Transactional(readOnly=true)
    ResponseEntity<byte[]> pdf(Long storeId,String fallbackOrigin){
        Store store=activeStore(storeId);
        StoreQrCode qr=qrs.findFirstByStoreIdAndStatus(storeId,QrStatus.ACTIVE).orElseThrow(()->new AppException("QR_TOKEN_INVALID","활성 QR이 없습니다."));
        String origin=posters.findByStoreId(storeId).map(p->p.publicOrigin).orElse(fallbackOrigin);
        byte[] body=StorePosterService.printKitPdf(store.name,origin+"/s/"+qr.publicToken,store.posterTagline,store.posterBrandTheme);
        return pdfResponse(store.name,body);
    }
    static ResponseEntity<byte[]> pdfResponse(String storeName,byte[] body){
        String filename=storeName.replaceAll("[\\r\\n\\\\/:*?\"<>|]","_")+"_입점키트_A6안내물3종_스티커90x50_"+StorePosterService.STICKER_COUNT+"매.pdf";
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).cacheControl(CacheControl.noStore())
            .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(filename,StandardCharsets.UTF_8).build().toString()).body(body);
    }
    private Store activeStore(Long storeId){
        Store store=stores.findById(storeId).orElseThrow(()->new AppException("STORE_NOT_FOUND","매장을 찾을 수 없습니다.",HttpStatus.NOT_FOUND));
        if(store.status!=StoreStatus.ACTIVE)throw new AppException("STORE_NOT_ACTIVE","운영 중인 매장만 입점 키트를 출력할 수 있습니다.",HttpStatus.CONFLICT);
        return store;
    }
}

@RestController @RequestMapping("/api/admin/operator/print-kits") class PrintKitController {
    private final PrintKitService kits;private final StoreApprovalService approvals;private final PublicOriginResolver publicOrigins;
    PrintKitController(PrintKitService kits,StoreApprovalService approvals,PublicOriginResolver publicOrigins){this.kits=kits;this.approvals=approvals;this.publicOrigins=publicOrigins;}

    record StatusBody(@NotNull PrintKitStatus status){}

    @GetMapping ApiResponse<?> list(@RequestParam(required=false) String q,@RequestParam(required=false) PrintKitStatus status,
        @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size,Authentication auth){
        approvals.requireOperator(adminId(auth));
        if(page<0||size<1||size>100)throw new AppException("INVALID_REQUEST","page는 0 이상, size는 1~100이어야 합니다.");
        if(q!=null&&q.length()>100)throw new AppException("INVALID_REQUEST","검색어는 100자 이하로 입력해 주세요.");
        return ApiResponse.ok(kits.search(q,status,page,size,approvals::owner));
    }

    /** 같은 상태로 다시 보내도 결과가 같다(멱등). */
    @PutMapping("/{storeId}") ApiResponse<?> change(@PathVariable Long storeId,@Valid @RequestBody StatusBody body,Authentication auth){
        return ApiResponse.ok(kits.change(storeId,body.status(),approvals.requireOperator(adminId(auth))));
    }

    @GetMapping(value="/{storeId}/pdf",produces=MediaType.APPLICATION_PDF_VALUE)
    ResponseEntity<byte[]> pdf(@PathVariable Long storeId,Authentication auth,HttpServletRequest request){
        approvals.requireOperator(adminId(auth));
        return kits.pdf(storeId,publicOrigins.resolve(request));
    }

    private Long adminId(Authentication a){return a==null?null:(Long)a.getPrincipal();}
}

