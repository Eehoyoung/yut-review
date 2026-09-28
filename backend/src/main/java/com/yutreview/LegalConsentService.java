package com.yutreview;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
class LegalConsentService {
    private static final DateTimeFormatter BILLING_DATE =
            DateTimeFormatter.ofPattern("yyyy.MM.dd").withZone(ZoneId.of("Asia/Seoul"));

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

    static String billingConsentText(Plan plan,int amount,boolean prorated,Instant nextAutomaticBillingAt){
        String monthly=String.format("%,d",plan.monthlyPriceKrw);
        String immediate=amount<=0
            ?"지금 추가 결제되는 금액은 0원이며"
            :prorated
                ?"지금 잔여 이용기간의 일할 차액 "+String.format("%,d",amount)+"원이 결제되며"
                :"지금 "+String.format("%,d",amount)+"원이 결제되며";
        String next=nextAutomaticBillingAt==null?"다음 결제예정일부터":BILLING_DATE.format(nextAutomaticBillingAt)+"부터";
        return "소담한판 "+plan.name()+" 요금제(월 "+monthly+"원)를 선택합니다. "+immediate+", "
            +next+" 등록 카드로 월 "+monthly+"원이 자동결제되는 데 동의합니다. (필수)";
    }

    void recordBillingChoice(AdminUser admin,Store store,boolean agreed,String version,Plan plan,int amount,boolean prorated,Instant nextAutomaticBillingAt,String source){
        if(!LegalConsentPolicy.BILLING_AUTO_PAYMENT_VERSION.equals(version))
            throw new AppException("BILLING_CONSENT_VERSION_INVALID","최신 자동결제 동의 내용을 확인해 주세요.");
        String text=billingConsentText(plan,amount,prorated,nextAutomaticBillingAt);
        record(admin,store,LegalConsentType.BILLING_AUTO_PAYMENT,agreed,version,source,text,Map.of(
            "plan",plan.name(),"monthlyPriceKrw",plan.monthlyPriceKrw,"immediateChargeKrw",amount,
            "prorated",prorated,"nextAutomaticBillingAt",nextAutomaticBillingAt==null?"":nextAutomaticBillingAt.toString()));
    }

    void recordAutoRenew(AdminUser admin,Store store,boolean agreed,Plan plan,Instant nextBillingAt){
        recordBillingChoice(admin,store,agreed,LegalConsentPolicy.BILLING_AUTO_PAYMENT_VERSION,plan,0,false,nextBillingAt,"BILLING_SETTINGS");
    }

    private void record(AdminUser admin,Store store,LegalConsentType type,boolean agreed,String version,String source,String text,Map<String,Object> details){
        LegalConsentEvent event=new LegalConsentEvent();event.admin=admin;event.store=store;event.consentType=type;
        event.agreed=agreed;event.documentVersion=version;event.source=source;event.consentText=text;
        try{event.detailsJson=json.writeValueAsString(details);}catch(JsonProcessingException e){throw new IllegalStateException(e);}
        event.createdAt=clock.instant();events.save(event);
    }
}
