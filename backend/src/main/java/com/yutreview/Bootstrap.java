package com.yutreview;

import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component class Bootstrap implements CommandLineRunner {
    private final boolean enabled;private final String email,password,storeName,storePhone,naverPlaceUrl,pin;private final AdminUserRepository admins;private final StoreRepository stores;private final MembershipRepository memberships;private final QrRepository qrs;private final PrizeRepository prizes;private final PasswordEncoder encoder;private final Clock clock;
    Bootstrap(@Value("${app.bootstrap.enabled:false}") boolean enabled,@Value("${app.bootstrap.email:}") String email,@Value("${app.bootstrap.password:}") String password,@Value("${app.bootstrap.store-name:}") String storeName,@Value("${app.bootstrap.store-phone:}") String storePhone,@Value("${app.bootstrap.naver-place-url:}") String naverPlaceUrl,@Value("${app.bootstrap.staff-pin:}") String pin,AdminUserRepository admins,StoreRepository stores,MembershipRepository memberships,QrRepository qrs,PrizeRepository prizes,PasswordEncoder encoder,Clock clock){this.enabled=enabled;this.email=email;this.password=password;this.storeName=storeName;this.storePhone=storePhone;this.naverPlaceUrl=naverPlaceUrl;this.pin=pin;this.admins=admins;this.stores=stores;this.memberships=memberships;this.qrs=qrs;this.prizes=prizes;this.encoder=encoder;this.clock=clock;}
    @Override @Transactional public void run(String... args){if(!enabled||admins.findByEmail(email.toLowerCase()).isPresent())return;if(password.length()<12||!pin.matches("\\d{6}"))throw new IllegalStateException("Bootstrap password/PIN configuration is invalid");var now=clock.instant();AdminUser a=new AdminUser();a.email=email.toLowerCase();a.passwordHash=encoder.encode(password);a.name="System Admin";a.role=AdminRole.SYSTEM_ADMIN;a.createdAt=now;admins.save(a);Store s=new Store();s.name=storeName;s.phone=storePhone;s.naverPlaceUrl=naverPlaceUrl;s.staffPinHash=encoder.encode(pin);s.status=StoreStatus.ACTIVE;s.createdAt=now;s.updatedAt=now;stores.save(s);AdminStoreMembership m=new AdminStoreMembership();m.admin=a;m.store=s;m.role=MembershipRole.OWNER;m.createdAt=now;memberships.save(m);StoreQrCode q=new StoreQrCode();q.store=s;q.publicToken=StaffVerificationService.randomToken();q.status=QrStatus.ACTIVE;q.createdAt=now;qrs.save(q);for(Tier t:Tier.values()){Prize p=new Prize();p.store=s;p.tier=t;p.name=t.name()+" 상품";p.description="관리자에서 상품을 설정하세요.";p.redeemPolicy=RedeemPolicy.ANYTIME;p.active=true;p.createdAt=now;p.updatedAt=now;prizes.save(p);}}
}
