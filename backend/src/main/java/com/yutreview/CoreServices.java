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
    String normalize(String phone){String p=phone==null?"":phone.replaceAll("[ -]","");if(!p.matches("010\\d{8}"))throw new AppException("INVALID_PHONE","올바른 휴대전화 번호를 입력해 주세요.");return p;}
    String hash(String phone){try{Mac m=Mac.getInstance("HmacSHA256");m.init(new SecretKeySpec(hmacKey,"HmacSHA256"));return HexFormat.of().formatHex(m.doFinal(normalize(phone).getBytes(StandardCharsets.UTF_8)));}catch(GeneralSecurityException e){throw new IllegalStateException(e);}}
    String encrypt(String value){try{byte[] iv=new byte[12];SecureRandom.getInstanceStrong().nextBytes(iv);Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(encryptionKey,"AES"),new GCMParameterSpec(128,iv));byte[] encrypted=cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));byte[] packed=new byte[iv.length+encrypted.length];System.arraycopy(iv,0,packed,0,iv.length);System.arraycopy(encrypted,0,packed,iv.length,encrypted.length);return Base64.getEncoder().encodeToString(packed);}catch(GeneralSecurityException e){throw new IllegalStateException("Personal data encryption failed",e);}}
    String decrypt(String value){try{byte[] packed=Base64.getDecoder().decode(value),iv=Arrays.copyOfRange(packed,0,12);Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(encryptionKey,"AES"),new GCMParameterSpec(128,iv));return new String(cipher.doFinal(Arrays.copyOfRange(packed,12,packed.length)),StandardCharsets.UTF_8);}catch(GeneralSecurityException|IllegalArgumentException e){throw new IllegalStateException("Personal data decryption failed",e);}}
}
@Service class StoreAccessService {
    private final QrRepository qrs; private final MembershipRepository memberships;
    StoreAccessService(QrRepository qrs,MembershipRepository memberships){this.qrs=qrs;this.memberships=memberships;}
    StoreQrCode activeQr(String token){StoreQrCode q=qrs.findByPublicToken(token).orElseThrow(()->new AppException("QR_TOKEN_INVALID","유효하지 않은 QR입니다.",org.springframework.http.HttpStatus.NOT_FOUND));if(q.status==QrStatus.REVOKED)throw new AppException("QR_TOKEN_REVOKED","폐기된 QR입니다.");if(q.store.status!=StoreStatus.ACTIVE)throw new AppException("STORE_INACTIVE","운영 중인 매장이 아닙니다.");return q;}
    void member(Long adminId,Long storeId){if(adminId==null||!memberships.existsByAdminIdAndStoreId(adminId,storeId))throw new AppException("FORBIDDEN","매장 접근 권한이 없습니다.",org.springframework.http.HttpStatus.FORBIDDEN);}
}
@Service class GameResultGenerator {
    private final SecureRandom random; GameResultGenerator(SecureRandom random){this.random=random;}
    YutResult generate(){return from(random.nextDouble());}
    static YutResult from(double r){if(r<0||r>=1)throw new IllegalArgumentException();if(r<.325)return YutResult.DO;if(r<.65)return YutResult.GAE;if(r<.775)return YutResult.GEOL;if(r<.9)return YutResult.YUT;return YutResult.MO;}
}
@Service class StaffVerificationService {
    private final StoreAccessService access; private final StaffVerificationRepository repo; private final PasswordEncoder encoder; private final Clock clock; private final PinAttemptLimiter limiter;
    StaffVerificationService(StoreAccessService access,StaffVerificationRepository repo,PasswordEncoder encoder,Clock clock,PinAttemptLimiter limiter){this.access=access;this.repo=repo;this.encoder=encoder;this.clock=clock;this.limiter=limiter;}
    @Transactional String verify(String storeToken,String pin,String ip){StoreQrCode qr=access.activeQr(storeToken);String key=ip+":"+qr.store.id;limiter.attempt(key);Instant now=clock.instant();if(pin==null||!pin.matches("\\d{6}")||!encoder.matches(pin,qr.store.staffPinHash))throw new AppException("STAFF_PIN_INVALID","직원 PIN이 올바르지 않습니다.");limiter.succeeded(key);StaffVerification v=new StaffVerification();v.store=qr.store;v.token="staff_verify_"+randomToken();v.expiresAt=now.plusSeconds(180);repo.save(v);return v.token;}
    @Transactional void consume(String token,Long storeId){StaffVerification v=repo.findForUpdate(token).orElseThrow(()->new AppException("STAFF_VERIFICATION_EXPIRED","직원 승인이 만료되었습니다."));if(v.used||!v.store.id.equals(storeId)||!clock.instant().isBefore(v.expiresAt))throw new AppException("STAFF_VERIFICATION_EXPIRED","직원 승인이 만료되었습니다.");v.used=true;}
    static String randomToken(){byte[] b=new byte[24];new SecureRandom().nextBytes(b);return Base64.getUrlEncoder().withoutPadding().encodeToString(b);}
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
    private final StoreAccessService access;private final StoreRepository stores;private final PhoneService phones;private final ParticipationService participation;private final StaffVerificationService verification;private final GameResultGenerator generator;private final GameRepository games;private final PrizeRepository prizes;private final CouponRepository coupons;private final Clock clock;private final NotificationService notifications;
    GameService(StoreAccessService access,StoreRepository stores,PhoneService phones,ParticipationService participation,StaffVerificationService verification,GameResultGenerator generator,GameRepository games,PrizeRepository prizes,CouponRepository coupons,Clock clock,NotificationService notifications){this.access=access;this.stores=stores;this.phones=phones;this.participation=participation;this.verification=verification;this.generator=generator;this.games=games;this.prizes=prizes;this.coupons=coupons;this.clock=clock;this.notifications=notifications;}
    @Transactional GamePlay create(String token,String name,String phone,String verifyToken,String idem){if(idem==null||idem.isBlank())throw new AppException("INVALID_REQUEST","idempotencyKey가 필요합니다.");StoreQrCode qr=access.activeQr(token);stores.findForUpdate(qr.store.id).orElseThrow(); // ponytail: store-wide lock is enough for single-instance MVP; narrow to customer-key locks if throughput matters.
        String normalized=phones.normalize(phone);Optional<GamePlay> existing=games.findByIdempotencyKey(idem);if(existing.isPresent()){GamePlay g=existing.get();if(!g.store.id.equals(qr.store.id)||!g.phoneHash.equals(phones.hash(normalized)))throw new AppException("GAME_ALREADY_CREATED","이미 다른 게임에 사용된 요청 키입니다.");return g;}ParticipationService.State state=participation.state(qr.store.id,phone);if(state.state().equals("HAS_ACTIVE_COUPON"))throw new AppException("ACTIVE_COUPON_EXISTS","사용 가능한 쿠폰이 있습니다.");if(state.state().equals("COOLDOWN"))throw new AppException("PARTICIPATION_COOLDOWN",state.nextPlayableDate()+"부터 다시 참여하실 수 있습니다.");verification.consume(verifyToken,qr.store.id);YutResult result=generator.generate();Prize prize=prizes.findByStoreIdAndTier(qr.store.id,result.tier).filter(p->p.active).orElseThrow(()->new AppException("PRIZE_NOT_CONFIGURED","활성 상품이 설정되지 않았습니다."));Instant now=clock.instant();GamePlay g=new GamePlay();g.publicId=UUID.randomUUID().toString();g.store=qr.store;g.qrCode=qr;g.customerNameEncrypted=phones.encrypt(name.trim());g.phoneHash=phones.hash(normalized);g.phoneEncrypted=phones.encrypt(normalized);g.phoneLast4=normalized.substring(7);g.yutResult=result;g.rewardTier=result.tier;g.status=GameStatus.CREATED;g.animationSeed="seed_"+StaffVerificationService.randomToken();g.idempotencyKey=idem;g.playedDate=LocalDate.now(clock);g.playedAt=now;games.save(g);Coupon c=new Coupon();c.store=qr.store;c.gamePlay=g;c.prize=prize;c.couponToken="cp_"+StaffVerificationService.randomToken();c.phoneHash=g.phoneHash;c.prizeNameSnapshot=prize.name;c.prizeDescriptionSnapshot=prize.description;c.redeemPolicySnapshot=prize.redeemPolicy;c.status=CouponStatus.ISSUED;c.issuedAt=now;ZoneId zone=clock.getZone();LocalDate issued=g.playedDate;c.validFrom=prize.redeemPolicy==RedeemPolicy.NEXT_DAY?issued.plusDays(1).atStartOfDay(zone).toInstant():now;c.expiresAt=issued.plusDays(90).atTime(23,59,59).atZone(zone).toInstant();coupons.save(c);notifications.couponIssued(c);return g;}
    @Transactional Coupon reveal(String playId){GamePlay g=games.findByPublicId(playId).orElseThrow(()->new AppException("GAME_NOT_FOUND","게임을 찾을 수 없습니다.",org.springframework.http.HttpStatus.NOT_FOUND));if(g.status==GameStatus.CREATED){g.status=GameStatus.REVEALED;g.revealedAt=clock.instant();}return coupons.findByGamePlayId(g.id).orElseThrow();}
}
@Service class CouponService {
    private final CouponRepository coupons;private final PasswordEncoder encoder;private final Clock clock;private final PinAttemptLimiter limiter;
    CouponService(CouponRepository coupons,PasswordEncoder encoder,Clock clock,PinAttemptLimiter limiter){this.coupons=coupons;this.encoder=encoder;this.clock=clock;this.limiter=limiter;}
    @Transactional Coupon get(String token){Coupon c=coupons.findByCouponToken(token).orElseThrow(()->new AppException("COUPON_NOT_FOUND","쿠폰을 찾을 수 없습니다.",org.springframework.http.HttpStatus.NOT_FOUND));expire(c);return c;}
    @Transactional Coupon redeem(String token,String pin,String ip){Coupon c=coupons.findForUpdate(token).orElseThrow(()->new AppException("COUPON_NOT_FOUND","쿠폰을 찾을 수 없습니다.",org.springframework.http.HttpStatus.NOT_FOUND));Instant now=clock.instant();if(c.status==CouponStatus.REDEEMED)throw new AppException("COUPON_ALREADY_REDEEMED","이미 사용한 쿠폰입니다.");expire(c);if(c.status==CouponStatus.EXPIRED)throw new AppException("COUPON_EXPIRED","유효기간이 지난 쿠폰입니다.");if(c.status!=CouponStatus.ISSUED)throw new AppException("COUPON_NOT_ACTIVE","사용할 수 없는 쿠폰입니다.");if(now.isBefore(c.validFrom))throw new AppException("COUPON_NOT_YET_VALID","아직 사용할 수 없는 쿠폰입니다.");String key=ip+":"+c.store.id;limiter.attempt(key);if(!encoder.matches(pin,c.store.staffPinHash))throw new AppException("STAFF_PIN_INVALID","직원 PIN이 올바르지 않습니다.");limiter.succeeded(key);c.status=CouponStatus.REDEEMED;c.redeemedAt=now;return c;}
    private void expire(Coupon c){if(c.status==CouponStatus.ISSUED&&clock.instant().isAfter(c.expiresAt))c.status=CouponStatus.EXPIRED;}
}
