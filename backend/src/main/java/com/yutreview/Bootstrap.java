package com.yutreview;

import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component class Bootstrap implements CommandLineRunner {
    private final boolean enabled;private final String email,password,storeName,storePhone,naverPlaceUrl,pin;private final AdminUserRepository admins;private final StoreProvisioningService provisioning;private final PasswordEncoder encoder;private final Clock clock;
    Bootstrap(@Value("${app.bootstrap.enabled:false}") boolean enabled,@Value("${app.bootstrap.email:}") String email,@Value("${app.bootstrap.password:}") String password,@Value("${app.bootstrap.store-name:}") String storeName,@Value("${app.bootstrap.store-phone:}") String storePhone,@Value("${app.bootstrap.naver-place-url:}") String naverPlaceUrl,@Value("${app.bootstrap.staff-pin:}") String pin,AdminUserRepository admins,StoreProvisioningService provisioning,PasswordEncoder encoder,Clock clock){this.enabled=enabled;this.email=email;this.password=password;this.storeName=storeName;this.storePhone=storePhone;this.naverPlaceUrl=naverPlaceUrl;this.pin=pin;this.admins=admins;this.provisioning=provisioning;this.encoder=encoder;this.clock=clock;}
    @Override @Transactional public void run(String... args){if(!enabled||admins.findByEmail(email.toLowerCase()).isPresent())return;if(password.length()<12||!pin.matches("\\d{6}"))throw new IllegalStateException("Bootstrap password/PIN configuration is invalid");AdminUser a=new AdminUser();a.email=email.toLowerCase();a.passwordHash=encoder.encode(password);a.name="System Admin";a.role=AdminRole.SYSTEM_ADMIN;a.createdAt=clock.instant();admins.save(a);provisioning.provision(a,storeName,storePhone,null,null,naverPlaceUrl,pin);}
}
