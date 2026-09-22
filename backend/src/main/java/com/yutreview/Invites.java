package com.yutreview;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 지인 추천용 초대코드.
 *
 * 계정마다 하나다. 한 사장이 매장을 세 곳 운영해도 코드는 하나 — "내 코드를 알려 준다"가
 * 사람 단위의 행동이라 그렇다. 매장별로 나누면 사장이 코드를 세 개 갖게 되고, 어느 것을 줘야
 * 하는지 매번 생각해야 한다.
 *
 * <b>리워드는 아직 없다.</b> 지금 하는 일은 "누가 누구를 데려왔는가"를 기록하는 것뿐이다.
 * 정책이 생기면 `admin_users.invited_by_admin_user_id`를 세면 된다.
 */
@Service class InviteCodeService {
    /**
     * 헷갈리는 글자를 뺀 32자. 0/O, 1/I/L이 없다.
     *
     * 코드는 전화로 불러 주거나 문자로 받아 손으로 옮겨 적는 값이다. 사람이 옮기다 틀리는
     * 것이 이 기능의 가장 흔한 실패이고, 그 대부분이 이 글자쌍에서 난다.
     */
    private static final String ALPHABET="ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    static final int LENGTH=6;
    /** 31^6 ≈ 8.8억. 충돌은 거의 없지만 유니크 제약이 있으니 몇 번 다시 뽑는다. */
    private static final int MAX_ATTEMPTS=8;

    /**
     * 코드 조회의 IP 한도.
     *
     * 가입 화면이 입력한 코드를 즉시 확인해 주려면 공개 조회가 필요한데, 그대로 두면 코드를
     * 전수로 훑어 유효한 값을 모을 수 있다. 사람이 코드 하나를 입력하는 데 분당 30번이 필요하지는 않다.
     */
    private static final int LOOKUPS_PER_IP_PER_MINUTE=30;

    private final AdminUserRepository admins;private final RateLimitService rateLimits;private final SecureRandom random;
    InviteCodeService(AdminUserRepository admins,RateLimitService rateLimits,SecureRandom random){
        this.admins=admins;this.rateLimits=rateLimits;this.random=random;
    }

    /**
     * 이 계정의 코드. 없으면 그 자리에서 만든다.
     *
     * 가입 시점에 만들지만, 이 기능이 생기기 전에 가입한 계정은 비어 있다. 백필 스크립트를
     * 따로 두지 않는 이유는 계정이 자기 코드를 처음 보는 순간이 곧 코드가 필요해지는 순간이라서다.
     */
    @Transactional String codeFor(Long adminId){
        AdminUser admin=admins.findById(adminId)
            .orElseThrow(()->new AppException("AUTH_REQUIRED","로그인이 필요합니다.",HttpStatus.UNAUTHORIZED));
        if(admin.inviteCode==null||admin.inviteCode.isBlank())admin.inviteCode=assign();
        return admin.inviteCode;
    }

    /** 가입 시 새 계정에 붙일 코드. 아직 저장되지 않은 엔티티에 쓰므로 조회를 하지 않는다. */
    String issue(){return assign();}

    /**
     * 코드가 가리키는 계정. 없으면 던진다.
     *
     * 대소문자를 가리지 않는다. 사람이 문자로 받은 코드를 소문자로 옮겨 적는 일이 흔하고,
     * 그걸 "존재하지 않는 코드"로 막으면 맞는 코드를 들고도 못 쓴다.
     */
    @Transactional(readOnly=true) AdminUser resolve(String raw){
        return find(raw).orElseThrow(()->new AppException("INVITE_CODE_NOT_FOUND","존재하지 않는 초대코드예요."));
    }

    /** 가입 화면이 입력 즉시 확인할 때. 존재 여부만 돌려주고 누구 것인지는 말하지 않는다. */
    @Transactional(readOnly=true) boolean exists(String raw,String clientIp){
        rateLimits.check("invite-lookup:"+clientIp,LOOKUPS_PER_IP_PER_MINUTE,Duration.ofMinutes(1),
            "RATE_LIMITED","요청이 너무 많아요. 잠시 후 다시 시도해 주세요.");
        return find(raw).isPresent();
    }

    /** 대문자로 맞추고 길이를 본다. 형식이 틀리면 조회할 것도 없다. */
    static String normalize(String raw){
        return raw==null?"":raw.trim().toUpperCase().replaceAll("[^A-Z0-9]","");
    }

    private Optional<AdminUser> find(String raw){
        String code=normalize(raw);
        if(code.length()!=LENGTH)return Optional.empty();
        return admins.findByInviteCode(code);
    }

    private String assign(){
        for(int attempt=0;attempt<MAX_ATTEMPTS;attempt++){
            String candidate=generate();
            if(!admins.existsByInviteCode(candidate))return candidate;
        }
        // 여기까지 오면 코드 공간이 찼거나 난수가 고장난 것이다. 둘 다 조용히 넘길 일이 아니다.
        throw new AppException("INVITE_CODE_UNAVAILABLE","초대코드를 만들지 못했습니다. 잠시 후 다시 시도해 주세요.",
            HttpStatus.SERVICE_UNAVAILABLE);
    }

    private String generate(){
        StringBuilder out=new StringBuilder(LENGTH);
        for(int i=0;i<LENGTH;i++)out.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        return out.toString();
    }
}
