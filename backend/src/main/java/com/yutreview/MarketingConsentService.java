package com.yutreview;

import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class MarketingConsentService {
    record ConsentView(MarketingService service,String serviceName,boolean agreed,String version,java.time.Instant changedAt) {}
    private static final Map<MarketingService,String> NAMES=Map.of(
        MarketingService.YUT_REVIEW,"윷리뷰",MarketingService.REVIEW_PILOT,"리뷰파일럿",MarketingService.SODAM,"소담");
    private final MarketingConsentEventRepository events;
    private final Clock clock;
    MarketingConsentService(MarketingConsentEventRepository events,Clock clock){this.events=events;this.clock=clock;}

    @Transactional void recordInitial(AdminUser admin,Map<MarketingService,Boolean> choices,String version){
        if(choices.values().stream().anyMatch(Boolean.TRUE::equals)&&!LegalConsentPolicy.MARKETING_SMS_VERSION.equals(version))
            throw new AppException("MARKETING_CONSENT_VERSION_INVALID","최신 광고성 문자 수신동의 내용을 확인해 주세요.");
        for(MarketingService service:MarketingService.values())record(admin,service,Boolean.TRUE.equals(choices.get(service)),"SIGNUP");
    }
    @Transactional ConsentView update(AdminUser admin,MarketingService service,boolean agreed,String version){
        if(!LegalConsentPolicy.MARKETING_SMS_VERSION.equals(version))throw new AppException("MARKETING_CONSENT_VERSION_INVALID","최신 광고성 문자 수신동의 내용을 확인해 주세요.");
        return view(record(admin,service,agreed,"SETTINGS"));
    }
    List<ConsentView> current(Long adminId){return Arrays.stream(MarketingService.values()).map(service->events.findFirstByAdminIdAndServiceOrderByChangedAtDescIdDesc(adminId,service)
        .map(this::view).orElse(new ConsentView(service,NAMES.get(service),false,LegalConsentPolicy.MARKETING_SMS_VERSION,null))).toList();}
    boolean maySend(Long adminId,MarketingService service){return events.findFirstByAdminIdAndServiceOrderByChangedAtDescIdDesc(adminId,service).map(e->e.agreed).orElse(false);}
    private MarketingConsentEvent record(AdminUser admin,MarketingService service,boolean agreed,String source){MarketingConsentEvent event=new MarketingConsentEvent();event.admin=admin;event.service=service;event.agreed=agreed;event.consentVersion=LegalConsentPolicy.MARKETING_SMS_VERSION;event.source=source;event.changedAt=clock.instant();return events.save(event);}
    private ConsentView view(MarketingConsentEvent event){return new ConsentView(event.service,NAMES.get(event.service),event.agreed,event.consentVersion,event.changedAt);}
}
