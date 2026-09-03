package com.yutreview;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service class PhoneService {
    private final byte[] hmacKey,encryptionKey; PhoneService(@Value("${app.phone-hmac-key}") String hmacKey,@Value("${app.phone-encryption-key}") String encryptionKey){this.hmacKey=hmacKey.getBytes(StandardCharsets.UTF_8);this.encryptionKey=Base64.getDecoder().decode(encryptionKey);if(this.encryptionKey.length!=32)throw new IllegalArgumentException("PHONE_ENCRYPTION_KEY must be a Base64-encoded 32-byte key");}
    String normalize(String phone){return Inputs.phone(phone);}
    String hash(String phone){try{Mac m=Mac.getInstance("HmacSHA256");m.init(new SecretKeySpec(hmacKey,"HmacSHA256"));return HexFormat.of().formatHex(m.doFinal(normalize(phone).getBytes(StandardCharsets.UTF_8)));}catch(GeneralSecurityException e){throw new IllegalStateException(e);}}
    String encrypt(String value){try{byte[] iv=new byte[12];SecureRandom.getInstanceStrong().nextBytes(iv);Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(encryptionKey,"AES"),new GCMParameterSpec(128,iv));byte[] encrypted=cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));byte[] packed=new byte[iv.length+encrypted.length];System.arraycopy(iv,0,packed,0,iv.length);System.arraycopy(encrypted,0,packed,iv.length,encrypted.length);return Base64.getEncoder().encodeToString(packed);}catch(GeneralSecurityException e){throw new IllegalStateException("Personal data encryption failed",e);}}
    String decrypt(String value){try{byte[] packed=Base64.getDecoder().decode(value),iv=Arrays.copyOfRange(packed,0,12);Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(encryptionKey,"AES"),new GCMParameterSpec(128,iv));return new String(cipher.doFinal(Arrays.copyOfRange(packed,12,packed.length)),StandardCharsets.UTF_8);}catch(GeneralSecurityException|IllegalArgumentException e){throw new IllegalStateException("Personal data decryption failed",e);}}
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
    static void password(String password,String confirm){
        if(password==null||!password.equals(confirm))throw new AppException("PASSWORD_MISMATCH","비밀번호가 일치하지 않습니다.");
        if(password.length()<10||!password.matches(".*[A-Za-z].*")||!password.matches(".*\\d.*"))
            throw new AppException("WEAK_PASSWORD","비밀번호는 영문과 숫자를 포함해 10자 이상이어야 합니다.");
    }
}
@Service class StoreProvisioningService {
    record Provisioned(Store store,String staffPin,String storeToken){}
    private final StoreRepository stores;private final MembershipRepository memberships;private final QrRepository qrs;private final GameConfigService config;private final StorePosterService posters;private final PasswordEncoder encoder;private final SecureRandom random;private final Clock clock;
    StoreProvisioningService(StoreRepository stores,MembershipRepository memberships,QrRepository qrs,GameConfigService config,StorePosterService posters,PasswordEncoder encoder,SecureRandom random,Clock clock){this.stores=stores;this.memberships=memberships;this.qrs=qrs;this.config=config;this.posters=posters;this.encoder=encoder;this.random=random;this.clock=clock;}
    @Transactional Provisioned provision(AdminUser owner,String name,String phone,String address,String businessNumber,String naverPlaceUrl,String staffPin){
        return provision(owner,name,phone,address,businessNumber,naverPlaceUrl,staffPin,"http://localhost:8088");
    }
    @Transactional Provisioned provision(AdminUser owner,String name,String phone,String address,String businessNumber,String naverPlaceUrl,String staffPin,String publicOrigin){
        Instant now=clock.instant();String pin=staffPin==null||staffPin.isBlank()?Integer.toString(100000+random.nextInt(900000)):staffPin;
        Store s=new Store();s.name=name.trim();s.phone=phone==null?"":phone.trim();s.address=address;s.businessNumber=businessNumber;s.naverPlaceUrl=naverPlaceUrl;s.staffPinHash=encoder.encode(pin);s.status=StoreStatus.ACTIVE;s.createdAt=now;s.updatedAt=now;stores.save(s);
        AdminStoreMembership m=new AdminStoreMembership();m.admin=owner;m.store=s;m.role=MembershipRole.OWNER;m.createdAt=now;memberships.save(m);
        StoreQrCode q=new StoreQrCode();q.store=s;q.publicToken=Tokens.random();q.status=QrStatus.ACTIVE;q.createdAt=now;qrs.save(q);
        config.save(s,GameConfigService.defaults());
        posters.save(s,q.publicToken,publicOrigin);
        return new Provisioned(s,pin,q.publicToken);
    }
}
@Service class GameConfigService {
    /** One row of a store's game configuration: how likely a throw is, and which prize rank it awards. */
    record Setting(YutResult yutResult,int weight,int prizeRank){}
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

    static void validate(List<Setting> settings){
        if(settings==null||settings.size()!=YutResult.values().length||settings.stream().map(Setting::yutResult).distinct().count()!=YutResult.values().length)
            throw new AppException("INVALID_REQUEST","윷 결과 5개의 설정을 모두 보내주세요.");
        if(settings.stream().anyMatch(st->st.weight()<0||st.weight()>StoreOutcome.MAX_WEIGHT))
            throw new AppException("INVALID_WEIGHT","가중치는 0에서 "+StoreOutcome.MAX_WEIGHT+" 사이여야 합니다.");
        if(settings.stream().mapToInt(Setting::weight).sum()<1)
            throw new AppException("ZERO_WEIGHT_SUM","가중치 합이 0이면 결과를 뽑을 수 없습니다.");
        int[] ranks=settings.stream().mapToInt(Setting::prizeRank).distinct().sorted().toArray();
        if(ranks.length>StoreOutcome.MAX_RANK)throw new AppException("INVALID_RANK_SEQUENCE","등급은 최대 "+StoreOutcome.MAX_RANK+"개까지 설정할 수 있습니다.");
        for(int i=0;i<ranks.length;i++)if(ranks[i]!=i+1)throw new AppException("INVALID_RANK_SEQUENCE","등급은 1등부터 빠짐없이 이어져야 합니다.");
    }
}
@Service class AdminSignupService {
    record Request(String password,String passwordConfirm,String email,String ownerName,String phone,String storeName,String businessNumber){}
    private final AdminUserRepository admins;private final StoreRepository stores;private final StoreProvisioningService provisioning;private final PasswordEncoder encoder;private final Clock clock;
    AdminSignupService(AdminUserRepository admins,StoreRepository stores,StoreProvisioningService provisioning,PasswordEncoder encoder,Clock clock){this.admins=admins;this.stores=stores;this.provisioning=provisioning;this.encoder=encoder;this.clock=clock;}
    @Transactional StoreProvisioningService.Provisioned signUp(Request r){return signUp(r,"http://localhost:8088");}
    @Transactional StoreProvisioningService.Provisioned signUp(Request r,String publicOrigin){
        String email=Inputs.email(r.email),owner=Inputs.required(r.ownerName,"대표자 이름을 입력해 주세요."),
            storeName=Inputs.required(r.storeName,"매장 상호명을 입력해 주세요."),
            phone=Inputs.phone(r.phone),business=Inputs.businessNumber(r.businessNumber);
        Inputs.password(r.password,r.passwordConfirm);
        if(admins.existsByEmail(email))throw new AppException("DUPLICATE_EMAIL","이미 가입된 이메일입니다.");
        if(stores.existsByBusinessNumber(business))throw new AppException("DUPLICATE_BUSINESS_NUMBER","이미 등록된 사업자등록번호입니다.");
        AdminUser a=new AdminUser();a.email=email;a.passwordHash=encoder.encode(r.password);a.name=owner;a.phone=phone;a.role=AdminRole.STORE_ADMIN;a.createdAt=clock.instant();admins.save(a);
        return provisioning.provision(a,storeName,phone,null,business,null,null,publicOrigin);
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
@Service class PinAttemptLimiter {
    private record Attempts(Instant since,int count){}
    private final Clock clock;private final Map<String,Attempts> attempts=new ConcurrentHashMap<>();PinAttemptLimiter(Clock clock){this.clock=clock;}
    void attempt(String key){Instant now=clock.instant();if(attempts.size()>10000)attempts.entrySet().removeIf(e->e.getValue().since.plusSeconds(60).isBefore(now));Attempts a=attempts.compute(key,(k,v)->v==null||v.since.plusSeconds(60).isBefore(now)?new Attempts(now,1):new Attempts(v.since,v.count+1));if(a.count>10)throw new AppException("STAFF_PIN_RATE_LIMITED","잠시 후 다시 시도해 주세요.",org.springframework.http.HttpStatus.TOO_MANY_REQUESTS);}
    void succeeded(String key){attempts.remove(key);}
}
@Service class LoginAttemptLimiter {
    private record Attempts(Instant since,int count){}
    private final Clock clock;private final Map<String,Attempts> attempts=new ConcurrentHashMap<>();LoginAttemptLimiter(Clock clock){this.clock=clock;}
    void attempt(String ip){Instant now=clock.instant();Attempts a=attempts.compute(ip,(k,v)->v==null||v.since.plusSeconds(60).isBefore(now)?new Attempts(now,1):new Attempts(v.since,v.count+1));if(a.count>5)throw new AppException("AUTH_RATE_LIMITED","로그인 시도가 너무 많습니다. 잠시 후 다시 시도해 주세요.",org.springframework.http.HttpStatus.TOO_MANY_REQUESTS);}
    void succeeded(String ip){attempts.remove(ip);}
}
@Service class ParticipationService {
    record State(String state,LocalDate nextPlayableDate,Coupon coupon){}
    private final CouponRepository coupons;private final GameRepository games;private final PhoneService phones;private final Clock clock;
    ParticipationService(CouponRepository coupons,GameRepository games,PhoneService phones,Clock clock){this.coupons=coupons;this.games=games;this.phones=phones;this.clock=clock;}
    State state(Long storeId,String phone){String hash=phones.hash(phone);Optional<Coupon> active=coupons.findFirstByStoreIdAndPhoneHashAndStatusOrderByIssuedAtDesc(storeId,hash,CouponStatus.ISSUED).filter(c->clock.instant().isBefore(c.expiresAt.plusNanos(1)));if(active.isPresent())return new State("HAS_ACTIVE_COUPON",null,active.get());Optional<GamePlay> last=games.findFirstByStoreIdAndPhoneHashOrderByPlayedDateDesc(storeId,hash);LocalDate today=LocalDate.now(clock);if(last.isPresent()){LocalDate next=last.get().playedDate.plusDays(2);if(today.isBefore(next))return new State("COOLDOWN",next,null);}return new State("CAN_PLAY",null,null);}
}
interface NotificationService { void couponIssued(Coupon coupon); }
@Service class NoopNotificationService implements NotificationService { public void couponIssued(Coupon coupon){} }
@Service class GameService {
    private final StoreAccessService access;private final StoreRepository stores;private final PhoneService phones;private final ParticipationService participation;private final GameResultGenerator generator;private final GameConfigService config;private final GameRepository games;private final PrizeRepository prizes;private final CouponRepository coupons;private final Clock clock;private final NotificationService notifications;
    GameService(StoreAccessService access,StoreRepository stores,PhoneService phones,ParticipationService participation,GameResultGenerator generator,GameConfigService config,GameRepository games,PrizeRepository prizes,CouponRepository coupons,Clock clock,NotificationService notifications){this.access=access;this.stores=stores;this.phones=phones;this.participation=participation;this.generator=generator;this.config=config;this.games=games;this.prizes=prizes;this.coupons=coupons;this.clock=clock;this.notifications=notifications;}
    @Transactional GamePlay create(String token,String name,String phone,String idem){if(idem==null||idem.isBlank())throw new AppException("INVALID_REQUEST","idempotencyKey가 필요합니다.");StoreQrCode qr=access.activeQr(token);stores.findForUpdate(qr.store.id).orElseThrow(); // ponytail: store-wide lock is enough for single-instance MVP; narrow to customer-key locks if throughput matters.
        String normalized=phones.normalize(phone);Optional<GamePlay> existing=games.findByIdempotencyKey(idem);if(existing.isPresent()){GamePlay g=existing.get();if(!g.store.id.equals(qr.store.id)||!g.phoneHash.equals(phones.hash(normalized)))throw new AppException("GAME_ALREADY_CREATED","이미 다른 게임에 사용된 요청 키입니다.");return g;}ParticipationService.State state=participation.state(qr.store.id,phone);if(state.state().equals("HAS_ACTIVE_COUPON"))throw new AppException("ACTIVE_COUPON_EXISTS","사용 가능한 쿠폰이 있습니다.");if(state.state().equals("COOLDOWN"))throw new AppException("PARTICIPATION_COOLDOWN",state.nextPlayableDate()+"부터 다시 참여하실 수 있습니다.");List<StoreOutcome> outcomes=config.load(qr.store.id);YutResult result=generator.generate(outcomes);int rank=outcomes.stream().filter(o->o.yutResult==result).mapToInt(o->o.prizeRank).findFirst().orElseThrow();Prize prize=prizes.findByStoreIdAndRank(qr.store.id,rank).filter(p->p.active).orElseThrow(()->new AppException("PRIZE_NOT_CONFIGURED","활성 상품이 설정되지 않았습니다."));Instant now=clock.instant();GamePlay g=new GamePlay();g.publicId=UUID.randomUUID().toString();g.store=qr.store;g.qrCode=qr;g.customerNameEncrypted=phones.encrypt(name.trim());g.phoneHash=phones.hash(normalized);g.phoneEncrypted=phones.encrypt(normalized);g.phoneLast4=normalized.substring(7);g.yutResult=result;g.prizeRank=rank;g.status=GameStatus.CREATED;g.animationSeed="seed_"+Tokens.random();g.idempotencyKey=idem;g.playedDate=LocalDate.now(clock);g.playedAt=now;games.save(g);Coupon c=new Coupon();c.store=qr.store;c.gamePlay=g;c.prize=prize;c.couponToken="cp_"+Tokens.random();c.phoneHash=g.phoneHash;c.prizeNameSnapshot=prize.name;c.prizeDescriptionSnapshot=prize.description;c.prizeRankSnapshot=rank;c.redeemPolicySnapshot=prize.redeemPolicy;c.status=CouponStatus.ISSUED;c.issuedAt=now;ZoneId zone=clock.getZone();LocalDate issued=g.playedDate;c.validFrom=prize.redeemPolicy==RedeemPolicy.NEXT_DAY?issued.plusDays(1).atStartOfDay(zone).toInstant():now;c.expiresAt=issued.plusDays(90).atTime(23,59,59).atZone(zone).toInstant();coupons.save(c);notifications.couponIssued(c);return g;}
    @Transactional Coupon reveal(String playId){GamePlay g=games.findByPublicId(playId).orElseThrow(()->new AppException("GAME_NOT_FOUND","게임을 찾을 수 없습니다.",org.springframework.http.HttpStatus.NOT_FOUND));if(g.status==GameStatus.CREATED){g.status=GameStatus.REVEALED;g.revealedAt=clock.instant();}return coupons.findByGamePlayId(g.id).orElseThrow();}
}
@Service class CouponService {
    private final CouponRepository coupons;private final PasswordEncoder encoder;private final Clock clock;private final PinAttemptLimiter limiter;
    CouponService(CouponRepository coupons,PasswordEncoder encoder,Clock clock,PinAttemptLimiter limiter){this.coupons=coupons;this.encoder=encoder;this.clock=clock;this.limiter=limiter;}
    @Transactional Coupon get(String token){Coupon c=coupons.findByCouponToken(token).orElseThrow(()->new AppException("COUPON_NOT_FOUND","쿠폰을 찾을 수 없습니다.",org.springframework.http.HttpStatus.NOT_FOUND));expire(c);return c;}
    @Transactional Coupon redeem(String token,String pin,String ip){Coupon c=coupons.findForUpdate(token).orElseThrow(()->new AppException("COUPON_NOT_FOUND","쿠폰을 찾을 수 없습니다.",org.springframework.http.HttpStatus.NOT_FOUND));Instant now=clock.instant();if(c.status==CouponStatus.REDEEMED)throw new AppException("COUPON_ALREADY_REDEEMED","이미 사용한 쿠폰입니다.");expire(c);if(c.status==CouponStatus.EXPIRED)throw new AppException("COUPON_EXPIRED","유효기간이 지난 쿠폰입니다.");if(c.status!=CouponStatus.ISSUED)throw new AppException("COUPON_NOT_ACTIVE","사용할 수 없는 쿠폰입니다.");if(now.isBefore(c.validFrom))throw new AppException("COUPON_NOT_YET_VALID","아직 사용할 수 없는 쿠폰입니다.");String key=ip+":"+c.store.id;limiter.attempt(key);if(!encoder.matches(pin,c.store.staffPinHash))throw new AppException("STAFF_PIN_INVALID","직원 PIN이 올바르지 않습니다.");limiter.succeeded(key);c.status=CouponStatus.REDEEMED;c.redeemedAt=now;return c;}
    private void expire(Coupon c){if(c.status==CouponStatus.ISSUED&&clock.instant().isAfter(c.expiresAt))c.status=CouponStatus.EXPIRED;}
}
