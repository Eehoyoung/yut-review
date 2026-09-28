package com.yutreview;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
class LegalConsentService {
    private final LegalConsentEventRepository events;
    private final ObjectMapper json;
    private final Clock clock;

    LegalConsentService(LegalConsentEventRepository events,ObjectMapper json,Clock clock){this.events=events;this.json=json;this.clock=clock;}

    void recordSignup(AdminUser admin){
        record(admin,null,LegalConsentType.SERVICE_TERMS,true,LegalConsentPolicy.TERMS_VERSION,"SIGNUP",
            "서비스 이용약관에 동의합니다.",Map.of());
        record(admin,null,LegalConsentType.ADMIN_PRIVACY,true,LegalConsentPolicy.ADMIN_PRIVACY_VERSION,"SIGNUP",
            "개인정보 수집·이용에 동의합니다.",Map.of());
    }

    void recordBillingChoice(AdminUser admin,Store store,boolean agreed,String version,Plan plan,int amount,Instant firstBillingAt,String source){
        if(!LegalConsentPolicy.BILLING_AUTO_PAYMENT_VERSION.equals(version))
            throw new AppException("BILLING_CONSENT_VERSION_INVALID","최신 자동결제 동의 내용을 확인해 주세요.");
        String text="무료체험 종료 또는 결제주기 도래 시 선택한 요금제의 월 이용료가 등록 카드로 자동결제되는 데 동의합니다. (필수)";
        record(admin,store,LegalConsentType.BILLING_AUTO_PAYMENT,agreed,version,source,text,Map.of(
            "plan",plan.name(),"monthlyPriceKrw",plan.monthlyPriceKrw,"immediateChargeKrw",amount,
            "firstBillingAt",firstBillingAt==null?"":firstBillingAt.toString()));
    }

    void recordAutoRenew(AdminUser admin,Store store,boolean agreed,Plan plan,Instant nextBillingAt){
        recordBillingChoice(admin,store,agreed,LegalConsentPolicy.BILLING_AUTO_PAYMENT_VERSION,plan,0,nextBillingAt,"BILLING_SETTINGS");
    }

    private void record(AdminUser admin,Store store,LegalConsentType type,boolean agreed,String version,String source,String text,Map<String,Object> details){
        LegalConsentEvent event=new LegalConsentEvent();event.admin=admin;event.store=store;event.consentType=type;
        event.agreed=agreed;event.documentVersion=version;event.source=source;event.consentText=text;
        try{event.detailsJson=json.writeValueAsString(details);}catch(JsonProcessingException e){throw new IllegalStateException(e);}
        event.createdAt=clock.instant();events.save(event);
    }
}
