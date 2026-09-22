package com.yutreview;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;

import javax.crypto.Mac;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service class PhoneService {
    /** HMAC 키의 최소 길이. 짧은 키는 DB를 한 번 본 사람이 전화번호 전수 대입으로 되돌릴 수 있다. */
    static final int MIN_HMAC_KEY_BYTES=32;
    private final byte[] hmacKey,encryptionKey; private final byte[] previousHmacKey;
    PhoneService(@Value("${app.phone-hmac-key}") String hmacKey,@Value("${app.phone-hmac-previous-key:}") String previousHmacKey,@Value("${app.phone-encryption-key}") String encryptionKey){
        this.hmacKey=requireStrongKey(hmacKey);
        // 회전 중에만 채운다. 레거시 원문 문자열 키도 받아야 기존 해시를 찾을 수 있다.
        this.previousHmacKey=previousHmacKey==null||previousHmacKey.isBlank()?null:legacyKey(previousHmacKey);
        this.encryptionKey=Base64.getDecoder().decode(encryptionKey);if(this.encryptionKey.length!=32)throw new IllegalArgumentException("PHONE_ENCRYPTION_KEY must be a Base64-encoded 32-byte key");
    }
    /**
     * Base64로 디코딩한 32바이트 이상만 받고, 아니면 기동을 멈춘다.
     *
     * 예외 메시지에 값을 절대 넣지 않는다. 기동 실패 로그는 대개 가장 널리 공유되는 로그다.
     */
    private static byte[] requireStrongKey(String value){
        byte[] decoded;
        try{decoded=Base64.getDecoder().decode(value==null?"":value.trim());}
        catch(IllegalArgumentException e){throw new IllegalArgumentException("PHONE_HMAC_SECRET must be Base64-encoded");}
        if(decoded.length<MIN_HMAC_KEY_BYTES)throw new IllegalArgumentException("PHONE_HMAC_SECRET must decode to at least "+MIN_HMAC_KEY_BYTES+" random bytes");
        return decoded;
    }
    /** 이전 키는 강도를 검사하지 않는다. 검사해서 거부하면 그 키로 만든 해시를 영영 못 찾는다. */
    private static byte[] legacyKey(String value){
        String trimmed=value.trim();
        try{return Base64.getDecoder().decode(trimmed);}
        catch(IllegalArgumentException e){return trimmed.getBytes(StandardCharsets.UTF_8);}
    }
    String normalize(String phone){return Inputs.phone(phone);}
    String hash(String phone){return hash(normalize(phone),hmacKey);}
    /**
     * 조회용 해시 목록. 회전 중에는 현재 키와 이전 키 둘 다 봐야 쿨타임과 미사용 쿠폰이 살아 있다.
     * 쓰기는 언제나 {@link #hash} 하나뿐이다.
     */
    List<String> lookupHashes(String phone){
        String normalized=normalize(phone);
        String current=hash(normalized,hmacKey);
        return previousHmacKey==null?List.of(current):List.of(current,hash(normalized,previousHmacKey));
    }
    boolean rotating(){return previousHmacKey!=null;}
    private static String hash(String normalized,byte[] key){try{Mac m=Mac.getInstance("HmacSHA256");m.init(new SecretKeySpec(key,"HmacSHA256"));return HexFormat.of().formatHex(m.doFinal(normalized.getBytes(StandardCharsets.UTF_8)));}catch(GeneralSecurityException e){throw new IllegalStateException(e);}}
    String encrypt(String value){try{byte[] iv=new byte[12];SecureRandom.getInstanceStrong().nextBytes(iv);Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(encryptionKey,"AES"),new GCMParameterSpec(128,iv));byte[] encrypted=cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));byte[] packed=new byte[iv.length+encrypted.length];System.arraycopy(iv,0,packed,0,iv.length);System.arraycopy(encrypted,0,packed,iv.length,encrypted.length);return Base64.getEncoder().encodeToString(packed);}catch(GeneralSecurityException e){throw new IllegalStateException("Personal data encryption failed",e);}}
    String decrypt(String value){if(PrivacyCleanupService.ANONYMIZED.equals(value))return "파기됨";try{byte[] packed=Base64.getDecoder().decode(value),iv=Arrays.copyOfRange(packed,0,12);Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(encryptionKey,"AES"),new GCMParameterSpec(128,iv));return new String(cipher.doFinal(Arrays.copyOfRange(packed,12,packed.length)),StandardCharsets.UTF_8);}catch(GeneralSecurityException|IllegalArgumentException e){throw new IllegalStateException("Personal data decryption failed",e);}}
}
/**
 * 사용자가 손으로 넣는 값을 한 곳에서 정규화한다. 회원가입과 매장 추가가 같은 규칙을 쓰도록
 * 화면마다 정규식을 다시 적지 않는다.
 */
final class Inputs {
    private Inputs(){}
    static String text(String v){return v==null?"":v.trim();}
    static String digits(String v){return v==null?"":v.replaceAll("\\D","");}
    static String lower(String v){return text(v).toLowerCase();}
    /** 휴대전화는 010으로 시작하는 숫자 11자리만 받는다. */
    static String phone(String v){
        String p=digits(v);
        if(!p.matches("010\\d{8}"))throw new AppException("INVALID_PHONE","휴대폰 번호는 010으로 시작하는 숫자 11자리로 입력해 주세요.");
        return p;
    }
    static String businessNumber(String v){
        String b=digits(v);
        if(!b.matches("\\d{10}"))throw new AppException("INVALID_BUSINESS_NUMBER","사업자등록번호는 숫자 10자리로 입력해 주세요.");
        return b;
    }
    static String email(String v){
        String e=lower(v);
        if(!e.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+"))throw new AppException("INVALID_EMAIL","이메일 주소를 확인해 주세요.");
        return e;
    }
    static String required(String v,String message){
        String t=text(v);
        if(t.isEmpty())throw new AppException("INVALID_REQUEST",message);
        return t;
    }
    /**
     * 개업일자. 숫자만 남겨 YYYYMMDD 8자리.
     *
     * 국세청이 하이픈을 받지 않는다. 화면도 막지만 서버가 다시 정규화한다 — 정규식을 화면마다
     * 새로 적지 않는다는 규칙이 여기에도 적용된다.
     */
    static String openingDate(String v){
        String d=digits(v);
        if(!d.matches("(19|20)\\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\\d|3[01])"))
            throw new AppException("INVALID_OPENING_DATE","개업일자를 YYYYMMDD 형식으로 입력해 주세요.");
        return d;
    }
    static void password(String password,String confirm){
        if(password==null||!password.equals(confirm))throw new AppException("PASSWORD_MISMATCH","비밀번호가 일치하지 않습니다.");
        if(password.length()<10||!password.matches(".*[A-Za-z].*")||!password.matches(".*\\d.*"))
            throw new AppException("WEAK_PASSWORD","비밀번호는 영문과 숫자를 포함해 10자 이상이어야 합니다.");
    }
}
@Service class StoreProvisioningService {
    record Provisioned(Store store,String staffPin,String storeToken){}
    private final StoreRepository stores;private final MembershipRepository memberships;private final QrRepository qrs;private final GameConfigService config;private final SubscriptionService subscriptions;private final PasswordEncoder encoder;private final SecureRandom random;private final Clock clock;
    /**
     * 셀프 신청 매장을 운영자가 승인해야 열리게 할지.
     *
     * 2026-09-22에 기본값을 false로 내렸다. 중복 사업자등록번호는 그대로 막으므로 한 번호로 두
     * 매장을 만들 수는 없고, 승인 대기 큐만 없앤 것이다. 심사 화면과 감사 로그는 그대로 살아 있어서
     * `STORE_APPROVAL_REQUIRED=true` 한 줄로 되돌아온다. 코드를 지우지 말 것.
     */
    private final boolean approvalRequired;
    StoreProvisioningService(StoreRepository stores,MembershipRepository memberships,QrRepository qrs,GameConfigService config,SubscriptionService subscriptions,PasswordEncoder encoder,SecureRandom random,Clock clock,@org.springframework.beans.factory.annotation.Value("${app.store-approval-required:false}") boolean approvalRequired){this.stores=stores;this.memberships=memberships;this.qrs=qrs;this.config=config;this.subscriptions=subscriptions;this.encoder=encoder;this.random=random;this.clock=clock;this.approvalRequired=approvalRequired;}
    /** 운영자가 직접 만드는 매장(부트스트랩/시드)은 이미 확인된 것이므로 바로 ACTIVE다. */
    @Transactional Provisioned provision(AdminUser owner,String name,String phone,String address,String businessNumber,String naverPlaceUrl,String staffPin){
        return provision(owner,name,phone,address,businessNumber,naverPlaceUrl,staffPin,"http://localhost:8088",StoreStatus.ACTIVE);
    }
    /**
     * 셀프 신청. `app.store-approval-required`가 켜져 있으면 PENDING_APPROVAL, 아니면 바로 ACTIVE다.
     *
     * 승인을 꺼도 사업자등록번호 중복은 막힌다(호출부의 existsByBusinessNumber). 한 번호로 매장을
     * 두 개 만들 수 없다는 것이 승인 없이도 남는 최소 방어선이다.
     */
    @Transactional Provisioned provision(AdminUser owner,String name,String phone,String address,String businessNumber,String naverPlaceUrl,String staffPin,String publicOrigin){
        return provision(owner,name,phone,address,businessNumber,naverPlaceUrl,staffPin,publicOrigin,
            approvalRequired?StoreStatus.PENDING_APPROVAL:StoreStatus.ACTIVE);
    }
    @Transactional Provisioned provision(AdminUser owner,String name,String phone,String address,String businessNumber,String naverPlaceUrl,String staffPin,String publicOrigin,StoreStatus status){
        Instant now=clock.instant();String pin=staffPin==null||staffPin.isBlank()?Integer.toString(100000+random.nextInt(900000)):staffPin;
        Store s=new Store();s.name=name.trim();s.phone=phone==null?"":phone.trim();s.address=address;s.businessNumber=businessNumber;s.naverPlaceUrl=naverPlaceUrl;s.staffPinHash=encoder.encode(pin);s.status=status;s.createdAt=now;s.updatedAt=now;stores.save(s);
        AdminStoreMembership m=new AdminStoreMembership();m.admin=owner;m.store=s;m.role=MembershipRole.OWNER;m.createdAt=now;memberships.save(m);
        StoreQrCode q=new StoreQrCode();q.store=s;q.publicToken=Tokens.random();q.status=QrStatus.ACTIVE;q.createdAt=now;qrs.save(q);
        config.save(s,GameConfigService.defaults());
        // 신규 매장은 BASIC으로 시작한다. 게임과 쿠폰은 어떤 등급에서도 다 열려 있으므로 이걸로 막히는 건 없다.
        subscriptions.start(s,Plan.BASIC);
        // 포스터 PNG는 이 흐름에서 가장 비싼 작업이라 여기서 만들지 않는다. 가입은 익명 요청이고,
        // 그 자리에서 큰 이미지를 그리면 요청 한 번에 수백 KB를 쌓는 길이 열린다. 예전에는
        // "승인된 매장만" 그려서 막았는데, 승인을 끄면 그 방어가 통째로 사라졌다.
        // 안내물은 파생 데이터라 인증된 다운로드(AdminController.poster)가 없으면 그때 만든다.
        return new Provisioned(s,pin,q.publicToken);
    }
}
@Service class GameConfigService {
    /** One row of a store's game configuration: how likely a throw is, and which prize rank it awards. */
    record Setting(YutResult yutResult,int weight,int prizeRank){}
    record PrizeSetting(int rank,String name,String description,RedeemPolicy redeemPolicy){}
    record EventConfiguration(List<StoreOutcome> outcomes,List<Prize> prizes){}
    private static final int[] DEFAULT_WEIGHTS={325,325,125,125,100},DEFAULT_RANKS={3,3,2,2,1};
    private final StoreOutcomeRepository outcomes;private final PrizeRepository prizes;private final Clock clock;
    GameConfigService(StoreOutcomeRepository outcomes,PrizeRepository prizes,Clock clock){this.outcomes=outcomes;this.prizes=prizes;this.clock=clock;}

    /** Reproduces the behaviour the game had while probabilities were hard-coded. */
    static List<Setting> defaults(){List<Setting> d=new ArrayList<>();for(YutResult y:YutResult.values())d.add(new Setting(y,DEFAULT_WEIGHTS[y.ordinal()],DEFAULT_RANKS[y.ordinal()]));return d;}
    static String defaultPrizeName(int rank){return rank+"등 상품";}

    List<StoreOutcome> load(Long storeId){
        List<StoreOutcome> found=new ArrayList<>(outcomes.findByStoreId(storeId));
        if(found.size()!=YutResult.values().length)throw new AppException("GAME_CONFIG_MISSING","매장 게임 설정이 없습니다.");
        found.sort(Comparator.comparingInt(o->o.yutResult.ordinal()));
        return found;
    }
    static int rankCount(List<StoreOutcome> config){return (int)config.stream().mapToInt(o->o.prizeRank).distinct().count();}
    /** Probability as a percentage rounded to one decimal. Server-computed; never taken from a client. */
    static double odds(List<StoreOutcome> config,java.util.function.Predicate<StoreOutcome> of){
        int total=config.stream().mapToInt(o->o.weight).sum();
        int part=config.stream().filter(of).mapToInt(o->o.weight).sum();
        return total==0?0:Math.round(part*1000.0/total)/10.0;
    }

    /**
     * Saves the whole config in one transaction. A partial write would leave a store running on a
     * half-applied probability table, so there is deliberately no per-outcome endpoint.
     */
    @Transactional List<StoreOutcome> save(Store store,List<Setting> settings){
        validate(settings);
        Instant now=clock.instant();
        int rankCount=settings.stream().mapToInt(Setting::prizeRank).max().orElseThrow();
        Map<YutResult,StoreOutcome> existing=new EnumMap<>(YutResult.class);
        for(StoreOutcome o:outcomes.findByStoreId(store.id))existing.put(o.yutResult,o);
        List<StoreOutcome> saved=new ArrayList<>();
        for(Setting st:settings){
            StoreOutcome o=existing.get(st.yutResult());
            if(o==null){o=new StoreOutcome();o.store=store;o.yutResult=st.yutResult();}
            o.weight=st.weight();o.prizeRank=st.prizeRank();o.updatedAt=now;
            saved.add(outcomes.save(o));
        }
        // Declaring a rank in the config means the store intends to hand it out, so the slot is created and enabled.
        Map<Integer,Prize> byRank=new HashMap<>();
        for(Prize p:prizes.findByStoreIdOrderByRank(store.id))byRank.put(p.rank,p);
        for(int rank=1;rank<=rankCount;rank++){
            Prize prize=byRank.remove(rank);
            if(prize==null){prize=new Prize();prize.store=store;prize.rank=rank;prize.name=defaultPrizeName(rank);prize.description="관리자에서 상품을 설정하세요.";prize.redeemPolicy=RedeemPolicy.ANYTIME;prize.createdAt=now;}
            prize.active=true;prize.updatedAt=now;prizes.save(prize);
        }
        // Ranks that dropped out of the ladder are deactivated, never deleted: issued coupons still point at them.
        byRank.values().forEach(orphan->{orphan.active=false;orphan.updatedAt=now;prizes.save(orphan);});
        saved.sort(Comparator.comparingInt(o->o.yutResult.ordinal()));
        return saved;
    }

    /** 상품과 확률을 하나의 문서로 검증하고 저장한다. 기존 쿠폰 snapshot은 건드리지 않는다. */
    @Transactional EventConfiguration saveEventConfiguration(Store store,List<Setting> settings,List<PrizeSetting> prizeSettings){
        validate(settings);
        int rankCount=settings.stream().mapToInt(Setting::prizeRank).max().orElseThrow();
        Map<Integer,PrizeSetting> requested=validatePrizes(prizeSettings,rankCount);
        List<StoreOutcome> saved=save(store,settings);
        Instant now=clock.instant();
        List<Prize> configured=new ArrayList<>();
        for(int rank=1;rank<=rankCount;rank++){
            PrizeSetting setting=requested.get(rank);
            Prize prize=prizes.findByStoreIdAndRank(store.id,rank).orElseThrow();
            prize.name=setting.name().trim();prize.description=setting.description()==null?null:setting.description().trim();
            prize.redeemPolicy=setting.redeemPolicy();prize.active=true;prize.updatedAt=now;
            configured.add(prizes.save(prize));
        }
        return new EventConfiguration(saved,configured);
    }

    static void validate(List<Setting> settings){
        if(settings==null||settings.size()!=YutResult.values().length||settings.stream().anyMatch(Objects::isNull)||settings.stream().anyMatch(st->st.yutResult()==null)||settings.stream().map(Setting::yutResult).distinct().count()!=YutResult.values().length)
            throw new AppException("INVALID_REQUEST","윷 결과 5개의 설정을 모두 보내주세요.");
        if(settings.stream().anyMatch(st->st.weight()<0||st.weight()>StoreOutcome.MAX_WEIGHT))
            throw new AppException("INVALID_WEIGHT","가중치는 0에서 "+StoreOutcome.MAX_WEIGHT+" 사이여야 합니다.");
        if(settings.stream().mapToInt(Setting::weight).sum()<1)
            throw new AppException("ZERO_WEIGHT_SUM","가중치 합이 0이면 결과를 뽑을 수 없습니다.");
        int[] ranks=settings.stream().mapToInt(Setting::prizeRank).distinct().sorted().toArray();
        if(ranks.length>StoreOutcome.MAX_RANK)throw new AppException("INVALID_RANK_SEQUENCE","등급은 최대 "+StoreOutcome.MAX_RANK+"개까지 설정할 수 있습니다.");
        for(int i=0;i<ranks.length;i++)if(ranks[i]!=i+1)throw new AppException("INVALID_RANK_SEQUENCE","등급은 1등부터 빠짐없이 이어져야 합니다.");
    }

    private static Map<Integer,PrizeSetting> validatePrizes(List<PrizeSetting> settings,int rankCount){
        if(settings==null||settings.size()!=rankCount)throw new AppException("INVALID_REQUEST","사용하는 상품 등급 1등부터 "+rankCount+"등까지 모두 보내주세요.");
        Map<Integer,PrizeSetting> byRank=new HashMap<>();
        for(PrizeSetting setting:settings){
            if(setting==null||setting.rank()<1||setting.rank()>rankCount||byRank.put(setting.rank(),setting)!=null)
                throw new AppException("INVALID_REQUEST","상품 등급은 1등부터 빠짐없이 한 번씩 보내주세요.");
            if(setting.name()==null||setting.name().isBlank()||setting.name().length()>100)
                throw new AppException("INVALID_REQUEST","상품명은 1자에서 100자까지 입력해 주세요.");
            if(setting.description()!=null&&setting.description().length()>500)
                throw new AppException("INVALID_REQUEST","상품 설명은 500자까지 입력해 주세요.");
            if(setting.redeemPolicy()==null)throw new AppException("INVALID_REQUEST","상품 사용 정책을 선택해 주세요.");
        }
        return byRank;
    }
}
@Service class AdminSignupService {
    /**
     * `openingDate`는 국세청 진위확인에 필요한 세 값 중 하나다(번호·개업일자·대표자명).
     * 검증이 꺼져 있으면 비어 있어도 된다. 기존 편의 생성자들이 빈 값을 넘기는 이유가 그것이다.
     */
    record Request(String password,String passwordConfirm,String email,String ownerName,String phone,String storeName,String businessNumber,boolean termsAgreed,String termsVersion,boolean privacyAgreed,String privacyVersion,String marketingVersion,boolean yutReviewMarketing,boolean reviewPilotMarketing,boolean sodamMarketing,String openingDate,String inviteCode){
        Request(String password,String passwordConfirm,String email,String ownerName,String phone,String storeName,String businessNumber){this(password,passwordConfirm,email,ownerName,phone,storeName,businessNumber,true,LegalConsentPolicy.TERMS_VERSION,true,LegalConsentPolicy.ADMIN_PRIVACY_VERSION,LegalConsentPolicy.MARKETING_SMS_VERSION,false,false,false,"","");}
        Request(String password,String passwordConfirm,String email,String ownerName,String phone,String storeName,String businessNumber,boolean termsAgreed,String termsVersion,boolean privacyAgreed,String privacyVersion){this(password,passwordConfirm,email,ownerName,phone,storeName,businessNumber,termsAgreed,termsVersion,privacyAgreed,privacyVersion,LegalConsentPolicy.MARKETING_SMS_VERSION,false,false,false,"","");}
        /** 개업일자 없이 부르던 자리. 검증이 꺼져 있으면 그 값을 쓰지 않으므로 그대로 둔다. */
        Request(String password,String passwordConfirm,String email,String ownerName,String phone,String storeName,String businessNumber,boolean termsAgreed,String termsVersion,boolean privacyAgreed,String privacyVersion,String marketingVersion,boolean yutReviewMarketing,boolean reviewPilotMarketing,boolean sodamMarketing){this(password,passwordConfirm,email,ownerName,phone,storeName,businessNumber,termsAgreed,termsVersion,privacyAgreed,privacyVersion,marketingVersion,yutReviewMarketing,reviewPilotMarketing,sodamMarketing,"","");}
    }
    private final AdminUserRepository admins;private final StoreRepository stores;private final StoreProvisioningService provisioning;private final PasswordEncoder encoder;private final Clock clock;private final MarketingConsentService marketingConsents;private final SignupAttemptLimiter limiter;private final BusinessRegistryService businessRegistry;private final InviteCodeService invites;
    /**
     * 쓰기 구간만 감싸는 트랜잭션.
     *
     * 메서드 전체에 `@Transactional`을 두면 국세청 호출이 그 안으로 들어간다. 응답을 기다리는
     * 동안 DB 커넥션을 붙들어 풀이 마른다(AI 공급자 호출에서 같은 규칙을 이미 적어 뒀다).
     * 계정·매장·멤버십·QR의 원자성은 이 템플릿이 그대로 보장한다.
     */
    private final org.springframework.transaction.support.TransactionTemplate writes;
    AdminSignupService(AdminUserRepository admins,StoreRepository stores,StoreProvisioningService provisioning,PasswordEncoder encoder,Clock clock,MarketingConsentService marketingConsents,SignupAttemptLimiter limiter,BusinessRegistryService businessRegistry,InviteCodeService invites,org.springframework.transaction.PlatformTransactionManager transactions){this.admins=admins;this.stores=stores;this.provisioning=provisioning;this.encoder=encoder;this.clock=clock;this.marketingConsents=marketingConsents;this.limiter=limiter;this.businessRegistry=businessRegistry;this.invites=invites;this.writes=new org.springframework.transaction.support.TransactionTemplate(transactions);}
    StoreProvisioningService.Provisioned signUp(Request r){return signUp(r,"http://localhost:8088",null);}
    StoreProvisioningService.Provisioned signUp(Request r,String publicOrigin){return signUp(r,publicOrigin,null);}

    /**
     * 순서가 곧 비용 순서다. 형식 → 한도 → 중복 → 국세청 → BCrypt와 행 생성.
     *
     * 중복 검사를 국세청 호출보다 먼저 두는 것은 이미 등록된 번호로 외부 호출을 태우지 않기
     * 위해서다. 국세청 호출은 이 흐름에서 가장 느리고 유일하게 할당량이 있는 자원이다.
     */
    StoreProvisioningService.Provisioned signUp(Request r,String publicOrigin,String clientIp){
        LegalConsentPolicy.requireAdmin(r.termsAgreed,r.termsVersion,r.privacyAgreed,r.privacyVersion);
        String email=Inputs.email(r.email),owner=Inputs.required(r.ownerName,"대표자 이름을 입력해 주세요."),
            storeName=Inputs.required(r.storeName,"매장 상호명을 입력해 주세요."),
            phone=Inputs.phone(r.phone),business=Inputs.businessNumber(r.businessNumber);
        Inputs.password(r.password,r.passwordConfirm);
        // 검증이 꺼져 있으면 개업일자를 요구하지 않는다. 켜면 세 값이 다 있어야 국세청이 답한다.
        String openingDate=businessRegistry.enabled()?Inputs.openingDate(r.openingDate()):"";
        // BCrypt와 행 생성 전에 센다. 형식 검증만 통과한 요청이 매번 해시 비용을 태우면 그 자체가 부하다.
        limiter.attempt(clientIp,business);
        if(admins.existsByEmail(email))throw new AppException("DUPLICATE_EMAIL","이미 가입된 이메일입니다.");
        if(stores.existsByBusinessNumber(business))throw new AppException("DUPLICATE_BUSINESS_NUMBER","이미 등록된 사업자등록번호입니다.");
        // 초대코드는 선택이다. 비어 있으면 그냥 넘어가고, 값이 있는데 틀리면 가입을 막는다.
        // 오타를 친 사람이 "추천이 반영됐겠지" 하고 넘어가는 것이 가장 나쁜 결과다.
        AdminUser inviter=null;String inviteCode="";
        if(r.inviteCode()!=null&&!r.inviteCode().isBlank()){
            inviter=invites.resolve(r.inviteCode());
            inviteCode=inviter.inviteCode;
            // 같은 사람이 계정을 하나 더 만들어 자기 코드를 쓰는 길을 막는다. 리워드가 아직
            // 없어서 지금은 손해가 없지만, 정책이 붙는 순간 그 전에 쌓인 기록이 이미 오염돼 있다.
            // 매장을 여러 개 하는 사장은 보통 한 계정에 매장을 추가하므로 정상 사용자를 막지 않는다.
            if(phone.equals(inviter.phone))
                throw new AppException("INVITE_CODE_SELF","자신의 초대코드는 사용할 수 없어요.");
        }
        // 트랜잭션 밖이다. 이 줄을 아래 writes 블록 안으로 옮기지 말 것.
        businessRegistry.verify(business,openingDate,owner);
        AdminUser resolvedInviter=inviter;String resolvedInviteCode=inviteCode;
        return writes.execute(status->{
            Instant now=clock.instant();AdminUser a=new AdminUser();a.email=email;a.passwordHash=encoder.encode(r.password);a.name=owner;a.phone=phone;a.role=AdminRole.STORE_ADMIN;a.termsVersion=r.termsVersion;a.termsAgreedAt=now;a.privacyVersion=r.privacyVersion;a.privacyAgreedAt=now;a.createdAt=now;
            a.inviteCode=invites.issue();
            if(resolvedInviter!=null){a.invitedBy=resolvedInviter;a.invitedByCode=resolvedInviteCode;}
            admins.save(a);
            marketingConsents.recordInitial(a,Map.of(MarketingService.YUT_REVIEW,r.yutReviewMarketing,MarketingService.REVIEW_PILOT,r.reviewPilotMarketing,MarketingService.SODAM,r.sodamMarketing),r.marketingVersion);
            StoreProvisioningService.Provisioned p=provisioning.provision(a,storeName,phone,null,business,null,null,publicOrigin);
            // 확인에 쓴 세 값을 매장에 묶어 둔다. 소유권이 넘어가도 이 매장이 어느 사업자로
            // 확인됐는지는 남아야 한다.
            stores.findById(p.store().id).ifPresent(st->{
                st.representativeName=owner;
                if(businessRegistry.enabled()){st.openingDate=openingDate;st.businessVerifiedAt=now;}
            });
            return p;
        });
    }
}
@Service class StoreAccessService {
    private final QrRepository qrs; private final MembershipRepository memberships;
    StoreAccessService(QrRepository qrs,MembershipRepository memberships){this.qrs=qrs;this.memberships=memberships;}
    StoreQrCode activeQr(String token){StoreQrCode q=qrs.findByPublicToken(token).orElseThrow(()->new AppException("QR_TOKEN_INVALID","유효하지 않은 QR입니다.",org.springframework.http.HttpStatus.NOT_FOUND));if(q.status==QrStatus.REVOKED)throw new AppException("QR_TOKEN_REVOKED","폐기된 QR입니다.");if(q.store.status!=StoreStatus.ACTIVE)throw new AppException("STORE_INACTIVE","운영 중인 매장이 아닙니다.");return q;}
    void member(Long adminId,Long storeId){if(adminId==null||!memberships.existsByAdminIdAndStoreId(adminId,storeId))throw new AppException("FORBIDDEN","매장 접근 권한이 없습니다.",org.springframework.http.HttpStatus.FORBIDDEN);}
}
@Service class GameResultGenerator {
    private final SecureRandom random; GameResultGenerator(SecureRandom random){this.random=random;}
    YutResult generate(List<StoreOutcome> config){int[] weights=new int[YutResult.values().length];for(StoreOutcome o:config)weights[o.yutResult.ordinal()]=o.weight;return from(random.nextDouble(),weights);}
    /** Pure so the weight boundaries stay testable. An outcome weighted 0 can never be returned. */
    static YutResult from(double r,int[] weights){
        if(r<0||r>=1)throw new IllegalArgumentException();
        int total=0;for(int w:weights)total+=w;
        if(total<1)throw new IllegalStateException("weights must not sum to zero");
        int pick=(int)(r*total),cumulative=0;
        for(YutResult y:YutResult.values()){cumulative+=weights[y.ordinal()];if(pick<cumulative)return y;}
        throw new IllegalStateException("unreachable");
    }
}
final class Tokens {
    private Tokens(){}
    static String random(){byte[] b=new byte[24];new SecureRandom().nextBytes(b);return Base64.getUrlEncoder().withoutPadding().encodeToString(b);}
}
/**
 * 직원 PIN 시도 제한.
 *
 * 키가 두 개다. `storeId+IP`는 한 자리에서 PIN을 찍어 보는 것을, `storeId+couponId`는 IP를 바꿔
 * 가며 같은 쿠폰을 노리는 것을 막는다. IP 하나만 보면 프록시 뒤에서 통째로 우회된다.
 *
 * 저장소는 인메모리 맵이 아니라 TTL이 있는 DB 카운터다. 예전 맵은 만료도 상한도 없어서
 * 서로 다른 키가 들어올수록 프로세스 메모리가 단조 증가했다.
 */
@Service class PinAttemptLimiter {
    static final int PER_IP_PER_MINUTE=10,PER_COUPON_PER_MINUTE=5;
    private final RateLimitService rateLimits;PinAttemptLimiter(RateLimitService rateLimits){this.rateLimits=rateLimits;}
    void attempt(Long storeId,Long couponId,String ip){
        if(ip!=null&&!ip.isBlank())rateLimits.check("pin-ip:"+storeId+":"+ip,PER_IP_PER_MINUTE,Duration.ofMinutes(1),"STAFF_PIN_RATE_LIMITED","잠시 후 다시 시도해 주세요.");
        rateLimits.check("pin-coupon:"+storeId+":"+couponId,PER_COUPON_PER_MINUTE,Duration.ofMinutes(1),"STAFF_PIN_RATE_LIMITED","잠시 후 다시 시도해 주세요.");
    }
    void succeeded(Long storeId,Long couponId,String ip){
        if(ip!=null&&!ip.isBlank())rateLimits.succeeded("pin-ip:"+storeId+":"+ip);
        rateLimits.succeeded("pin-coupon:"+storeId+":"+couponId);
    }
}
/** 로그인 시도 제한. IP와 계정 두 키를 함께 센다. 계정 키가 없으면 프록시 하나로 전수 대입이 열린다. */
@Service class LoginAttemptLimiter {
    static final int PER_MINUTE=5;
    private final RateLimitService rateLimits;LoginAttemptLimiter(RateLimitService rateLimits){this.rateLimits=rateLimits;}
    void attempt(String ip,String email){
        if(ip!=null&&!ip.isBlank())rateLimits.check("login-ip:"+ip,PER_MINUTE,Duration.ofMinutes(1),"AUTH_RATE_LIMITED","로그인 시도가 너무 많습니다. 잠시 후 다시 시도해 주세요.");
        rateLimits.check("login-account:"+email,PER_MINUTE,Duration.ofMinutes(1),"AUTH_RATE_LIMITED","로그인 시도가 너무 많습니다. 잠시 후 다시 시도해 주세요.");
    }
    void succeeded(String ip,String email){
        if(ip!=null&&!ip.isBlank())rateLimits.succeeded("login-ip:"+ip);
        rateLimits.succeeded("login-account:"+email);
    }
}
/** 가입 시도 제한. IP는 시간·일 두 창으로, 사업자등록번호는 하루 창으로 본다. */
@Service class SignupAttemptLimiter {
    static final int PER_IP_PER_HOUR=5,PER_IP_PER_DAY=20,PER_BUSINESS_NUMBER_PER_DAY=3;
    private final RateLimitService rateLimits;SignupAttemptLimiter(RateLimitService rateLimits){this.rateLimits=rateLimits;}
    void attempt(String ip,String businessNumber){
        String message="가입 요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.";
        if(ip!=null&&!ip.isBlank()){
            rateLimits.check("signup-ip-hour:"+ip,PER_IP_PER_HOUR,Duration.ofHours(1),"SIGNUP_RATE_LIMITED",message);
            rateLimits.check("signup-ip-day:"+ip,PER_IP_PER_DAY,Duration.ofHours(24),"SIGNUP_RATE_LIMITED",message);
        }
        if(businessNumber!=null&&!businessNumber.isBlank())
            rateLimits.check("signup-biz:"+businessNumber,PER_BUSINESS_NUMBER_PER_DAY,Duration.ofHours(24),"SIGNUP_RATE_LIMITED",message);
    }
}
@Service class ParticipationService {
    record State(String state,LocalDate nextPlayableDate,Coupon coupon){}
    private final CouponRepository coupons;private final GameRepository games;private final PhoneService phones;private final Clock clock;
    ParticipationService(CouponRepository coupons,GameRepository games,PhoneService phones,Clock clock){this.coupons=coupons;this.games=games;this.phones=phones;this.clock=clock;}
    /** 회전 중이면 이전 키로 저장된 해시도 함께 본다. 그러지 않으면 키 교체 직후 쿨타임이 통째로 풀린다. */
    State state(Long storeId,String phone){List<String> hashes=phones.lookupHashes(phone);Optional<Coupon> active=coupons.findFirstByStoreIdAndPhoneHashInAndStatusOrderByIssuedAtDesc(storeId,hashes,CouponStatus.ISSUED).filter(c->clock.instant().isBefore(c.expiresAt.plusNanos(1)));if(active.isPresent())return new State("HAS_ACTIVE_COUPON",null,active.get());Optional<GamePlay> last=games.findFirstByStoreIdAndPhoneHashInOrderByPlayedDateDesc(storeId,hashes);LocalDate today=LocalDate.now(clock);if(last.isPresent()){LocalDate next=last.get().playedDate.plusDays(2);if(today.isBefore(next))return new State("COOLDOWN",next,null);}return new State("CAN_PLAY",null,null);}
}
/**
 * 매장별 이벤트 설정. 지금은 쿠폰 사용 기한 하나뿐이다.
 *
 * 설정은 이 시점 이후 새로 발급되는 쿠폰에만 적용된다. 이미 발급된 쿠폰의 expiresAt은 상품명·등급과
 * 같은 이유로 발급 시점에 동결되며, 사장이 기한을 줄이든 늘리든 손님이 이미 받은 쿠폰은 그대로다.
 * 이미 발급된 쿠폰을 일괄로 다시 계산하는 코드를 추가하지 말 것.
 */
@Service class StoreEventSettingsService {
    private final StoreEventSettingsRepository settings;private final Clock clock;
    StoreEventSettingsService(StoreEventSettingsRepository settings,Clock clock){this.settings=settings;this.clock=clock;}

    /** 설정 행이 없는 매장은 기본값으로 동작한다. 조회 실패가 임의값으로 새는 경로를 만들지 않는다. */
    int couponValidityDays(Long storeId){return settings.findByStoreId(storeId).map(s->s.couponValidityDays).orElse(StoreEventSettings.DEFAULT_COUPON_VALIDITY_DAYS);}

    Optional<StoreEventSettings> find(Long storeId){return settings.findByStoreId(storeId);}

    @Transactional StoreEventSettings save(Store store,Integer couponValidityDays){
        if(couponValidityDays==null||couponValidityDays<StoreEventSettings.MIN_COUPON_VALIDITY_DAYS||couponValidityDays>StoreEventSettings.MAX_COUPON_VALIDITY_DAYS)
            throw new AppException("INVALID_COUPON_VALIDITY_DAYS","쿠폰 사용 기한은 "+StoreEventSettings.MIN_COUPON_VALIDITY_DAYS+"일에서 "+StoreEventSettings.MAX_COUPON_VALIDITY_DAYS+"일 사이로 설정해 주세요.");
        Instant now=clock.instant();
        StoreEventSettings s=settings.findByStoreId(store.id).orElseGet(StoreEventSettings::new);
        if(s.id==null){s.store=store;s.createdAt=now;}
        s.couponValidityDays=couponValidityDays;s.updatedAt=now;
        return settings.save(s);
    }

    /**
     * 만료 시각을 정하는 유일한 자리.
     *
     * 기준은 발급일이 아니라 <b>쓸 수 있게 되는 날</b>(validFrom)이다. 사장이 "사용 기한 N일"로 읽는 것은
     * 실제로 쓸 수 있는 날이 N일이라는 뜻이고, NEXT_DAY 상품에서 발급일을 기준으로 잡으면 하루를 손해 본다.
     * 사용 기한 1일 + NEXT_DAY라면 발급일 23:59:59가 만료라 받자마자 죽은 쿠폰이 나온다.
     *
     * N일째의 23:59:59까지 쓸 수 있다. 1일이면 쓸 수 있게 된 그날 자정 직전까지다.
     * (기존 구현은 발급일+N일이라 실제로는 N+1 달력일이었다. 2026-09-04에 정의를 맞췄다.)
     */
    static Instant expiresAt(Instant validFrom,int days,ZoneId zone){
        ZonedDateTime from=validFrom.atZone(zone);
        ZonedDateTime end=from.toLocalDate().plusDays(days-1L).atTime(23,59,59).atZone(zone);
        // 자정 직전에 발급되고 기한이 1일이면 마지막 초를 넘겨 이미 지난 시각이 나온다. 그때는 하루를 더 준다.
        return (end.isAfter(from)?end:end.plusDays(1)).toInstant();
    }
}
interface NotificationService { void couponIssued(Coupon coupon); }
@Service class NoopNotificationService implements NotificationService { public void couponIssued(Coupon coupon){} }
@Service class GameService {
    /**
     * 공개 게임 생성의 상한.
     *
     * 손님 한 명의 참여 제한은 여기가 아니라 2일 쿨타임이 정한다. 이 숫자들은 "한 매장이나 한 IP가
     * 전화번호와 요청 키만 바꿔 가며 서버를 통째로 밀어붙이는 것"만 끊는다. 정상 QR 손님이 이 선에
     * 닿으려면 같은 가게에서 1분에 30번 새 참여가 일어나야 한다.
     */
    static final int MAX_PLAYS_PER_STORE_PER_MINUTE=30,MAX_PLAYS_PER_IP_PER_MINUTE=10,MAX_PLAYS_PER_STORE_PER_DAY=2000;
    private final StoreAccessService access;private final PhoneService phones;private final ParticipationService participation;private final GameResultGenerator generator;private final GameConfigService config;private final StoreEventSettingsService eventSettings;private final GameRepository games;private final PrizeRepository prizes;private final CouponRepository coupons;private final Clock clock;private final NotificationService notifications;private final RateLimitService rateLimits;
    /** 위 기본값이 정책이고, 설정은 눈에 띄게 붐비는 매장을 위한 조정 나사다. 기본값으로 두는 것이 정상이다. */
    private final int perStorePerMinute,perIpPerMinute,perStorePerDay;
    GameService(StoreAccessService access,PhoneService phones,ParticipationService participation,GameResultGenerator generator,GameConfigService config,StoreEventSettingsService eventSettings,GameRepository games,PrizeRepository prizes,CouponRepository coupons,Clock clock,NotificationService notifications,RateLimitService rateLimits,
        @Value("${app.limits.game-per-store-per-minute:"+MAX_PLAYS_PER_STORE_PER_MINUTE+"}") int perStorePerMinute,
        @Value("${app.limits.game-per-ip-per-minute:"+MAX_PLAYS_PER_IP_PER_MINUTE+"}") int perIpPerMinute,
        @Value("${app.limits.game-per-store-per-day:"+MAX_PLAYS_PER_STORE_PER_DAY+"}") int perStorePerDay){this.access=access;this.phones=phones;this.participation=participation;this.generator=generator;this.config=config;this.eventSettings=eventSettings;this.games=games;this.prizes=prizes;this.coupons=coupons;this.clock=clock;this.notifications=notifications;this.rateLimits=rateLimits;this.perStorePerMinute=perStorePerMinute;this.perIpPerMinute=perIpPerMinute;this.perStorePerDay=perStorePerDay;}
    /** 실제로 적용 중인 한도. 모니터링 화면이 상수를 따로 들고 있지 않게 한 곳에서만 읽는다. */
    Map<String,Integer> limits(){return Map.of("gamePerStorePerMinute",perStorePerMinute,"gamePerIpPerMinute",perIpPerMinute,"gamePerStorePerDay",perStorePerDay);}
    @Transactional GamePlay create(String token,String name,String phone,String idem){return create(token,name,phone,idem,true,LegalConsentPolicy.CUSTOMER_PRIVACY_VERSION,null);}
    @Transactional GamePlay create(String token,String name,String phone,String idem,boolean privacyAgreed,String privacyVersion){return create(token,name,phone,idem,privacyAgreed,privacyVersion,null);}
    @Transactional GamePlay create(String token,String name,String phone,String idem,boolean privacyAgreed,String privacyVersion,String clientIp){LegalConsentPolicy.requireCustomer(privacyAgreed,privacyVersion);if(idem==null||idem.isBlank())throw new AppException("INVALID_REQUEST","idempotencyKey가 필요합니다.");
        if(clientIp!=null&&!clientIp.isBlank())rateLimits.check("game-ip:"+clientIp,perIpPerMinute,Duration.ofMinutes(1),"GAME_RATE_LIMITED","참여 요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.");
        StoreQrCode qr=access.activeQr(token);
        rateLimits.check("game-store:"+qr.store.id,perStorePerMinute,Duration.ofMinutes(1),"GAME_RATE_LIMITED","참여 요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.");
        String normalized=phones.normalize(phone);
        // 매장 전체가 아니라 이 손님만 줄 세운다. 매장 행을 잠그면 한 손님의 게임이 그 가게의
        // 다른 모든 손님을 기다리게 만들고, 그 자체가 요청 하나로 매장을 멈추는 길이 된다.
        rateLimits.lock("game:"+qr.store.id+":"+phones.hash(normalized));
        Optional<GamePlay> existing=games.findByIdempotencyKey(idem);if(existing.isPresent()){GamePlay g=existing.get();if(!g.store.id.equals(qr.store.id)||!phones.lookupHashes(normalized).contains(g.phoneHash))throw new AppException("GAME_ALREADY_CREATED","이미 다른 게임에 사용된 요청 키입니다.");return g;}
        // 행 수 상한. 쿨타임을 지나 정상 발급되는 쿠폰까지 합쳐도 한 매장이 하루에 이만큼 쌓을 일은 없다.
        if(games.countByStoreIdAndPlayedDate(qr.store.id,LocalDate.now(clock))>=perStorePerDay)throw new AppException("STORE_DAILY_LIMIT","오늘 참여가 마감되었습니다. 내일 다시 참여해 주세요.",org.springframework.http.HttpStatus.TOO_MANY_REQUESTS);
        ParticipationService.State state=participation.state(qr.store.id,phone);if(state.state().equals("HAS_ACTIVE_COUPON"))throw new AppException("ACTIVE_COUPON_EXISTS","사용 가능한 쿠폰이 있습니다.");if(state.state().equals("COOLDOWN"))throw new AppException("PARTICIPATION_COOLDOWN",state.nextPlayableDate()+"부터 다시 참여하실 수 있습니다.");List<StoreOutcome> outcomes=config.load(qr.store.id);YutResult result=generator.generate(outcomes);int rank=outcomes.stream().filter(o->o.yutResult==result).mapToInt(o->o.prizeRank).findFirst().orElseThrow();Prize prize=prizes.findByStoreIdAndRank(qr.store.id,rank).filter(p->p.active).orElseThrow(()->new AppException("PRIZE_NOT_CONFIGURED","활성 상품이 설정되지 않았습니다."));Instant now=clock.instant();GamePlay g=new GamePlay();g.publicId=UUID.randomUUID().toString();g.store=qr.store;g.qrCode=qr;g.customerNameEncrypted=phones.encrypt(name.trim());g.phoneHash=phones.hash(normalized);g.phoneEncrypted=phones.encrypt(normalized);g.phoneLast4=normalized.substring(7);g.yutResult=result;g.prizeRank=rank;g.status=GameStatus.CREATED;g.animationSeed="seed_"+Tokens.random();g.idempotencyKey=idem;g.privacyConsentVersion=privacyVersion;g.privacyConsentedAt=now;g.playedDate=LocalDate.now(clock);g.playedAt=now;games.save(g);Coupon c=new Coupon();c.store=qr.store;c.gamePlay=g;c.prize=prize;c.couponToken="cp_"+Tokens.random();c.phoneHash=g.phoneHash;c.prizeNameSnapshot=prize.name;c.prizeDescriptionSnapshot=prize.description;c.prizeRankSnapshot=rank;c.redeemPolicySnapshot=prize.redeemPolicy;c.status=CouponStatus.ISSUED;c.issuedAt=now;ZoneId zone=clock.getZone();LocalDate issued=g.playedDate;c.validFrom=prize.redeemPolicy==RedeemPolicy.NEXT_DAY?issued.plusDays(1).atStartOfDay(zone).toInstant():now;
        // 발급 시점의 매장 설정으로 한 번 계산하고 끝낸다. 나중에 사장이 기한을 바꿔도 이 쿠폰은 그대로다.
        c.expiresAt=StoreEventSettingsService.expiresAt(c.validFrom,eventSettings.couponValidityDays(qr.store.id),zone);coupons.save(c);notifications.couponIssued(c);return g;}
    @Transactional Coupon reveal(String playId){GamePlay g=games.findByPublicId(playId).orElseThrow(()->new AppException("GAME_NOT_FOUND","게임을 찾을 수 없습니다.",org.springframework.http.HttpStatus.NOT_FOUND));if(g.status==GameStatus.CREATED){g.status=GameStatus.REVEALED;g.revealedAt=clock.instant();}return coupons.findByGamePlayId(g.id).orElseThrow();}
}
@Service class CouponService {
    private final CouponRepository coupons;private final PasswordEncoder encoder;private final Clock clock;private final PinAttemptLimiter limiter;
    CouponService(CouponRepository coupons,PasswordEncoder encoder,Clock clock,PinAttemptLimiter limiter){this.coupons=coupons;this.encoder=encoder;this.clock=clock;this.limiter=limiter;}
    @Transactional Coupon get(String token){Coupon c=coupons.findByCouponToken(token).orElseThrow(()->new AppException("COUPON_NOT_FOUND","쿠폰을 찾을 수 없습니다.",org.springframework.http.HttpStatus.NOT_FOUND));expire(c);return c;}
    @Transactional Coupon redeem(String token,String pin,String ip){Coupon c=coupons.findForUpdate(token).orElseThrow(()->new AppException("COUPON_NOT_FOUND","쿠폰을 찾을 수 없습니다.",org.springframework.http.HttpStatus.NOT_FOUND));Instant now=clock.instant();if(c.status==CouponStatus.REDEEMED)throw new AppException("COUPON_ALREADY_REDEEMED","이미 사용한 쿠폰입니다.");expire(c);if(c.status==CouponStatus.EXPIRED)throw new AppException("COUPON_EXPIRED","유효기간이 지난 쿠폰입니다.");if(c.status!=CouponStatus.ISSUED)throw new AppException("COUPON_NOT_ACTIVE","사용할 수 없는 쿠폰입니다.");if(now.isBefore(c.validFrom))throw new AppException("COUPON_NOT_YET_VALID","아직 사용할 수 없는 쿠폰입니다.");limiter.attempt(c.store.id,c.id,ip);if(!encoder.matches(pin,c.store.staffPinHash))throw new AppException("STAFF_PIN_INVALID","직원 PIN이 올바르지 않습니다.");limiter.succeeded(c.store.id,c.id,ip);c.status=CouponStatus.REDEEMED;c.redeemedAt=now;return c;}
    private void expire(Coupon c){if(c.status==CouponStatus.ISSUED&&clock.instant().isAfter(c.expiresAt))c.status=CouponStatus.EXPIRED;}
}
