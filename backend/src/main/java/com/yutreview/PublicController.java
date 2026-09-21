package com.yutreview;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/public") class PublicController {
    /**
     * 상태 조회 + 회수 티켓 발급의 IP 상한.
     *
     * 게임 생성(IP 분당 10)보다 훨씬 넉넉해야 한다. 한국 모바일 손님은 상당수가 통신사 NAT 뒤라
     * 한 IP에 여러 명이 뭉치고, 게임 한 번에 이 호출이 최소 한 번씩 붙는다. 실제로 분당 20으로
     * 두고 돌렸더니 정상 흐름이 막혔다. 여기서 막는 것은 손님이 아니라 회수 티켓 행을 쌓는 반복 호출이다.
     */
    static final int STATE_LOOKUPS_PER_IP_PER_MINUTE=60;
    private final StoreAccessService access;private final PhoneService phones;private final ParticipationService participation;private final GameService games;private final CouponService coupons;private final PrizeRepository prizes;private final GameConfigService gameConfig;private final CouponRecoveryService recovery;private final ClientIpResolver clientIps;private final RateLimitService rateLimits;private final int stateLookupsPerIpPerMinute;
    PublicController(StoreAccessService access,PhoneService phones,ParticipationService participation,GameService games,CouponService coupons,PrizeRepository prizes,GameConfigService gameConfig,CouponRecoveryService recovery,ClientIpResolver clientIps,RateLimitService rateLimits,
        @org.springframework.beans.factory.annotation.Value("${app.limits.state-lookup-per-ip-per-minute:"+STATE_LOOKUPS_PER_IP_PER_MINUTE+"}") int stateLookupsPerIpPerMinute){this.access=access;this.phones=phones;this.participation=participation;this.games=games;this.coupons=coupons;this.prizes=prizes;this.gameConfig=gameConfig;this.recovery=recovery;this.clientIps=clientIps;this.rateLimits=rateLimits;this.stateLookupsPerIpPerMinute=stateLookupsPerIpPerMinute;}
    record CustomerStateRequest(@NotBlank @Size(max=100) String name,@NotBlank @Size(max=30) String phone,boolean privacyAgreed,@Size(max=20) String privacyConsentVersion){}
    record PinRequest(@Pattern(regexp="\\d{6}") String pin){}
    record RecoverRequest(@NotBlank @Size(max=100) String ticket){}
    record GameRequest(@NotBlank @Size(max=100) String storeToken,@NotBlank @Size(max=100) String name,@NotBlank @Size(max=30) String phone,@NotBlank @Size(max=100) String idempotencyKey,boolean privacyAgreed,@Size(max=20) String privacyConsentVersion){}
    @GetMapping("/stores/by-token/{token}") ApiResponse<?> store(@PathVariable String token){Store s=access.activeQr(token).store;return ApiResponse.ok(Map.of("name",s.name,"naverPlaceUrl",s.naverPlaceUrl==null?"":s.naverPlaceUrl,"posterTagline",s.posterTagline==null?"":s.posterTagline,"prizes",publicPrizes(s.id)));}
    /**
     * 참여 가능 여부.
     *
     * 예전에는 여기서 `couponToken`을 그대로 돌려줬다. 매장 QR과 번호만 알면 남의 쿠폰 bearer
     * token이 나왔다는 뜻이다. 지금은 5분·1회용 회수 티켓만 주고, 토큰은 그 티켓을 실제로 들고 온
     * 요청에만 연다.
     */
    @PostMapping("/stores/{token}/customer-state") ApiResponse<?> state(@PathVariable String token,@Valid @RequestBody CustomerStateRequest r,HttpServletRequest req){
        // 이 조회가 회수 티켓 행을 만든다. 쓰기가 생긴 공개 엔드포인트라 상한이 필요하다.
        // 손님 한 명이 번호를 잘못 눌러 몇 번 다시 시도하는 것보다는 충분히 넉넉하다.
        String ip=clientIps.resolve(req);if(!ip.isBlank())rateLimits.check("state-ip:"+ip,stateLookupsPerIpPerMinute,java.time.Duration.ofMinutes(1),"RATE_LIMITED","요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.");
        LegalConsentPolicy.requireCustomer(r.privacyAgreed,r.privacyConsentVersion);Store s=access.activeQr(token).store;ParticipationService.State x=participation.state(s.id,r.phone);String ticket=x.coupon()==null?"":recovery.issue(s,x.coupon(),x.coupon().phoneHash);return ApiResponse.ok(Map.of("state",x.state(),"nextPlayableDate",x.nextPlayableDate()==null?"":x.nextPlayableDate().toString(),"recoveryTicket",ticket));}
    /** 회수 티켓 1회 사용. 만료·재사용·타 매장은 구분 없이 같은 오류로 막힌다. */
    @PostMapping("/stores/{token}/coupons/recover") ApiResponse<?> recover(@PathVariable String token,@Valid @RequestBody RecoverRequest r){Store s=access.activeQr(token).store;return ApiResponse.ok(couponView(recovery.redeem(s.id,r.ticket()),false));}
    @PostMapping("/games") ApiResponse<?> create(@Valid @RequestBody GameRequest r,HttpServletRequest req){GamePlay g=games.create(r.storeToken,r.name,r.phone,r.idempotencyKey,r.privacyAgreed,r.privacyConsentVersion,clientIps.resolve(req));return ApiResponse.ok(Map.of("playId",g.publicId,"animationSeed",g.animationSeed,"animationProfile","STANDARD"));}
    @PostMapping("/games/{playId}/reveal") ApiResponse<?> reveal(@PathVariable String playId){return ApiResponse.ok(couponView(games.reveal(playId),true));}
    @GetMapping("/coupons/{token}") ApiResponse<?> coupon(@PathVariable String token){return ApiResponse.ok(couponView(coupons.get(token),false));}
    @PostMapping("/coupons/{token}/redeem") ApiResponse<?> redeem(@PathVariable String token,@Valid @RequestBody PinRequest r,HttpServletRequest req){return ApiResponse.ok(couponView(coupons.redeem(token,r.pin,clientIps.resolve(req)),false));}
    private Map<String,Object> couponView(Coupon c,boolean reveal){ZoneId z=ZoneId.of("Asia/Seoul");Map<String,Object> m=new java.util.LinkedHashMap<>();if(reveal){m.put("playId",c.gamePlay.publicId);m.put("yutResult",c.gamePlay.yutResult);}m.put("prizeRank",c.prizeRankSnapshot);m.put("couponToken",c.couponToken);m.put("status",c.status);m.put("prize",Map.of("name",c.prizeNameSnapshot,"description",c.prizeDescriptionSnapshot==null?"":c.prizeDescriptionSnapshot));m.put("redeemPolicy",c.redeemPolicySnapshot);m.put("validFrom",c.validFrom.atZone(z));m.put("expiresAt",c.expiresAt.atZone(z));return m;}
    /**
     * A rank nobody can reach is left out of the list. Advertising a prize next to a 0% chance of
     * winning it is the one thing showing odds to customers must never do.
     */
    private java.util.List<Map<String,Object>> publicPrizes(Long storeId){
        java.util.List<StoreOutcome> config=gameConfig.load(storeId);
        java.util.List<Map<String,Object>> view=new java.util.ArrayList<>();
        for(Prize p:prizes.findByStoreIdOrderByRank(storeId)){
            if(!p.active)continue;
            double odds=GameConfigService.odds(config,o->o.prizeRank==p.rank);
            if(odds<=0)continue;
            view.add(Map.of("rank",p.rank,"name",p.name,"description",p.description==null?"":p.description,"odds",odds));
        }
        return view;
    }
}
