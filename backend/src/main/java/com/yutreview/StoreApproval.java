package com.yutreview;

import jakarta.persistence.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

enum StoreApprovalAction { APPROVE, REJECT, REVIEW_AGAIN, OWNERSHIP_CHANGE }

/**
 * 승인·거부·재심사·소유권 변경의 append-only 증거.
 *
 * 사업자등록번호는 자기 것이라고 주장하는 사람이 그냥 적어 넣는 값이다. 그 주장이 권한이 되기
 * 전에 운영자가 한 번 본다는 것을, 나중에 분쟁이 났을 때 확인할 수 있어야 한다.
 */
@Entity @Table(name="store_approval_events",indexes=@Index(columnList="store_id,created_at"))
class StoreApprovalEvent {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) Long id;
    @ManyToOne(optional=false) @JoinColumn(name="store_id",nullable=false) Store store;
    @ManyToOne @JoinColumn(name="actor_admin_user_id") AdminUser actor;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=30) StoreApprovalAction action;
    @Column(length=200) String note;
    @Column(name="created_at",nullable=false) Instant createdAt;
}

interface StoreApprovalEventRepository extends JpaRepository<StoreApprovalEvent,Long> {
    List<StoreApprovalEvent> findByStoreIdOrderByCreatedAtDescIdDesc(Long storeId);
    Optional<StoreApprovalEvent> findFirstByStoreIdAndActionOrderByCreatedAtDescIdDesc(Long storeId,StoreApprovalAction action);
}

@Service class StoreApprovalService {
    private final StoreRepository stores;private final MembershipRepository memberships;
    private final AdminUserRepository admins;private final StoreApprovalEventRepository events;
    private final QrRepository qrs;private final StorePosterService posters;private final Clock clock;
    StoreApprovalService(StoreRepository stores,MembershipRepository memberships,AdminUserRepository admins,
        StoreApprovalEventRepository events,QrRepository qrs,StorePosterService posters,Clock clock){
        this.stores=stores;this.memberships=memberships;this.admins=admins;this.events=events;
        this.qrs=qrs;this.posters=posters;this.clock=clock;
    }

    /** 운영자 전용 문. 요금제 변경과 같은 이유로 멤버십이 아니라 계정 역할로만 연다. */
    AdminUser requireOperator(Long adminId){
        AdminUser admin=adminId==null?null:admins.findById(adminId).orElse(null);
        if(admin==null||admin.role!=AdminRole.SYSTEM_ADMIN)
            throw new AppException("OPERATOR_ONLY","운영자만 사용할 수 있는 기능입니다.",HttpStatus.FORBIDDEN);
        return admin;
    }

    /**
     * 매장 운영 기능이 열려 있는지. 승인 대기·거부·중지는 전부 여기서 막힌다.
     *
     * 상태 확인용 조회(GET /stores/{id})는 이 검사를 지나지 않는다. 사장이 자기 신청이 어떻게
     * 됐는지조차 못 보면 승인 흐름이 그냥 고장난 화면이 된다.
     */
    void requireOperable(Store store){
        switch(store.status){
            case ACTIVE -> {}
            case PENDING_APPROVAL -> throw new AppException("STORE_PENDING_APPROVAL",
                "운영자 승인 후 이용할 수 있습니다.",HttpStatus.FORBIDDEN);
            case REJECTED -> throw new AppException("STORE_REJECTED",
                "승인이 거부된 매장입니다. 운영자에게 문의해 주세요.",HttpStatus.FORBIDDEN);
            case INACTIVE -> throw new AppException("STORE_INACTIVE",
                "운영 중인 매장이 아닙니다.",HttpStatus.FORBIDDEN);
        }
    }

    /** 거부 사유. 사장 화면이 "왜 막혔는지"를 말할 수 있게 마지막 거부 기록만 꺼낸다. */
    String rejectionNote(Long storeId){
        return events.findFirstByStoreIdAndActionOrderByCreatedAtDescIdDesc(storeId,StoreApprovalAction.REJECT)
            .map(e->e.note==null?"":e.note).orElse("");
    }

    /**
     * 승인. 이미 ACTIVE면 아무 것도 하지 않고 changed=false를 돌려준다.
     *
     * 같은 승인을 두 번 눌러도 감사 로그에 같은 사건이 두 줄 남지 않아야 재전송이 기록을 흐리지
     * 않는다. 포스터는 여기서 처음 만든다. 승인 전에 렌더링해 두면 아무나 익명 요청 한 번으로
     * 큰 PNG를 쌓게 만드는 길이 다시 열린다.
     */
    @Transactional Map<String,Object> approve(AdminUser actor,Long storeId,String note,String publicOrigin){
        Store store=lock(storeId);
        if(store.status==StoreStatus.ACTIVE)return Map.of("id",store.id,"status",store.status.name(),"changed",false);
        store.status=StoreStatus.ACTIVE;store.updatedAt=clock.instant();
        record(store,actor,StoreApprovalAction.APPROVE,note);
        qrs.findFirstByStoreIdAndStatus(store.id,QrStatus.ACTIVE)
            .ifPresent(qr->posters.save(store,qr.publicToken,publicOrigin,store.posterTagline));
        return Map.of("id",store.id,"status",store.status.name(),"changed",true);
    }

    @Transactional Map<String,Object> reject(AdminUser actor,Long storeId,String reason){
        Store store=lock(storeId);
        if(store.status==StoreStatus.REJECTED)return Map.of("id",store.id,"status",store.status.name(),"changed",false);
        store.status=StoreStatus.REJECTED;store.updatedAt=clock.instant();
        record(store,actor,StoreApprovalAction.REJECT,Inputs.required(reason,"거부 사유를 입력해 주세요."));
        return Map.of("id",store.id,"status",store.status.name(),"changed",true);
    }

    /** 거부된 매장을 다시 심사 대기로. 거부가 영구 사망선고가 되면 오판을 되돌릴 방법이 없다. */
    @Transactional Map<String,Object> reviewAgain(AdminUser actor,Long storeId,String note){
        Store store=lock(storeId);
        if(store.status!=StoreStatus.REJECTED)
            throw new AppException("INVALID_REQUEST","거부된 매장만 재심사로 되돌릴 수 있습니다.");
        store.status=StoreStatus.PENDING_APPROVAL;store.updatedAt=clock.instant();
        record(store,actor,StoreApprovalAction.REVIEW_AGAIN,note);
        return Map.of("id",store.id,"status",store.status.name(),"changed",true);
    }

    /** 소유권 이전. 실제 사업자가 남의 계정에 매장을 빼앗겼을 때의 복구 경로다. */
    @Transactional Map<String,Object> changeOwner(AdminUser actor,Long storeId,String email,String note){
        Store store=lock(storeId);
        AdminUser next=admins.findByEmail(Inputs.email(email))
            .orElseThrow(()->new AppException("ADMIN_NOT_FOUND","해당 이메일로 가입된 관리자 계정이 없습니다."));
        Instant now=clock.instant();
        for(AdminStoreMembership m:memberships.findByStoreId(storeId))
            if(m.role==MembershipRole.OWNER)memberships.delete(m);
        Optional<AdminStoreMembership> existing=memberships.findByStoreId(storeId).stream()
            .filter(m->m.admin.id.equals(next.id)).findFirst();
        AdminStoreMembership owner=existing.orElseGet(AdminStoreMembership::new);
        owner.admin=next;owner.store=store;owner.role=MembershipRole.OWNER;
        if(owner.createdAt==null)owner.createdAt=now;
        memberships.save(owner);
        record(store,actor,StoreApprovalAction.OWNERSHIP_CHANGE,note==null||note.isBlank()?next.email:note);
        return Map.of("id",store.id,"ownerEmail",next.email);
    }

    List<Map<String,Object>> events(Long storeId){
        return events.findByStoreIdOrderByCreatedAtDescIdDesc(storeId).stream().map(e->{
            Map<String,Object> view=new LinkedHashMap<String,Object>();
            view.put("action",e.action.name());
            view.put("actorEmail",e.actor==null?"":e.actor.email);
            view.put("note",e.note==null?"":e.note);
            view.put("createdAt",e.createdAt);
            return view;
        }).toList();
    }

    Map<String,Object> owner(Long storeId){
        return memberships.findByStoreId(storeId).stream().filter(m->m.role==MembershipRole.OWNER).findFirst()
            .map(m->Map.<String,Object>of("ownerName",m.admin.name,"ownerEmail",m.admin.email,
                "ownerPhone",m.admin.phone==null?"":m.admin.phone))
            .orElse(Map.of("ownerName","","ownerEmail","","ownerPhone",""));
    }

    private Store lock(Long storeId){
        return stores.findForUpdate(storeId)
            .orElseThrow(()->new AppException("STORE_NOT_FOUND","매장을 찾을 수 없습니다.",HttpStatus.NOT_FOUND));
    }
    private void record(Store store,AdminUser actor,StoreApprovalAction action,String note){
        StoreApprovalEvent e=new StoreApprovalEvent();
        e.store=store;e.actor=actor;e.action=action;
        e.note=note==null||note.isBlank()?null:note.trim();
        e.createdAt=clock.instant();
        events.save(e);
    }
}

/**
 * 운영자 전용 심사 API.
 *
 * CSRF 토큰을 따로 두지 않는다. 인증이 cookie가 아니라 `sessionStorage`의 Bearer JWT라
 * 브라우저가 이 요청에 자격증명을 자동으로 실어 주지 않기 때문이다(그것이 CSRF의 전제다).
 * 재전송 방어는 각 동작을 멱등하게 만들어서 얻는다.
 */
@RestController @RequestMapping("/api/admin/operator") class OperatorController {
    private final StoreApprovalService approvals;private final StoreRepository stores;
    private final PhoneHashMigrationService phoneHashes;private final PublicOriginResolver publicOrigins;private final OperatorMonitoringService monitoring;
    OperatorController(StoreApprovalService approvals,StoreRepository stores,
        PhoneHashMigrationService phoneHashes,PublicOriginResolver publicOrigins,OperatorMonitoringService monitoring){
        this.approvals=approvals;this.stores=stores;this.phoneHashes=phoneHashes;this.publicOrigins=publicOrigins;this.monitoring=monitoring;
    }

    record NoteBody(@Size(max=200) String note){}
    record RejectBody(@NotBlank @Size(max=200) String reason){}
    record OwnershipBody(@NotBlank @Size(max=255) String email,@Size(max=200) String note){}

    /**
     * 자원 현황. 쿼터가 무엇을 막고 있고 저장량이 어디까지 찼는지 한 화면에서 본다.
     *
     * 알림은 붙이지 않았다. 매장 수십 곳 규모에서 알림 채널을 먼저 만드는 것은 운영 부담만 늘린다.
     * 운영자가 이 화면을 보는 것이 지금의 모니터링이고, 임계값은 `docs/LOAD_TEST_PLAN.md`에 있다.
     */
    @GetMapping("/monitoring") ApiResponse<?> monitoring(Authentication auth){
        approvals.requireOperator(adminId(auth));
        return ApiResponse.ok(monitoring.snapshot());
    }

    @GetMapping("/summary") ApiResponse<?> summary(Authentication auth){
        approvals.requireOperator(adminId(auth));
        return ApiResponse.ok(Map.of(
            "pending",stores.countByStatus(StoreStatus.PENDING_APPROVAL),
            "active",stores.countByStatus(StoreStatus.ACTIVE),
            "rejected",stores.countByStatus(StoreStatus.REJECTED)));
    }

    @GetMapping("/stores") ApiResponse<?> list(@RequestParam(required=false) StoreStatus status,
        @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size,Authentication auth){
        approvals.requireOperator(adminId(auth));
        if(page<0||size<1||size>100)throw new AppException("INVALID_REQUEST","page는 0 이상, size는 1~100이어야 합니다.");
        PageRequest request=PageRequest.of(page,size,Sort.by(Sort.Direction.ASC,"createdAt"));
        Page<Store> found=status==null?stores.findAll(request):stores.findByStatus(status,request);
        Page<Map<String,Object>> view=found.map(this::view);
        return ApiResponse.ok(new AdminController.PageView<>(view.getContent(),view.getNumber(),view.getSize(),
            view.getTotalElements(),view.getTotalPages()));
    }

    @PostMapping("/stores/{id}/approve") ApiResponse<?> approve(@PathVariable Long id,
        @Valid @RequestBody(required=false) NoteBody body,Authentication auth,
        jakarta.servlet.http.HttpServletRequest request){
        return ApiResponse.ok(approvals.approve(approvals.requireOperator(adminId(auth)),id,
            body==null?null:body.note(),publicOrigins.resolve(request)));
    }

    @PostMapping("/stores/{id}/reject") ApiResponse<?> reject(@PathVariable Long id,
        @Valid @RequestBody RejectBody body,Authentication auth){
        return ApiResponse.ok(approvals.reject(approvals.requireOperator(adminId(auth)),id,body.reason()));
    }

    @PostMapping("/stores/{id}/review-again") ApiResponse<?> reviewAgain(@PathVariable Long id,
        @Valid @RequestBody(required=false) NoteBody body,Authentication auth){
        return ApiResponse.ok(approvals.reviewAgain(approvals.requireOperator(adminId(auth)),id,
            body==null?null:body.note()));
    }

    @PostMapping("/stores/{id}/ownership") ApiResponse<?> ownership(@PathVariable Long id,
        @Valid @RequestBody OwnershipBody body,Authentication auth){
        return ApiResponse.ok(approvals.changeOwner(approvals.requireOperator(adminId(auth)),id,body.email(),body.note()));
    }

    @GetMapping("/stores/{id}/approval-events") ApiResponse<?> events(@PathVariable Long id,Authentication auth){
        approvals.requireOperator(adminId(auth));
        return ApiResponse.ok(approvals.events(id));
    }

    /** HMAC 키 회전 중 1회. 이전 키를 폐기하기 전에 남은 해시를 현재 키로 옮긴다. */
    @PostMapping("/phone-hash/rehash") ApiResponse<?> rehash(Authentication auth){
        approvals.requireOperator(adminId(auth));
        return ApiResponse.ok(phoneHashes.rehashAll());
    }

    private Map<String,Object> view(Store s){
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("id",s.id);out.put("name",s.name);
        out.put("businessNumber",s.businessNumber==null?"":s.businessNumber);
        out.putAll(approvals.owner(s.id));
        out.put("status",s.status.name());out.put("createdAt",s.createdAt);
        out.put("note",approvals.rejectionNote(s.id));
        return out;
    }
    private Long adminId(Authentication a){return a==null?null:(Long)a.getPrincipal();}
}
