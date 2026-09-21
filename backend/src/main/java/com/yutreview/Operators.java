package com.yutreview;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 운영자 계정 자체를 다루는 자리.
 *
 * {@link StoreApproval}은 운영자가 **매장에** 하는 일을 다룬다. 여기는 운영자가 **사람에게**
 * 하는 일이다. 누구를 운영자로 만들고, 누구에게서 그 권한을 걷는가. 매장 심사보다 되돌리기
 * 어려운 동작이라 같은 강도의 증거를 남긴다.
 */
enum OperatorAuditAction { OPERATOR_CREATED, OPERATOR_GRANTED, OPERATOR_REVOKED }

/**
 * 매장에 매이지 않는 운영자 동작의 append-only 증거.
 *
 * {@link StoreApprovalEvent}에 끼워 넣지 않는다. 그쪽은 `store_id`가 NOT NULL이라 매장 없는
 * 사건을 표현할 수 없고, 억지로 매장 하나를 붙이면 그 매장의 이력이 거짓이 된다.
 */
@Entity @Table(name="operator_audit_events",indexes=@Index(columnList="created_at"))
class OperatorAuditEvent {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) Long id;
    /** 누가 했나. 계정이 지워져도 사건은 남아야 하므로 nullable이다. */
    @ManyToOne @JoinColumn(name="actor_admin_user_id") AdminUser actor;
    /** 누구에게 했나. 같은 이유로 nullable. */
    @ManyToOne @JoinColumn(name="target_admin_user_id") AdminUser target;
    /**
     * 대상의 이메일을 사건 시점 값으로 동결한다. FK만 두면 계정이 지워졌을 때 "누구였는지"가
     * 사라져서 감사 로그가 쓸모없어진다. 쿠폰이 상품명을 동결하는 것과 같은 이유다.
     */
    @Column(name="target_email",length=255) String targetEmail;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=30) OperatorAuditAction action;
    @Column(length=200) String note;
    @Column(name="created_at",nullable=false) Instant createdAt;
}

interface OperatorAuditEventRepository extends JpaRepository<OperatorAuditEvent,Long> {
    List<OperatorAuditEvent> findTop200ByOrderByCreatedAtDescIdDesc();
}

@Service class OperatorAccountService {
    private final AdminUserRepository admins;private final MembershipRepository memberships;
    private final OperatorAuditEventRepository audits;private final StoreApprovalEventRepository approvalEvents;
    private final PasswordEncoder encoder;private final Clock clock;
    OperatorAccountService(AdminUserRepository admins,MembershipRepository memberships,
        OperatorAuditEventRepository audits,StoreApprovalEventRepository approvalEvents,
        PasswordEncoder encoder,Clock clock){
        this.admins=admins;this.memberships=memberships;this.audits=audits;
        this.approvalEvents=approvalEvents;this.encoder=encoder;this.clock=clock;
    }

    @Transactional(readOnly=true) AdminController.PageView<Map<String,Object>> list(String query,int page,int size){
        if(page<0||size<1||size>100)
            throw new AppException("INVALID_REQUEST","page는 0 이상, size는 1~100이어야 합니다.");
        PageRequest request=PageRequest.of(page,size,Sort.by(Sort.Direction.DESC,"createdAt"));
        String q=query==null?"":query.trim().toLowerCase();
        Page<AdminUser> found=q.isEmpty()
            ? admins.findAll(request)
            : admins.findByEmailContainingIgnoreCaseOrNameContainingIgnoreCase(q,q,request);
        Page<Map<String,Object>> view=found.map(this::view);
        return new AdminController.PageView<>(view.getContent(),view.getNumber(),view.getSize(),
            view.getTotalElements(),view.getTotalPages());
    }

    /**
     * 운영자 계정 신설.
     *
     * 매장을 만들지 않는다. 운영자는 어느 매장의 멤버도 아니어야 한다 — 멤버가 되면 자기 매장을
     * 스스로 심사할 수 있게 되고, 그 순간 승인 절차가 형식이 된다.
     */
    @Transactional Map<String,Object> create(AdminUser actor,String rawEmail,String name,
            String password,String passwordConfirm,String note){
        String email=Inputs.email(rawEmail);
        String displayName=Inputs.required(name,"이름을 입력해 주세요.");
        Inputs.password(password,passwordConfirm);
        if(admins.existsByEmail(email))throw new AppException("DUPLICATE_EMAIL","이미 가입된 이메일입니다.");
        Instant now=clock.instant();
        AdminUser created=new AdminUser();
        created.email=email;created.passwordHash=encoder.encode(password);created.name=displayName;
        created.role=AdminRole.SYSTEM_ADMIN;created.createdAt=now;
        admins.save(created);
        record(actor,created,OperatorAuditAction.OPERATOR_CREATED,note,now);
        return view(created);
    }

    /** 기존 매장 사장을 운영자로 올린다. 이미 운영자면 아무 일도 하지 않는다(멱등). */
    @Transactional Map<String,Object> grant(AdminUser actor,Long targetId,String note){
        AdminUser target=require(targetId);
        if(target.role==AdminRole.SYSTEM_ADMIN)return changed(target,false);
        target.role=AdminRole.SYSTEM_ADMIN;
        record(actor,target,OperatorAuditAction.OPERATOR_GRANTED,note,clock.instant());
        return changed(target,true);
    }

    /**
     * 운영자 권한 회수.
     *
     * 두 가지를 막는다. 자기 자신을 내리는 것과, 마지막 운영자를 내리는 것. 둘 다 아무도 승인을
     * 할 수 없는 상태를 만들고, 그 상태에서는 복구할 API 자체가 없어서 DB를 직접 고쳐야 한다.
     */
    @Transactional Map<String,Object> revoke(AdminUser actor,Long targetId,String note){
        AdminUser target=require(targetId);
        if(target.role!=AdminRole.SYSTEM_ADMIN)return changed(target,false);
        if(actor.id.equals(target.id))
            throw new AppException("OPERATOR_SELF_REVOKE","자신의 운영자 권한은 회수할 수 없습니다.");
        if(admins.countByRole(AdminRole.SYSTEM_ADMIN)<=1)
            throw new AppException("OPERATOR_LAST_ONE","마지막 운영자는 회수할 수 없습니다.");
        target.role=AdminRole.STORE_ADMIN;
        record(actor,target,OperatorAuditAction.OPERATOR_REVOKED,note,clock.instant());
        return changed(target,true);
    }

    /**
     * 매장 심사와 계정 변경을 한 줄로 합친 활동 피드.
     *
     * 두 테이블을 합치는 것은 메모리에서 한다. 각각 상한이 걸린 최근 목록이고 운영자 동작은
     * 하루 수십 건 규모다. 여기에 UNION 뷰나 공통 상위 테이블을 만들면 스키마만 복잡해진다.
     * 이 화면이 느려지면 그때가 규모를 다시 생각할 때다.
     */
    @Transactional(readOnly=true) List<Map<String,Object>> feed(){
        List<Map<String,Object>> out=new ArrayList<>();
        for(OperatorAuditEvent e:audits.findTop200ByOrderByCreatedAtDescIdDesc()){
            Map<String,Object> item=new LinkedHashMap<>();
            item.put("kind","ACCOUNT");
            item.put("action",e.action.name());
            item.put("actor",e.actor==null?null:e.actor.email);
            item.put("target",e.targetEmail);
            item.put("note",e.note);
            item.put("createdAt",e.createdAt);
            out.add(item);
        }
        for(StoreApprovalEvent e:approvalEvents.findTop200ByOrderByCreatedAtDescIdDesc()){
            Map<String,Object> item=new LinkedHashMap<>();
            item.put("kind","STORE");
            item.put("action",e.action.name());
            item.put("actor",e.actor==null?null:e.actor.email);
            item.put("target",e.store==null?null:e.store.name);
            item.put("storeId",e.store==null?null:e.store.id);
            item.put("note",e.note);
            item.put("createdAt",e.createdAt);
            out.add(item);
        }
        out.sort(Comparator.comparing((Map<String,Object> m)->(Instant)m.get("createdAt")).reversed());
        return out.size()>200?out.subList(0,200):out;
    }

    private AdminUser require(Long id){
        AdminUser found=id==null?null:admins.findById(id).orElse(null);
        if(found==null)throw new AppException("ADMIN_NOT_FOUND","계정을 찾을 수 없습니다.",HttpStatus.NOT_FOUND);
        return found;
    }

    private void record(AdminUser actor,AdminUser target,OperatorAuditAction action,String note,Instant at){
        OperatorAuditEvent e=new OperatorAuditEvent();
        e.actor=actor;e.target=target;e.targetEmail=target.email;e.action=action;
        e.note=note==null||note.isBlank()?null:note.trim();e.createdAt=at;
        audits.save(e);
    }

    private Map<String,Object> changed(AdminUser a,boolean changed){
        Map<String,Object> out=new LinkedHashMap<>(view(a));
        out.put("changed",changed);
        return out;
    }

    /** 비밀번호 해시는 절대 내보내지 않는다. 화면이 쓰는 값만 담는다. */
    private Map<String,Object> view(AdminUser a){
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("id",a.id);
        out.put("email",a.email);
        out.put("name",a.name);
        out.put("role",a.role.name());
        out.put("storeCount",memberships.countByAdminId(a.id));
        out.put("createdAt",a.createdAt);
        return out;
    }
}

/**
 * 운영자 계정 API.
 *
 * {@link OperatorController}와 같은 `/api/admin/operator` 아래지만 파일을 나눠 둔다. 매장 심사와
 * 계정 관리는 바뀌는 이유가 다르고, 한 파일에 두면 심사 로직을 고치다 권한 로직을 건드리게 된다.
 */
@RestController @RequestMapping("/api/admin/operator") class OperatorAccountController {
    private final StoreApprovalService approvals;private final OperatorAccountService accounts;
    OperatorAccountController(StoreApprovalService approvals,OperatorAccountService accounts){
        this.approvals=approvals;this.accounts=accounts;
    }

    record CreateBody(@NotBlank @Size(max=255) String email,@NotBlank @Size(max=100) String name,
        @NotBlank @Size(max=100) String password,@NotBlank @Size(max=100) String passwordConfirm,
        @Size(max=200) String note){}
    record NoteBody(@Size(max=200) String note){}

    @GetMapping("/admins") ApiResponse<?> list(@RequestParam(required=false) String q,
        @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size,Authentication auth){
        approvals.requireOperator(adminId(auth));
        return ApiResponse.ok(accounts.list(q,page,size));
    }

    @PostMapping("/admins") ApiResponse<?> create(@Valid @RequestBody CreateBody body,Authentication auth){
        AdminUser actor=approvals.requireOperator(adminId(auth));
        return ApiResponse.ok(accounts.create(actor,body.email(),body.name(),
            body.password(),body.passwordConfirm(),body.note()));
    }

    @PostMapping("/admins/{id}/grant") ApiResponse<?> grant(@PathVariable Long id,
        @Valid @RequestBody(required=false) NoteBody body,Authentication auth){
        AdminUser actor=approvals.requireOperator(adminId(auth));
        return ApiResponse.ok(accounts.grant(actor,id,body==null?null:body.note()));
    }

    @PostMapping("/admins/{id}/revoke") ApiResponse<?> revoke(@PathVariable Long id,
        @Valid @RequestBody(required=false) NoteBody body,Authentication auth){
        AdminUser actor=approvals.requireOperator(adminId(auth));
        return ApiResponse.ok(accounts.revoke(actor,id,body==null?null:body.note()));
    }

    /** 매장 심사와 계정 변경을 시간순으로 합친 활동 피드. */
    @GetMapping("/audit") ApiResponse<?> audit(Authentication auth){
        approvals.requireOperator(adminId(auth));
        return ApiResponse.ok(accounts.feed());
    }

    private Long adminId(Authentication a){return a==null?null:(Long)a.getPrincipal();}
}

/**
 * 첫 운영자 한 명.
 *
 * 운영자를 만드는 API는 운영자만 쓸 수 있으므로 첫 한 명은 밖에서 넣어야 한다. {@link Bootstrap}을
 * 쓰지 않는 이유는 그쪽이 매장까지 만들기 때문이다. 운영자는 어느 매장의 멤버도 아니어야 한다.
 *
 * 한 번 만들어지면 다시 만들지 않는다. 비밀번호를 바꾸는 용도로 쓸 수 없고, 그래야 환경 변수가
 * 서버에 남아 있다는 이유만으로 계정이 조용히 되돌려지는 일이 없다.
 *
 * 값은 로그에 찍지 않는다. 기동 로그는 채팅과 이슈로 복사되는 경로다.
 */
@Component class OperatorBootstrap implements CommandLineRunner {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(OperatorBootstrap.class);
    private final String email,password,name;
    private final AdminUserRepository admins;private final PasswordEncoder encoder;private final Clock clock;
    OperatorBootstrap(@Value("${app.operator-bootstrap.email:}") String email,
        @Value("${app.operator-bootstrap.password:}") String password,
        @Value("${app.operator-bootstrap.name:소담랩스 운영자}") String name,
        AdminUserRepository admins,PasswordEncoder encoder,Clock clock){
        this.email=email;this.password=password;this.name=name;
        this.admins=admins;this.encoder=encoder;this.clock=clock;
    }

    @Override @Transactional public void run(String... args){
        if(email==null||email.isBlank()||password==null||password.isBlank())return;
        // 이미 운영자가 있으면 손대지 않는다. 계정 목록이 아니라 역할로 본다 — 이메일만 보면
        // 운영자가 권한을 잃은 뒤 재기동에서 조용히 되돌아온다.
        if(admins.countByRole(AdminRole.SYSTEM_ADMIN)>0)return;
        String normalized=Inputs.email(email);
        if(admins.existsByEmail(normalized)){
            log.warn("operator bootstrap skipped: the account already exists but is not an operator");
            return;
        }
        Inputs.password(password,password);
        AdminUser a=new AdminUser();
        a.email=normalized;a.passwordHash=encoder.encode(password);
        a.name=Inputs.required(name,"이름을 입력해 주세요.");
        a.role=AdminRole.SYSTEM_ADMIN;a.createdAt=clock.instant();
        admins.save(a);
        log.info("operator bootstrap created the first operator account");
    }
}
