package com.yutreview;

import jakarta.mail.internet.MimeMessage;
import jakarta.persistence.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.http.HttpStatus;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** 시스템 운영자 이메일 OTP. 일반 매장 관리자 인증과 수명·진입점을 분리한다. */
@Entity @Table(name="operator_login_challenges",indexes={
    @Index(columnList="token_hash",unique=true),@Index(columnList="expires_at")})
class OperatorLoginChallenge {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) Long id;
    @ManyToOne(optional=false,fetch=FetchType.LAZY) AdminUser admin;
    @Column(name="token_hash",nullable=false,unique=true,length=64) String tokenHash;
    @Column(name="code_hash",nullable=false,length=64) String codeHash;
    @Column(nullable=false) int attempts;
    @Column(name="created_at",nullable=false) Instant createdAt;
    @Column(name="expires_at",nullable=false) Instant expiresAt;
    @Column(name="used_at") Instant usedAt;
}

interface OperatorLoginChallengeRepository extends JpaRepository<OperatorLoginChallenge,Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select c from OperatorLoginChallenge c join fetch c.admin where c.tokenHash=:tokenHash")
    Optional<OperatorLoginChallenge> findForUpdate(@Param("tokenHash") String tokenHash);
    @Modifying @org.springframework.data.jpa.repository.Query("delete from OperatorLoginChallenge c where c.expiresAt < :now")
    int purge(@Param("now") Instant now);
}

@Service class OperatorOtpMailService {
    private final JavaMailSender sender;private final String from;private final String password;
    OperatorOtpMailService(JavaMailSender sender,@Value("${spring.mail.username:}") String from,
            @Value("${spring.mail.password:}") String password){this.sender=sender;this.from=from;this.password=password;}

    void send(String to,String code,long ttlSeconds){
        if(from.isBlank()||password.isBlank())throw unavailable();
        try{
            MimeMessage message=sender.createMimeMessage();
            MimeMessageHelper helper=new MimeMessageHelper(message,"UTF-8");
            helper.setFrom(from);helper.setTo(to);helper.setSubject("[소담한판] 시스템 운영자 로그인 인증번호");
            helper.setText("시스템 운영자 로그인 인증번호는 "+code+"입니다. "
                +ttlSeconds+"초 안에 입력해 주세요.\n본인이 요청하지 않았다면 이 메일을 무시해 주세요.");
            sender.send(message);
        }catch(Exception e){throw unavailable();}
    }

    private static AppException unavailable(){return new AppException("OPERATOR_OTP_EMAIL_UNAVAILABLE",
        "운영자 인증 메일을 보내지 못했습니다. 잠시 후 다시 시도해 주세요.",HttpStatus.SERVICE_UNAVAILABLE);}
}

@Service class OperatorOtpAuthService {
    static final int MAX_ATTEMPTS=5;
    private final AdminUserRepository admins;private final OperatorLoginChallengeRepository challenges;
    private final OperatorOtpMailService mail;private final RateLimitService rateLimits;private final JwtService jwt;
    private final SecureRandom random;private final Clock clock;private final Duration otpTtl;

    OperatorOtpAuthService(AdminUserRepository admins,OperatorLoginChallengeRepository challenges,
        OperatorOtpMailService mail,RateLimitService rateLimits,JwtService jwt,SecureRandom random,Clock clock,
        @Value("${app.operator-auth.otp-ttl-seconds:120}") long otpTtlSeconds){
        if(otpTtlSeconds<30||otpTtlSeconds>600)
            throw new IllegalArgumentException("OPERATOR_OTP_TTL_SECONDS must be between 30 and 600");
        this.admins=admins;this.challenges=challenges;this.mail=mail;this.rateLimits=rateLimits;
        this.jwt=jwt;this.random=random;this.clock=clock;this.otpTtl=Duration.ofSeconds(otpTtlSeconds);
    }

    @Transactional Map<String,Object> request(String rawEmail,String ip){
        String email=Inputs.email(rawEmail);String client=ip==null||ip.isBlank()?"unknown":ip;
        rateLimits.check("operator-otp-ip:"+client,5,Duration.ofMinutes(15),"OPERATOR_OTP_RATE_LIMITED",
            "인증 요청이 너무 많습니다. 15분 후 다시 시도해 주세요.");
        rateLimits.check("operator-otp-email:"+email,5,Duration.ofMinutes(15),"OPERATOR_OTP_RATE_LIMITED",
            "인증 요청이 너무 많습니다. 15분 후 다시 시도해 주세요.");

        String token=Tokens.random();
        AdminUser admin=admins.findByEmail(email).filter(a->a.role==AdminRole.SYSTEM_ADMIN).orElse(null);
        if(admin!=null){
            String code=Integer.toString(100000+random.nextInt(900000));Instant now=clock.instant();
            OperatorLoginChallenge challenge=new OperatorLoginChallenge();challenge.admin=admin;
            challenge.tokenHash=hash(token);challenge.codeHash=hash(token+":"+code);
            challenge.createdAt=now;challenge.expiresAt=now.plus(otpTtl);challenges.save(challenge);
            try{mail.send(admin.email,code,otpTtl.toSeconds());}
            catch(RuntimeException e){challenges.delete(challenge);throw e;}
        }
        // 계정 존재 여부와 역할을 응답으로 구분하지 않는다.
        return Map.of("challengeToken",token,"maskedEmail",mask(email),
            "expiresInSeconds",otpTtl.toSeconds());
    }

    @Transactional(noRollbackFor=AppException.class) Map<String,Object> verify(String token,String code){
        String safeToken=token==null?"":token;String safeCode=code==null?"":code;
        OperatorLoginChallenge challenge=challenges.findForUpdate(hash(safeToken)).orElseThrow(OperatorOtpAuthService::invalid);
        Instant now=clock.instant();
        if(challenge.usedAt!=null||!now.isBefore(challenge.expiresAt)||challenge.attempts>=MAX_ATTEMPTS
            ||challenge.admin.role!=AdminRole.SYSTEM_ADMIN)throw invalid();
        challenge.attempts++;
        if(!MessageDigest.isEqual(challenge.codeHash.getBytes(StandardCharsets.UTF_8),
                hash(safeToken+":"+safeCode).getBytes(StandardCharsets.UTF_8)))throw invalid();
        challenge.usedAt=now;
        String accessToken=jwt.issueOperator(challenge.admin);
        return Map.of("accessToken",accessToken,"tokenType","Bearer",
            "expiresInSeconds",jwt.operatorSessionTtlSeconds(),
            "expiresAt",now.plusSeconds(jwt.operatorSessionTtlSeconds()));
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedDelay=600_000,initialDelay=600_000)
    @Transactional void purgeExpired(){challenges.purge(clock.instant());}

    private static String mask(String email){int at=email.indexOf('@');String local=email.substring(0,at);
        return local.substring(0,Math.min(2,local.length()))+"***"+email.substring(at);}
    static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
        .digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private static AppException invalid(){return new AppException("OPERATOR_OTP_INVALID",
        "인증번호가 올바르지 않거나 만료되었습니다.",HttpStatus.UNAUTHORIZED);}
}

@RestController @RequestMapping("/api/admin/operator-auth") class OperatorOtpAuthController {
    private final OperatorOtpAuthService auth;private final ClientIpResolver clientIps;
    OperatorOtpAuthController(OperatorOtpAuthService auth,ClientIpResolver clientIps){this.auth=auth;this.clientIps=clientIps;}
    record RequestBody(@NotBlank @Size(max=255) String email){}
    record VerifyBody(@NotBlank @Size(max=200) String challengeToken,
        @NotBlank @Pattern(regexp="\\d{6}") String code){}
    @PostMapping("/request") ApiResponse<?> request(@Valid @org.springframework.web.bind.annotation.RequestBody RequestBody body,
        HttpServletRequest request){return ApiResponse.ok(auth.request(body.email(),clientIps.resolve(request)));}
    @PostMapping("/verify") ApiResponse<?> verify(@Valid @org.springframework.web.bind.annotation.RequestBody VerifyBody body){
        return ApiResponse.ok(auth.verify(body.challengeToken(),body.code()));}
}
