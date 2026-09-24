package com.yutreview;

import jakarta.mail.internet.MimeMessage;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.mail.javamail.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@Configuration class RecoveryMailConfig {
    @Bean @ConditionalOnMissingBean(JavaMailSender.class) JavaMailSender recoveryJavaMailSender(
        @Value("${spring.mail.host:localhost}") String host,@Value("${spring.mail.port:587}") int port,
        @Value("${spring.mail.username:}") String username,@Value("${spring.mail.password:}") String password,
        @Value("${spring.mail.properties.mail.smtp.auth:true}") boolean auth,
        @Value("${spring.mail.properties.mail.smtp.starttls.enable:true}") boolean starttls,
        @Value("${spring.mail.properties.mail.smtp.starttls.required:true}") boolean starttlsRequired){
        JavaMailSenderImpl sender=new JavaMailSenderImpl();sender.setHost(host);sender.setPort(port);sender.setUsername(username);sender.setPassword(password);
        Properties properties=sender.getJavaMailProperties();properties.put("mail.smtp.auth",Boolean.toString(auth));properties.put("mail.smtp.starttls.enable",Boolean.toString(starttls));properties.put("mail.smtp.starttls.required",Boolean.toString(starttlsRequired));
        return sender;
    }
}

@Service class RecoveryMailService {
    private final JavaMailSender sender;private final String from;private final String password;
    RecoveryMailService(JavaMailSender sender,@Value("${spring.mail.username:}") String from,@Value("${spring.mail.password:}") String password){this.sender=sender;this.from=from;this.password=password;}
    void sendCode(String to,String code){
        if(from.isBlank()||password.isBlank())throw unavailable();
        try{MimeMessage m=sender.createMimeMessage();MimeMessageHelper h=new MimeMessageHelper(m,"UTF-8");h.setFrom(from);h.setTo(to);h.setSubject("[소담한판] 로그인 정보 확인 인증번호");h.setText("소담한판 인증번호는 "+code+"입니다. 10분 안에 입력해 주세요.\n본인이 요청하지 않았다면 이 메일을 무시해 주세요.");sender.send(m);}catch(Exception e){throw unavailable();}
    }
    private static AppException unavailable(){return new AppException("RECOVERY_EMAIL_UNAVAILABLE","인증 메일을 보내지 못했습니다. 잠시 후 다시 시도해 주세요.",HttpStatus.SERVICE_UNAVAILABLE);}
}

@Service class AdminAccountRecoveryService {
    static final Duration TTL=Duration.ofMinutes(10);static final int MAX_ATTEMPTS=5;
    private final StoreRepository stores;private final MembershipRepository memberships;private final AdminRecoveryChallengeRepository challenges;private final PasswordEncoder encoder;private final RateLimitService rateLimits;private final RecoveryMailService mail;private final SecureRandom random;private final Clock clock;
    AdminAccountRecoveryService(StoreRepository stores,MembershipRepository memberships,AdminRecoveryChallengeRepository challenges,PasswordEncoder encoder,RateLimitService rateLimits,RecoveryMailService mail,SecureRandom random,Clock clock){this.stores=stores;this.memberships=memberships;this.challenges=challenges;this.encoder=encoder;this.rateLimits=rateLimits;this.mail=mail;this.random=random;this.clock=clock;}

    Map<String,Object> request(AccountRecoveryPurpose purpose,String email,String businessNumber,String openingDate,String representativeName,String phone,String ip){
        AdminUser admin=identity(email,businessNumber,openingDate,representativeName,phone,ip);
        String token=Tokens.random(),code=Integer.toString(100000+random.nextInt(900000));Instant now=clock.instant();
        AdminRecoveryChallenge c=new AdminRecoveryChallenge();c.admin=admin;c.purpose=purpose;c.tokenHash=hash(token);c.codeHash=hash(token+":"+code);c.createdAt=now;c.expiresAt=now.plus(TTL);challenges.save(c);
        try{mail.sendCode(admin.email,code);}catch(RuntimeException e){challenges.delete(c);throw e;}
        return Map.of("challengeToken",token,"maskedEmail",mask(admin.email),"expiresInSeconds",TTL.toSeconds());
    }

    @Transactional(noRollbackFor=AppException.class) Map<String,Object> verify(AccountRecoveryPurpose purpose,String token,String code,String password,String passwordConfirm){
        AdminRecoveryChallenge c=challenges.findForUpdate(hash(token==null?"":token)).orElseThrow(AdminAccountRecoveryService::invalid);Instant now=clock.instant();
        if(c.usedAt!=null||c.purpose!=purpose||!now.isBefore(c.expiresAt)||c.attempts>=MAX_ATTEMPTS)throw invalid();
        if(purpose==AccountRecoveryPurpose.RESET_PASSWORD)Inputs.password(password,passwordConfirm);
        c.attempts++;
        if(!MessageDigest.isEqual(c.codeHash.getBytes(StandardCharsets.UTF_8),hash(token+":"+code).getBytes(StandardCharsets.UTF_8)))throw invalid();
        c.usedAt=now;
        if(purpose==AccountRecoveryPurpose.RESET_PASSWORD)c.admin.passwordHash=encoder.encode(password);
        return purpose==AccountRecoveryPurpose.FIND_EMAIL?Map.of("email",c.admin.email):Map.of("reset",true);
    }
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay=3_600_000,initialDelay=3_600_000)
    @Transactional void purgeExpired(){challenges.purge(clock.instant());}

    private AdminUser identity(String rawEmail,String businessNumber,String openingDate,String representativeName,String phone,String ip){
        String business=Inputs.businessNumber(businessNumber),date=Inputs.openingDate(openingDate),representative=Inputs.required(representativeName,"대표자 이름을 입력해 주세요."),normalizedPhone=Inputs.phone(phone);
        rateLimits.check("account-recovery-ip:"+(ip==null||ip.isBlank()?"unknown":ip),5,Duration.ofHours(1),"ACCOUNT_RECOVERY_RATE_LIMITED","계정 찾기 요청이 너무 많습니다. 한 시간 후 다시 시도해 주세요.");
        Store store=stores.findByBusinessNumber(business).orElseThrow(AdminAccountRecoveryService::notFound);
        if(store.businessVerifiedAt==null||!date.equals(store.openingDate)||store.representativeName==null||!representative.equals(store.representativeName))throw notFound();
        AdminUser owner=memberships.findByStoreId(store.id).stream().filter(m->m.role==MembershipRole.OWNER&&m.admin.role==AdminRole.STORE_ADMIN).map(m->m.admin).filter(a->normalizedPhone.equals(a.phone)).findFirst().orElseThrow(AdminAccountRecoveryService::notFound);
        if(rawEmail!=null&&!rawEmail.isBlank()&&!owner.email.equals(Inputs.email(rawEmail)))throw notFound();
        return owner;
    }
    private static String mask(String email){int at=email.indexOf('@');String local=email.substring(0,at);return local.substring(0,Math.min(2,local.length()))+"***"+email.substring(at);}
    private static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private static AppException notFound(){return new AppException("ACCOUNT_RECOVERY_NOT_FOUND","입력한 정보와 일치하는 계정을 찾을 수 없습니다.",HttpStatus.NOT_FOUND);}
    private static AppException invalid(){return new AppException("RECOVERY_CODE_INVALID","인증번호가 올바르지 않거나 만료되었습니다.",HttpStatus.BAD_REQUEST);}
}

@RestController @RequestMapping("/api/admin/auth/recovery") class AdminAccountRecoveryController {
    private final AdminAccountRecoveryService recovery;private final ClientIpResolver clientIps;
    AdminAccountRecoveryController(AdminAccountRecoveryService recovery,ClientIpResolver clientIps){this.recovery=recovery;this.clientIps=clientIps;}
    record RecoveryRequest(@NotNull AccountRecoveryPurpose purpose,@Size(max=255) String email,@NotBlank @Size(max=20) String businessNumber,@NotBlank @Size(max=20) String openingDate,@NotBlank @Size(max=50) String representativeName,@NotBlank @Size(max=20) String phone){}
    record VerifyBody(@NotNull AccountRecoveryPurpose purpose,@NotBlank @Size(max=200) String challengeToken,@NotBlank @Size(max=6) String code,@Size(max=128) String password,@Size(max=128) String passwordConfirm){}
    @PostMapping("/request") ApiResponse<?> request(@Valid @RequestBody RecoveryRequest b,HttpServletRequest req){if(b.purpose()==AccountRecoveryPurpose.RESET_PASSWORD&&(b.email()==null||b.email().isBlank()))throw new AppException("INVALID_EMAIL","이메일 주소를 입력해 주세요.");return ApiResponse.ok(recovery.request(b.purpose(),b.email(),b.businessNumber(),b.openingDate(),b.representativeName(),b.phone(),clientIps.resolve(req)));}
    @PostMapping("/verify") ApiResponse<?> verify(@Valid @RequestBody VerifyBody b){return ApiResponse.ok(recovery.verify(b.purpose(),b.challengeToken(),b.code(),b.password(),b.passwordConfirm()));}
}
