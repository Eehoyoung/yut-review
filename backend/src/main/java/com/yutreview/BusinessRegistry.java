package com.yutreview;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 국세청 사업자등록정보 진위확인.
 *
 * 공공데이터포털 `api.odcloud.kr/api/nts-businessman/v1/validate`. 사업자등록번호·개업일자·
 * 대표자성명 **세 개가 모두 맞아야** 통과한다. 번호 하나만 보면 인터넷에서 주운 번호로 가입할 수
 * 있지만, 개업일자와 대표자명까지 맞추려면 실제 사업자등록증을 봐야 한다.
 *
 * 운영자 승인 대기 큐를 껐기 때문에(2026-09-22) 사람이 사업자등록증을 확인하는 단계가 없다.
 * 그 자리를 이 API가 메운다. 둘 중 하나는 반드시 있어야 하고, 지금은 이쪽이다.
 *
 * <h2>되돌리면 안 되는 지점</h2>
 *
 * 호출을 `@Transactional` 안에 넣지 말 것. 외부 호출이 응답을 기다리는 동안 DB 커넥션을 붙들어
 * 풀이 마른다(AI 공급자 호출에서 같은 규칙을 이미 적어 뒀다).
 *
 * 실패를 열어 주지 말 것. 국세청이 죽었을 때 가입을 통과시키면 그 시간 동안 아무 번호나 들어온다.
 * 가입은 손님 흐름이 아니라 급하지 않다. 막고 나중에 다시 받는 쪽이 맞다.
 */
@Service class BusinessRegistryService {
    private static final Logger log=LoggerFactory.getLogger(BusinessRegistryService.class);
    private static final URI ENDPOINT_BASE=URI.create("https://api.odcloud.kr/api/nts-businessman/v1/validate");
    /** 가입 한 건이 이보다 오래 기다릴 이유가 없다. 넘으면 국세청이 느린 것이고 다시 시도하면 된다. */
    private static final Duration TIMEOUT=Duration.ofSeconds(8);

    private final boolean enabled;private final String serviceKey;private final HttpClient http;
    private final com.fasterxml.jackson.databind.ObjectMapper json=new com.fasterxml.jackson.databind.ObjectMapper();

    BusinessRegistryService(@Value("${app.business-verification.enabled:false}") boolean enabled,
        @Value("${app.business-verification.service-key:}") String serviceKey){
        this.enabled=enabled;this.serviceKey=serviceKey==null?"":serviceKey.trim();
        this.http=HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
        if(enabled&&this.serviceKey.isEmpty())
            throw new IllegalStateException("NTS_SERVICE_KEY must be set when business verification is enabled");
    }

    boolean enabled(){return enabled;}

    /**
     * 진위확인. 통과하지 못하면 던진다.
     *
     * @param businessNumber 숫자 10자리(정규화 완료)
     * @param openingDate    개업일자 YYYYMMDD(정규화 완료)
     * @param ownerName      대표자성명
     */
    void verify(String businessNumber,String openingDate,String ownerName){
        if(!enabled)return;
        JsonNode body;
        try{
            String payload=json.writeValueAsString(Map.of("businesses",
                java.util.List.of(Map.of("b_no",businessNumber,"start_dt",openingDate,"p_nm",ownerName))));
            HttpResponse<String> response=http.send(
                HttpRequest.newBuilder(endpoint()).timeout(TIMEOUT)
                    .header("Content-Type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload,StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if(response.statusCode()!=200){
                // 응답 본문을 로그에 남기지 않는다. 오류 본문에 요청 파라미터가 그대로 되돌아오는
                // 경우가 있고, 거기에는 대표자 성명이 들어 있다.
                log.warn("business verification upstream returned {}",response.statusCode());
                throw unavailable();
            }
            body=json.readTree(response.body());
        }catch(AppException e){
            throw e;
        }catch(Exception e){
            log.warn("business verification call failed: {}",e.getClass().getSimpleName());
            throw unavailable();
        }

        JsonNode first=body.path("data").path(0);
        if(first.isMissingNode())throw unavailable();
        // valid "01"이 일치, "02"가 불일치다. 다른 값은 규격에 없으므로 통과시키지 않는다.
        if(!"01".equals(first.path("valid").asText()))
            throw new AppException("BUSINESS_NOT_VERIFIED",
                "국세청에 등록된 사업자 정보와 일치하지 않습니다. 사업자등록증의 번호·개업일자·대표자명을 확인해 주세요.");

        // 등록은 돼 있으나 문을 닫은 사업자는 받지 않는다. 진위확인만 보면 폐업자도 "일치"다.
        String state=first.path("status").path("b_stt_cd").asText();
        if("03".equals(state))
            throw new AppException("BUSINESS_CLOSED","폐업 처리된 사업자등록번호입니다.");
        if("02".equals(state))
            throw new AppException("BUSINESS_SUSPENDED","휴업 중인 사업자등록번호입니다.");
    }

    /**
     * 실제로 부를 주소.
     *
     * `serviceKey`는 쿼리에 붙는다. 이미 URL 인코딩된 키를 그대로 넣으면 이중 인코딩되므로
     * 원문 키를 받아 여기서 한 번만 인코딩한다(공공데이터포털이 키를 두 가지로 주는데,
     * 넣어야 하는 것은 Decoding 쪽이다).
     *
     * 테스트가 가짜 서버로 바꿔 끼운다. 진짜 국세청을 때리면 할당량을 태우고, 네트워크가 끊길 때
     * 우리 코드와 무관하게 빨개지며, 실제 사업자등록번호를 fixture에 넣게 된다.
     */
    URI endpoint(){
        return URI.create(ENDPOINT_BASE+"?serviceKey="
            +URLEncoder.encode(serviceKey,StandardCharsets.UTF_8)+"&returnType=JSON");
    }

    /**
     * 국세청에 닿지 못했을 때.
     *
     * 503이다. 사용자가 뭘 잘못한 것이 아니고, 잠시 후 다시 하면 된다는 뜻이 코드에 담겨야 한다.
     */
    private static AppException unavailable(){
        return new AppException("BUSINESS_VERIFICATION_UNAVAILABLE",
            "사업자 정보를 확인하지 못했습니다. 잠시 후 다시 시도해 주세요.",HttpStatus.SERVICE_UNAVAILABLE);
    }
}
