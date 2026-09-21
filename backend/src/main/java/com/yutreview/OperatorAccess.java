package com.yutreview;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 운영자 콘솔 접근 통제.
 *
 * 운영자 계정 하나면 모든 매장의 승인·거부·소유권 이전과 모든 손님의 참여 집계에 닿는다.
 * 비밀번호 하나가 그 전부를 여는 상태를 두지 않는다.
 *
 * 두 겹이다.
 *
 *   1. 허용 IP  — 사무실·집처럼 미리 정한 곳에서는 그냥 열린다.
 *   2. 기기 인증 — 그 밖에서는 등록된 기기의 WebAuthn 서명이 있어야 열린다.
 *      휴대폰은 지문·얼굴로 풀고, 개인키가 기기 보안칩을 벗어나지 않아서 훔쳐 갈 수가 없다.
 *
 * **잠금 탈출구는 `OPERATOR_ACCESS_ENABLED=false`뿐이다.** 가정용 인터넷 IP는 바뀌고, 기기는
 * 잃어버린다. 둘 다 일어나면 화면으로는 복구할 방법이 없다. 서버 SSH는 이미 신뢰의 뿌리라
 * 여기에 탈출구를 두는 것이 새 구멍을 만들지 않는다. 이 변수를 지우지 말 것.
 */
@Component class OperatorAccessPolicy {
    private final boolean enabled;private final List<ClientIpResolver.Cidr> allowed;private final String rpId;private final String origin;

    OperatorAccessPolicy(@Value("${app.operator-access.enabled:false}") boolean enabled,
        @Value("${app.operator-access.allowed-cidrs:}") String cidrs,
        @Value("${app.operator-access.rp-id:}") String rpId,
        @Value("${app.public-origin:}") String publicOrigin){
        this.enabled=enabled;
        this.allowed=ClientIpResolver.parseCidrs(cidrs);
        this.origin=PublicOriginResolver.normalize(publicOrigin);
        // rpId는 origin의 호스트다. 둘이 어긋나면 브라우저가 등록 자체를 거부한다.
        this.rpId=!rpId.isBlank()?rpId.trim()
            :origin.isEmpty()?"localhost":URI.create(origin).getHost();
    }

    boolean enabled(){return enabled;}
    String rpId(){return rpId;}
    String origin(){return origin;}
    boolean allowedIp(String ip){return ClientIpResolver.matches(allowed,ip);}
    boolean hasAllowlist(){return !allowed.isEmpty();}
}

/** 등록된 운영자 기기. 공개키만 보관한다 — 개인키는 기기 밖으로 나오지 않는다. */
@Entity @Table(name="operator_devices",indexes=@Index(columnList="admin_user_id"))
class OperatorDevice {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) Long id;
    @Column(name="admin_user_id",nullable=false) Long adminId;
    /** base64url. 브라우저가 assertion에서 이 값으로 자기를 밝힌다. */
    @Column(name="credential_id",nullable=false,unique=true,length=500) String credentialId;
    /**
     * SPKI(X.509) DER을 base64로. CBOR/COSE를 직접 파싱하지 않는 이유가 여기 있다 —
     * 브라우저의 `getPublicKey()`가 이 형식으로 바로 주고 Java KeyFactory가 그대로 읽는다.
     */
    @Column(name="public_key_spki",nullable=false,columnDefinition="text") String publicKeySpki;
    /** COSE 알고리즘 식별자. -7=ES256, -257=RS256. */
    @Column(name="algorithm",nullable=false) int algorithm;
    @Column(name="sign_count",nullable=false) long signCount;
    @Column(nullable=false,length=60) String name;
    @Column(name="created_at",nullable=false) Instant createdAt;
    @Column(name="last_used_at") Instant lastUsedAt;
}

interface OperatorDeviceRepository extends JpaRepository<OperatorDevice,Long> {
    List<OperatorDevice> findByAdminIdOrderByCreatedAtAsc(Long adminId);
    Optional<OperatorDevice> findByCredentialId(String credentialId);
    long countByAdminId(Long adminId);
}

enum OperatorChallengePurpose { REGISTER, AUTHENTICATE }

/**
 * 1회용 WebAuthn 챌린지.
 *
 * 인메모리 맵으로 두지 않는다. 재기동에 사라지는 것은 괜찮지만, 만료도 상한도 없어서 서로 다른
 * 키가 들어올수록 메모리가 단조 증가한다. `rate_counters`에서 이미 같은 실수를 했다.
 */
@Entity @Table(name="operator_challenges",indexes=@Index(columnList="expires_at"))
class OperatorChallenge {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) Long id;
    @Column(name="admin_user_id",nullable=false) Long adminId;
    @Column(nullable=false,unique=true,length=100) String challenge;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=20) OperatorChallengePurpose purpose;
    @Column(name="created_at",nullable=false) Instant createdAt;
    @Column(name="expires_at",nullable=false) Instant expiresAt;
}

interface OperatorChallengeRepository extends JpaRepository<OperatorChallenge,Long> {
    Optional<OperatorChallenge> findByChallenge(String challenge);
    @Modifying @Query("delete from OperatorChallenge c where c.expiresAt < :now")
    int purge(@Param("now") Instant now);
}

/**
 * 기기 인증을 통과한 뒤의 짧은 통행증.
 *
 * 평문은 저장하지 않고 SHA-256만 남긴다. 쿠폰 회수 티켓과 같은 이유다 — DB를 읽을 수 있는
 * 사람이 통행증을 그대로 쓸 수 있으면 안 된다.
 */
@Entity @Table(name="operator_sessions",indexes=@Index(columnList="expires_at"))
class OperatorSession {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) Long id;
    @Column(name="admin_user_id",nullable=false) Long adminId;
    @Column(name="token_hash",nullable=false,unique=true,length=100) String tokenHash;
    @Column(name="device_id") Long deviceId;
    @Column(name="created_at",nullable=false) Instant createdAt;
    @Column(name="expires_at",nullable=false) Instant expiresAt;
}

interface OperatorSessionRepository extends JpaRepository<OperatorSession,Long> {
    Optional<OperatorSession> findByTokenHash(String tokenHash);
    @Modifying @Query("delete from OperatorSession s where s.expiresAt < :now")
    int purge(@Param("now") Instant now);
    @Modifying @Query("delete from OperatorSession s where s.deviceId = :deviceId")
    int deleteByDeviceId(@Param("deviceId") Long deviceId);
}

@Service class OperatorAccessService {
    /** 챌린지 수명. 사람이 지문을 대는 시간이라 짧아도 된다. */
    private static final Duration CHALLENGE_TTL=Duration.ofMinutes(5);
    /** 통행증 수명. JWT(8시간)보다 짧게 둬서 기기 인증이 형식이 되지 않게 한다. */
    static final Duration SESSION_TTL=Duration.ofHours(4);
    private static final Base64.Encoder URL=Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder URL_DECODER=Base64.getUrlDecoder();

    private final OperatorDeviceRepository devices;private final OperatorChallengeRepository challenges;
    private final OperatorSessionRepository sessions;private final OperatorAccessPolicy policy;
    private final SecureRandom random;private final Clock clock;private final ObjectMapper json=new ObjectMapper();

    OperatorAccessService(OperatorDeviceRepository devices,OperatorChallengeRepository challenges,
        OperatorSessionRepository sessions,OperatorAccessPolicy policy,SecureRandom random,Clock clock){
        this.devices=devices;this.challenges=challenges;this.sessions=sessions;
        this.policy=policy;this.random=random;this.clock=clock;
    }

    /** 화면이 현재 상태를 알아야 무엇을 요구할지 정할 수 있다. */
    @Transactional(readOnly=true) Map<String,Object> status(Long adminId,String clientIp){
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("enabled",policy.enabled());
        out.put("rpId",policy.rpId());
        out.put("ipAllowed",policy.allowedIp(clientIp));
        out.put("deviceCount",devices.countByAdminId(adminId));
        out.put("devices",list(adminId));
        return out;
    }

    @Transactional(readOnly=true) List<Map<String,Object>> list(Long adminId){
        List<Map<String,Object>> out=new ArrayList<>();
        for(OperatorDevice d:devices.findByAdminIdOrderByCreatedAtAsc(adminId)){
            Map<String,Object> item=new LinkedHashMap<>();
            item.put("id",d.id);item.put("name",d.name);
            item.put("createdAt",d.createdAt);item.put("lastUsedAt",d.lastUsedAt);
            out.add(item);
        }
        return out;
    }

    @Transactional Map<String,Object> challenge(Long adminId,OperatorChallengePurpose purpose){
        byte[] bytes=new byte[32];random.nextBytes(bytes);
        String value=URL.encodeToString(bytes);
        Instant now=clock.instant();
        OperatorChallenge c=new OperatorChallenge();
        c.adminId=adminId;c.challenge=value;c.purpose=purpose;
        c.createdAt=now;c.expiresAt=now.plus(CHALLENGE_TTL);
        challenges.save(c);
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("challenge",value);
        out.put("rpId",policy.rpId());
        out.put("timeoutMs",CHALLENGE_TTL.toMillis());
        if(purpose==OperatorChallengePurpose.AUTHENTICATE){
            List<String> ids=new ArrayList<>();
            for(OperatorDevice d:devices.findByAdminIdOrderByCreatedAtAsc(adminId))ids.add(d.credentialId);
            out.put("allowCredentials",ids);
        }
        return out;
    }

    /**
     * 기기 등록.
     *
     * attestation은 검증하지 않는다. 우리가 알아야 하는 것은 "어느 제조사의 인증기인가"가 아니라
     * "앞으로 이 공개키로 서명하는 쪽만 들여보낸다"는 것뿐이다. 등록 요청 자체가 이미 허용 IP나
     * 유효한 통행증을 지나온 뒤라서, 여기서 막을 대상은 제조사가 아니라 재전송이다.
     */
    @Transactional Map<String,Object> register(Long adminId,String name,String credentialId,
            String publicKeySpki,int algorithm,String clientDataJson){
        String label=Inputs.required(name,"기기 이름을 입력해 주세요.");
        if(label.length()>60)label=label.substring(0,60);
        JsonNode client=verifyClientData(clientDataJson,"webauthn.create",adminId,OperatorChallengePurpose.REGISTER);
        if(client==null)throw new AppException("DEVICE_CHALLENGE_INVALID","기기 등록 시간이 지났습니다. 다시 시도해 주세요.");
        if(devices.findByCredentialId(credentialId).isPresent())
            throw new AppException("DEVICE_ALREADY_REGISTERED","이미 등록된 기기입니다.");
        try{
            publicKey(publicKeySpki,algorithm);
        }catch(Exception e){
            throw new AppException("DEVICE_KEY_INVALID","기기 공개키를 읽을 수 없습니다.");
        }
        Instant now=clock.instant();
        OperatorDevice d=new OperatorDevice();
        d.adminId=adminId;d.credentialId=credentialId;d.publicKeySpki=publicKeySpki;
        d.algorithm=algorithm;d.signCount=0;d.name=label;d.createdAt=now;
        devices.save(d);
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("id",d.id);out.put("name",d.name);out.put("createdAt",d.createdAt);
        out.put("deviceCount",devices.countByAdminId(adminId));
        return out;
    }

    /**
     * 기기 인증. 성공하면 평문 통행증을 **이 한 번만** 돌려준다.
     *
     * 실패 이유를 구분해 주지 않는다. 등록되지 않은 기기인지, 서명이 틀렸는지, 챌린지가 만료됐는지
     * 를 알려 주면 공격자가 무엇을 고쳐야 하는지 배운다.
     */
    @Transactional Map<String,Object> authenticate(Long adminId,String credentialId,
            String clientDataJson,String authenticatorDataB64,String signatureB64){
        OperatorDevice device=devices.findByCredentialId(credentialId).orElse(null);
        if(device==null||!device.adminId.equals(adminId))throw invalid();
        JsonNode client=verifyClientData(clientDataJson,"webauthn.get",adminId,OperatorChallengePurpose.AUTHENTICATE);
        if(client==null)throw invalid();

        byte[] authenticatorData=URL_DECODER.decode(authenticatorDataB64);
        if(authenticatorData.length<37)throw invalid();
        byte[] rpIdHash=sha256(policy.rpId().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        for(int i=0;i<32;i++)if(authenticatorData[i]!=rpIdHash[i])throw invalid();
        // 0번 비트가 User Present. 사용자가 실제로 기기를 만졌다는 표시다.
        if((authenticatorData[32]&0x01)==0)throw invalid();

        try{
            Signature verifier=Signature.getInstance(algorithmName(device.algorithm));
            verifier.initVerify(publicKey(device.publicKeySpki,device.algorithm));
            verifier.update(authenticatorData);
            verifier.update(sha256(clientDataJson.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            if(!verifier.verify(URL_DECODER.decode(signatureB64)))throw invalid();
        }catch(AppException e){
            throw e;
        }catch(Exception e){
            throw invalid();
        }

        // 서명 카운터가 뒤로 가면 복제된 인증기다. 0을 계속 보내는 기기(대부분의 패스키)는
        // 카운터를 쓰지 않는다는 뜻이라 그대로 둔다.
        long counter=((long)(authenticatorData[33]&0xFF)<<24)|((authenticatorData[34]&0xFF)<<16)
            |((authenticatorData[35]&0xFF)<<8)|(authenticatorData[36]&0xFF);
        if(counter!=0&&counter<=device.signCount)throw invalid();
        if(counter!=0)device.signCount=counter;

        Instant now=clock.instant();
        device.lastUsedAt=now;

        byte[] raw=new byte[32];random.nextBytes(raw);
        String token=URL.encodeToString(raw);
        OperatorSession s=new OperatorSession();
        s.adminId=adminId;s.tokenHash=hash(token);s.deviceId=device.id;
        s.createdAt=now;s.expiresAt=now.plus(SESSION_TTL);
        sessions.save(s);

        Map<String,Object> out=new LinkedHashMap<>();
        out.put("deviceToken",token);
        out.put("expiresAt",s.expiresAt);
        out.put("deviceName",device.name);
        return out;
    }

    /** 통행증 검사. 유효하면 그 계정 id를 준다. */
    @Transactional(readOnly=true) Long sessionAdmin(String token){
        if(token==null||token.isBlank())return null;
        OperatorSession s=sessions.findByTokenHash(hash(token)).orElse(null);
        if(s==null||!s.expiresAt.isAfter(clock.instant()))return null;
        return s.adminId;
    }

    /** 기기를 지우면 그 기기로 받은 통행증도 같이 죽는다. 안 그러면 4시간 동안 살아 있다. */
    @Transactional void remove(Long adminId,Long deviceId){
        OperatorDevice d=devices.findById(deviceId).orElse(null);
        if(d==null||!d.adminId.equals(adminId))
            throw new AppException("DEVICE_NOT_FOUND","기기를 찾을 수 없습니다.",HttpStatus.NOT_FOUND);
        // 마지막 기기를 지우는 것은 막지 않는다. 허용 IP가 남아 있을 수 있고, 둘 다 잃으면
        // OPERATOR_ACCESS_ENABLED=false가 탈출구다. 여기서 막으면 잃어버린 기기를 영영 못 지운다.
        sessions.deleteByDeviceId(deviceId);
        devices.delete(d);
    }

    /** 만료된 챌린지와 통행증을 치운다. 두 테이블 다 상한이 없으면 단조 증가한다. */
    @Scheduled(fixedDelay=600_000) @Transactional public void purgeExpired(){
        Instant now=clock.instant();
        challenges.purge(now);
        sessions.purge(now);
    }

    /**
     * clientDataJSON을 검사하고 챌린지를 소모한다.
     *
     * 챌린지는 한 번 쓰면 지운다. 남겨 두면 같은 서명을 다시 보내는 것만으로 통과한다.
     */
    private JsonNode verifyClientData(String clientDataJson,String expectedType,Long adminId,
            OperatorChallengePurpose purpose){
        try{
            JsonNode node=json.readTree(clientDataJson);
            if(!expectedType.equals(node.path("type").asText()))return null;
            String origin=node.path("origin").asText();
            if(!policy.origin().isEmpty()&&!policy.origin().equals(origin))return null;
            String challenge=node.path("challenge").asText();
            OperatorChallenge stored=challenges.findByChallenge(challenge).orElse(null);
            if(stored==null||stored.purpose!=purpose||!stored.adminId.equals(adminId))return null;
            boolean live=stored.expiresAt.isAfter(clock.instant());
            challenges.delete(stored);
            return live?node:null;
        }catch(Exception e){
            return null;
        }
    }

    private static PublicKey publicKey(String spkiBase64,int algorithm) throws Exception {
        byte[] der=Base64.getDecoder().decode(spkiBase64);
        return KeyFactory.getInstance(algorithm==-257?"RSA":"EC")
            .generatePublic(new X509EncodedKeySpec(der));
    }

    private static String algorithmName(int algorithm){
        return switch(algorithm){
            case -257 -> "SHA256withRSA";
            case -35 -> "SHA384withECDSA";
            case -36 -> "SHA512withECDSA";
            default -> "SHA256withECDSA";
        };
    }

    private static AppException invalid(){
        return new AppException("DEVICE_ASSERTION_INVALID","기기 인증에 실패했습니다.",HttpStatus.FORBIDDEN);
    }

    static String hash(String value){return Base64.getEncoder().encodeToString(sha256(
        value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}

    private static byte[] sha256(byte[] input){
        try{return MessageDigest.getInstance("SHA-256").digest(input);}
        catch(Exception e){throw new IllegalStateException("SHA-256 unavailable",e);}
    }
}

/**
 * `/api/admin/operator/**`의 문지기.
 *
 * 컨트롤러의 `requireOperator`보다 앞에 선다. 저쪽은 "이 사람이 운영자인가"를 보고 여기는
 * "이 요청이 믿을 수 있는 곳에서 왔는가"를 본다. 둘 다 있어야 비밀번호 하나로 전부 열리지 않는다.
 *
 * 기기 등록 경로 자체는 여기를 지나야 한다. 안 그러면 아무나 자기 기기를 등록해 통과한다.
 * 그래서 첫 기기는 반드시 **허용 IP에서** 등록한다.
 */
@Component class OperatorAccessFilter extends OncePerRequestFilter {
    static final String HEADER="X-Operator-Device";
    /** 문지기를 지나지 않는 경로. 상태 조회와 기기 인증 자체는 통행증이 없어야 쓸 수 있다. */
    private static final List<String> OPEN=List.of(
        "/api/admin/operator/access/status",
        "/api/admin/operator/access/authenticate-challenge",
        "/api/admin/operator/access/authenticate");

    private final OperatorAccessPolicy policy;private final OperatorAccessService access;
    private final ClientIpResolver clientIps;
    OperatorAccessFilter(OperatorAccessPolicy policy,OperatorAccessService access,ClientIpResolver clientIps){
        this.policy=policy;this.access=access;this.clientIps=clientIps;
    }

    @Override protected boolean shouldNotFilter(HttpServletRequest request){
        if(!policy.enabled())return true;
        String path=request.getRequestURI();
        return !path.startsWith("/api/admin/operator/")||OPEN.contains(path);
    }

    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,
            FilterChain chain) throws ServletException,IOException {
        if(policy.allowedIp(clientIps.resolve(request))){chain.doFilter(request,response);return;}
        Object principal=org.springframework.security.core.context.SecurityContextHolder
            .getContext().getAuthentication()==null?null
            :org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        Long adminId=principal instanceof Long id?id:null;
        Long sessionAdmin=access.sessionAdmin(request.getHeader(HEADER));
        if(adminId!=null&&adminId.equals(sessionAdmin)){chain.doFilter(request,response);return;}
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"success\":false,\"data\":null,\"error\":{\"code\":\"DEVICE_REQUIRED\","
            +"\"message\":\"등록된 기기에서만 사용할 수 있습니다.\"}}");
    }
}

/**
 * 접근 통제 API.
 *
 * `/access/status`, `/access/authenticate-challenge`, `/access/authenticate` 셋은
 * {@link OperatorAccessFilter}를 지나지 않는다. 지나게 하면 기기 인증을 하려면 먼저 기기 인증을
 * 통과해야 하는 순환이 생겨 아무도 들어올 수 없다.
 *
 * 나머지(`/access/devices`, `/access/register*`)는 문지기를 지난다. 그래서 **첫 기기는 반드시
 * 허용 IP에서 등록해야 한다.** 아무 데서나 등록할 수 있으면 기기 인증이 아무것도 막지 않는다.
 */
@org.springframework.web.bind.annotation.RestController
@org.springframework.web.bind.annotation.RequestMapping("/api/admin/operator/access")
class OperatorAccessController {
    private final StoreApprovalService approvals;private final OperatorAccessService access;
    private final ClientIpResolver clientIps;
    OperatorAccessController(StoreApprovalService approvals,OperatorAccessService access,ClientIpResolver clientIps){
        this.approvals=approvals;this.access=access;this.clientIps=clientIps;
    }

    record RegisterBody(@jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max=60) String name,
        @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max=500) String credentialId,
        @jakarta.validation.constraints.NotBlank String publicKey,int algorithm,
        @jakarta.validation.constraints.NotBlank String clientDataJson){}
    record AuthenticateBody(@jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max=500) String credentialId,
        @jakarta.validation.constraints.NotBlank String clientDataJson,
        @jakarta.validation.constraints.NotBlank String authenticatorData,
        @jakarta.validation.constraints.NotBlank String signature){}

    /** 화면이 무엇을 요구해야 할지 정하는 유일한 근거. 통행증 없이도 읽을 수 있어야 한다. */
    @org.springframework.web.bind.annotation.GetMapping("/status")
    ApiResponse<?> status(org.springframework.security.core.Authentication auth,HttpServletRequest request){
        Long adminId=approvals.requireOperator(adminId(auth)).id;
        return ApiResponse.ok(access.status(adminId,clientIps.resolve(request)));
    }

    @org.springframework.web.bind.annotation.GetMapping("/devices")
    ApiResponse<?> devices(org.springframework.security.core.Authentication auth){
        return ApiResponse.ok(access.list(approvals.requireOperator(adminId(auth)).id));
    }

    @org.springframework.web.bind.annotation.PostMapping("/register-challenge")
    ApiResponse<?> registerChallenge(org.springframework.security.core.Authentication auth){
        Long adminId=approvals.requireOperator(adminId(auth)).id;
        return ApiResponse.ok(access.challenge(adminId,OperatorChallengePurpose.REGISTER));
    }

    @org.springframework.web.bind.annotation.PostMapping("/register")
    ApiResponse<?> register(@jakarta.validation.Valid @org.springframework.web.bind.annotation.RequestBody RegisterBody body,
            org.springframework.security.core.Authentication auth){
        Long adminId=approvals.requireOperator(adminId(auth)).id;
        return ApiResponse.ok(access.register(adminId,body.name(),body.credentialId(),
            body.publicKey(),body.algorithm(),body.clientDataJson()));
    }

    @org.springframework.web.bind.annotation.PostMapping("/authenticate-challenge")
    ApiResponse<?> authenticateChallenge(org.springframework.security.core.Authentication auth){
        Long adminId=approvals.requireOperator(adminId(auth)).id;
        return ApiResponse.ok(access.challenge(adminId,OperatorChallengePurpose.AUTHENTICATE));
    }

    @org.springframework.web.bind.annotation.PostMapping("/authenticate")
    ApiResponse<?> authenticate(@jakarta.validation.Valid @org.springframework.web.bind.annotation.RequestBody AuthenticateBody body,
            org.springframework.security.core.Authentication auth){
        Long adminId=approvals.requireOperator(adminId(auth)).id;
        return ApiResponse.ok(access.authenticate(adminId,body.credentialId(),body.clientDataJson(),
            body.authenticatorData(),body.signature()));
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/devices/{id}")
    ApiResponse<?> remove(@org.springframework.web.bind.annotation.PathVariable Long id,
            org.springframework.security.core.Authentication auth){
        Long adminId=approvals.requireOperator(adminId(auth)).id;
        access.remove(adminId,id);
        return ApiResponse.ok(Map.of("removed",true));
    }

    private Long adminId(org.springframework.security.core.Authentication a){
        return a==null?null:(Long)a.getPrincipal();
    }
}
