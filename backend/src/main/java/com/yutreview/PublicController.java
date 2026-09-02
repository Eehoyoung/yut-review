package com.yutreview;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/public") class PublicController {
    private final StoreAccessService access;private final PhoneService phones;private final ParticipationService participation;private final StaffVerificationService verification;private final GameService games;private final CouponService coupons;
    PublicController(StoreAccessService access,PhoneService phones,ParticipationService participation,StaffVerificationService verification,GameService games,CouponService coupons){this.access=access;this.phones=phones;this.participation=participation;this.verification=verification;this.games=games;this.coupons=coupons;}
    record CustomerStateRequest(@NotBlank @Size(max=100) String name,@NotBlank @Size(max=30) String phone,boolean privacyAgreed){}
    record PinRequest(@Pattern(regexp="\\d{6}") String pin){}
    record GameRequest(@NotBlank @Size(max=100) String storeToken,@NotBlank @Size(max=100) String name,@NotBlank @Size(max=30) String phone,@NotBlank @Size(max=100) String staffVerificationToken,@NotBlank @Size(max=100) String idempotencyKey){}
    @GetMapping("/stores/by-token/{token}") ApiResponse<?> store(@PathVariable String token){Store s=access.activeQr(token).store;return ApiResponse.ok(Map.of("name",s.name,"naverPlaceUrl",s.naverPlaceUrl==null?"":s.naverPlaceUrl));}
    @PostMapping("/stores/{token}/customer-state") ApiResponse<?> state(@PathVariable String token,@Valid @RequestBody CustomerStateRequest r){if(!r.privacyAgreed)throw new AppException("PRIVACY_CONSENT_REQUIRED","개인정보 수집에 동의해 주세요.");Store s=access.activeQr(token).store;ParticipationService.State x=participation.state(s.id,r.phone);return ApiResponse.ok(Map.of("state",x.state(),"nextPlayableDate",x.nextPlayableDate()==null?"":x.nextPlayableDate().toString(),"couponToken",x.coupon()==null?"":x.coupon().couponToken));}
    @PostMapping("/stores/{token}/staff-verify") ApiResponse<?> verify(@PathVariable String token,@Valid @RequestBody PinRequest r,HttpServletRequest req){return ApiResponse.ok(Map.of("verificationToken",verification.verify(token,r.pin,clientIp(req)),"expiresInSeconds",180));}
    @PostMapping("/games") ApiResponse<?> create(@Valid @RequestBody GameRequest r){GamePlay g=games.create(r.storeToken,r.name,r.phone,r.staffVerificationToken,r.idempotencyKey);return ApiResponse.ok(Map.of("playId",g.publicId,"animationSeed",g.animationSeed,"animationProfile","STANDARD"));}
    @PostMapping("/games/{playId}/reveal") ApiResponse<?> reveal(@PathVariable String playId){return ApiResponse.ok(couponView(games.reveal(playId),true));}
    @GetMapping("/coupons/{token}") ApiResponse<?> coupon(@PathVariable String token){return ApiResponse.ok(couponView(coupons.get(token),false));}
    @PostMapping("/coupons/{token}/redeem") ApiResponse<?> redeem(@PathVariable String token,@Valid @RequestBody PinRequest r,HttpServletRequest req){return ApiResponse.ok(couponView(coupons.redeem(token,r.pin,clientIp(req)),false));}
    private Map<String,Object> couponView(Coupon c,boolean reveal){ZoneId z=ZoneId.of("Asia/Seoul");Map<String,Object> m=new java.util.LinkedHashMap<>();if(reveal){m.put("playId",c.gamePlay.publicId);m.put("yutResult",c.gamePlay.yutResult);m.put("tier",c.gamePlay.rewardTier);}m.put("couponToken",c.couponToken);m.put("status",c.status);m.put("prize",Map.of("name",c.prizeNameSnapshot,"description",c.prizeDescriptionSnapshot==null?"":c.prizeDescriptionSnapshot));m.put("redeemPolicy",c.redeemPolicySnapshot);m.put("validFrom",c.validFrom.atZone(z));m.put("expiresAt",c.expiresAt.atZone(z));return m;}
    private String clientIp(HttpServletRequest r){String proxied=r.getHeader("X-Real-IP");return proxied==null||proxied.isBlank()?r.getRemoteAddr():proxied;}
}
