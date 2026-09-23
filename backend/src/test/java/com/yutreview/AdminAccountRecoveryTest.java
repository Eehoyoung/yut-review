package com.yutreview;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.*;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest @Transactional class AdminAccountRecoveryTest {
    @Autowired AdminAccountRecoveryService recovery;@Autowired AdminUserRepository admins;@Autowired StoreRepository stores;
    @Autowired MembershipRepository memberships;@Autowired PasswordEncoder encoder;@Autowired Clock clock;
    @MockitoBean RecoveryMailService mail;
    AdminUser admin;

    @BeforeEach void setup(){
        admin=new AdminUser();admin.email="recover@example.com";admin.passwordHash=encoder.encode("oldsecret12");admin.name="김대표";admin.phone="01012345678";admin.role=AdminRole.STORE_ADMIN;admin.createdAt=clock.instant();admins.save(admin);
        Store store=new Store();store.name="복구매장";store.businessNumber="1234567890";store.phone=admin.phone;store.representativeName="김대표";store.openingDate="20230101";store.businessVerifiedAt=clock.instant();store.staffPinHash=encoder.encode("123456");store.status=StoreStatus.ACTIVE;store.createdAt=clock.instant();store.updatedAt=clock.instant();stores.save(store);
        AdminStoreMembership m=new AdminStoreMembership();m.admin=admin;m.store=store;m.role=MembershipRole.OWNER;m.createdAt=clock.instant();memberships.save(m);
    }

    @Test void emailIsRevealedOnlyAfterCorrectOneTimeCode(){
        Map<String,Object> issued=recovery.request(AccountRecoveryPurpose.FIND_EMAIL,null,"1234567890","20230101","김대표","01012345678","198.51.100.10");
        ArgumentCaptor<String> code=ArgumentCaptor.forClass(String.class);verify(mail).sendCode(eq("recover@example.com"),code.capture());
        assertEquals("recover@example.com",recovery.verify(AccountRecoveryPurpose.FIND_EMAIL,(String)issued.get("challengeToken"),code.getValue(),null,null).get("email"));
        assertEquals("RECOVERY_CODE_INVALID",assertThrows(AppException.class,()->recovery.verify(AccountRecoveryPurpose.FIND_EMAIL,(String)issued.get("challengeToken"),code.getValue(),null,null)).code);
    }

    @Test void passwordResetRequiresMatchingEmailAndCode(){
        assertEquals("ACCOUNT_RECOVERY_NOT_FOUND",assertThrows(AppException.class,()->recovery.request(AccountRecoveryPurpose.RESET_PASSWORD,"wrong@example.com","1234567890","20230101","김대표","01012345678","198.51.100.11")).code);
        Map<String,Object> issued=recovery.request(AccountRecoveryPurpose.RESET_PASSWORD,"recover@example.com","1234567890","20230101","김대표","01012345678","198.51.100.12");
        ArgumentCaptor<String> code=ArgumentCaptor.forClass(String.class);verify(mail).sendCode(eq("recover@example.com"),code.capture());
        recovery.verify(AccountRecoveryPurpose.RESET_PASSWORD,(String)issued.get("challengeToken"),code.getValue(),"newsecret12","newsecret12");
        assertTrue(encoder.matches("newsecret12",admins.findByEmail("recover@example.com").orElseThrow().passwordHash));
    }
}
