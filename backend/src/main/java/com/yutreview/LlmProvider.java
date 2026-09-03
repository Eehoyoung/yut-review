package com.yutreview;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** 모델에 보내는 한 번의 요청. 여기 담기는 것은 이미 비식별 집계뿐이어야 한다. */
record LlmRequest(
        String model,
        String systemPrompt,
        List<LlmMessage> messages,
        /** 구조화 응답을 받을 JSON schema. null이면 자유 텍스트. */
        Map<String, Object> jsonSchema,
        String schemaName,
        int maxOutputTokens,
        List<LlmTool> tools) {
}

record LlmMessage(String role, String content) {
    static LlmMessage user(String content) { return new LlmMessage("user", content); }
    static LlmMessage assistant(String content) { return new LlmMessage("assistant", content); }
}

/** 모델이 호출할 수 있는 도구. 읽기 전용 집계 조회만 등록한다. */
record LlmTool(String name, String description, Map<String, Object> parameters) {
}

/** 모델이 도구를 부른 기록. 서버가 실행하고 결과를 다시 넣어 준다. */
record LlmToolCall(String callId, String name, String argumentsJson) {
}

/** 도구 한 번의 왕복(호출 + 결과). 대화가 이어지는 동안 계속 쌓아서 매 요청에 함께 보낸다. */
record LlmToolExchange(LlmToolCall call, String resultJson) {
}

record LlmUsage(int inputTokens, int outputTokens) {
    static final LlmUsage NONE = new LlmUsage(0, 0);
}

record LlmResponse(String text, List<LlmToolCall> toolCalls, LlmUsage usage, String model) {
    boolean wantsTools() { return toolCalls != null && !toolCalls.isEmpty(); }
}

/**
 * 모델 호출 경계. 구현을 갈아끼울 수 있어야 하는 이유는 두 가지다. CI에서 실제 API를 때리지 않아야
 * 하고, 나중에 공급자를 바꿀 때 프롬프트와 도구 정의를 다시 쓰지 않아야 한다.
 */
interface LlmProvider {
    LlmResponse complete(LlmRequest request);

    /**
     * 지금까지의 도구 왕복을 전부 붙여 다시 묻는다.
     *
     * 마지막 왕복만 보내면 앞 라운드에서 확인한 숫자가 사라져, 모델이 같은 것을 다시 묻거나 결국
     * 근거 없이 답하게 된다. 누적해서 보내는 것이 이 인터페이스의 계약이다.
     */
    LlmResponse continueWithToolResults(LlmRequest request, List<LlmToolExchange> exchanges);

    String name();
}

/** AI 호출이 실패했을 때. 이 예외는 고객 흐름으로 새지 않고 관리자 화면에서만 보인다. */
class LlmException extends RuntimeException {
    final String code;

    LlmException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }
}

/**
 * OpenAI Responses API 구현.
 *
 * 키는 서버에만 두고, 모델 ID는 환경변수로 바꿀 수 있게 둔다. 타임아웃을 짧게 잡는 이유는 관리자가
 * 응답을 기다리다 화면이 멈춘 것처럼 보이는 쪽이 실패 메시지보다 나쁘기 때문이다.
 */
@Service
@ConditionalOnProperty(name = "app.ai.provider", havingValue = "openai")
class OpenAiLlmProvider implements LlmProvider {
    private final RestClient client;
    private final ObjectMapper json;
    private final boolean configured;

    OpenAiLlmProvider(@Value("${app.ai.openai.api-key:}") String apiKey,
                      @Value("${app.ai.openai.base-url:https://api.openai.com/v1}") String baseUrl,
                      @Value("${app.ai.timeout-seconds:45}") long timeoutSeconds,
                      ObjectMapper json) {
        // 키가 없어도 뜨기는 해야 한다. 여기서 던지면 컨테이너가 healthy가 되지 않아, 관리자 전용
        // 기능의 설정 실수 하나로 손님의 QR·게임·쿠폰까지 같이 멈춘다.
        this.configured = apiKey != null && !apiKey.isBlank();
        // 타임아웃을 명시적으로 잡는다. 기본값은 무한 대기라 관리자 화면이 멈춘 것처럼 보인다.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
        this.client = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .defaultHeader("Authorization", "Bearer " + (this.configured ? apiKey : "unset"))
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();
        this.json = json;
    }

    @Override
    public String name() {
        return "openai";
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        return call(body(request, List.of()));
    }

    @Override
    public LlmResponse continueWithToolResults(LlmRequest request, List<LlmToolExchange> exchanges) {
        return call(body(request, exchanges));
    }

    private Map<String, Object> body(LlmRequest request, List<LlmToolExchange> exchanges) {
        List<Map<String, Object>> input = new java.util.ArrayList<>();
        for (LlmMessage m : request.messages())
            input.add(Map.of("role", m.role(), "content", m.content()));
        // 지금까지의 왕복을 순서대로 전부 붙인다. 마지막 것만 보내면 앞의 근거가 사라진다.
        for (LlmToolExchange exchange : exchanges) {
            LlmToolCall call = exchange.call();
            input.add(Map.of("type", "function_call", "call_id", call.callId(),
                    "name", call.name(), "arguments", call.argumentsJson()));
            input.add(Map.of("type", "function_call_output", "call_id", call.callId(),
                    "output", exchange.resultJson()));
        }
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("model", request.model());
        payload.put("instructions", request.systemPrompt());
        payload.put("input", input);
        payload.put("max_output_tokens", request.maxOutputTokens());
        payload.put("store", false);
        if (request.jsonSchema() != null)
            payload.put("text", Map.of("format", Map.of(
                    "type", "json_schema",
                    "name", request.schemaName(),
                    "strict", true,
                    "schema", request.jsonSchema())));
        if (request.tools() != null && !request.tools().isEmpty())
            payload.put("tools", request.tools().stream().map(t -> Map.<String, Object>of(
                    "type", "function",
                    "name", t.name(),
                    "description", t.description(),
                    "parameters", t.parameters(),
                    "strict", false)).toList());
        return payload;
    }

    private LlmResponse call(Map<String, Object> payload) {
        if (!configured)
            throw new LlmException("AI_NOT_CONFIGURED", "AI 사용 설정이 완료되지 않았습니다.", null);
        JsonNode root;
        try {
            root = client.post().uri("/responses").body(payload).retrieve().body(JsonNode.class);
        } catch (RestClientException e) {
            // 실패 메시지에 요청 본문을 넣지 않는다. 집계뿐이라도 로그로 흘리지 않는 편이 낫다.
            throw new LlmException("AI_PROVIDER_UNAVAILABLE", "AI 응답을 받지 못했습니다.", e);
        }
        if (root == null) throw new LlmException("AI_PROVIDER_UNAVAILABLE", "AI 응답이 비어 있습니다.", null);

        StringBuilder text = new StringBuilder();
        List<LlmToolCall> calls = new java.util.ArrayList<>();
        for (JsonNode item : root.path("output")) {
            String type = item.path("type").asText("");
            if ("function_call".equals(type))
                calls.add(new LlmToolCall(item.path("call_id").asText(), item.path("name").asText(),
                        item.path("arguments").asText("{}")));
            else
                for (JsonNode content : item.path("content"))
                    if ("output_text".equals(content.path("type").asText("")))
                        text.append(content.path("text").asText(""));
        }
        JsonNode usage = root.path("usage");
        return new LlmResponse(text.toString(), calls,
                new LlmUsage(usage.path("input_tokens").asInt(0), usage.path("output_tokens").asInt(0)),
                root.path("model").asText(""));
    }
}

/**
 * CI와 로컬 기본값. 실제 API를 부르지 않고 스키마에 맞는 그럴듯한 응답을 만든다.
 *
 * 테스트가 네트워크에 의존하면 실패가 신호가 아니라 잡음이 된다. 그래서 기본 프로바이더는 이쪽이고,
 * OpenAI는 `app.ai.provider=openai`일 때만 뜬다.
 */
@Service
@ConditionalOnProperty(name = "app.ai.provider", havingValue = "fake", matchIfMissing = true)
class FakeLlmProvider implements LlmProvider {
    private final ObjectMapper json;
    /** 테스트가 실패 경로를 확인할 수 있도록 켜고 끈다. */
    volatile boolean failing;
    volatile LlmRequest lastRequest;
    /** 테스트가 도구 왕복 누적을 확인한다. */
    volatile List<LlmToolExchange> lastExchanges = List.of();
    /** 다음 complete() 한 번이 요청할 도구 호출. 도구 경로를 테스트하려고 둔다. */
    volatile List<LlmToolCall> nextToolCalls = List.of();
    /** 응답은 받았지만 형식이 깨진 경우. 이미 과금된 실패를 테스트한다. */
    volatile boolean brokenJson;

    FakeLlmProvider(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public String name() {
        return "fake";
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        lastRequest = request;
        if (failing) throw new LlmException("AI_PROVIDER_UNAVAILABLE", "테스트용 실패", null);
        List<LlmToolCall> calls = nextToolCalls;
        nextToolCalls = List.of();
        if (!calls.isEmpty()) return new LlmResponse("", calls, new LlmUsage(60, 20), request.model());
        return new LlmResponse(sample(request), List.of(), new LlmUsage(120, 80), request.model());
    }

    @Override
    public LlmResponse continueWithToolResults(LlmRequest request, List<LlmToolExchange> exchanges) {
        lastRequest = request;
        lastExchanges = List.copyOf(exchanges);
        if (failing) throw new LlmException("AI_PROVIDER_UNAVAILABLE", "테스트용 실패", null);
        return new LlmResponse(sample(request), List.of(), new LlmUsage(140, 90), request.model());
    }

    /** 스키마가 있으면 그 모양대로, 없으면 짧은 한국어 문장 하나. */
    private String sample(LlmRequest request) {
        if (brokenJson) return "{ 잘린 응답";
        if (request.jsonSchema() == null) return "확인했습니다. 현재 데이터에서는 참여 수가 가장 큰 변화입니다.";
        try {
            return json.writeValueAsString(fill(request.jsonSchema()));
        } catch (Exception e) {
            throw new LlmException("AI_RESPONSE_INVALID", "테스트 응답 생성 실패", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Object fill(Map<String, Object> schema) {
        String type = String.valueOf(schema.getOrDefault("type", "string"));
        switch (type) {
            case "object" -> {
                Map<String, Object> props = (Map<String, Object>) schema.getOrDefault("properties", Map.of());
                Map<String, Object> out = new java.util.LinkedHashMap<>();
                props.forEach((k, v) -> out.put(k, fill((Map<String, Object>) v)));
                return out;
            }
            case "array" -> {
                Map<String, Object> items = (Map<String, Object>) schema.getOrDefault("items", Map.of("type", "string"));
                return List.of(fill(items));
            }
            case "integer", "number" -> {
                return 1;
            }
            case "boolean" -> {
                return true;
            }
            default -> {
                return "테스트 값";
            }
        }
    }
}
