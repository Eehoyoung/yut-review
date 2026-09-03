package com.yutreview;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AI 매니저가 부를 수 있는 도구.
 *
 * 전부 읽기 전용 집계다. 쓰기 도구도, SQL을 받는 도구도 없다. storeId는 서버가 고정해 넘기므로
 * 모델이 인자로 다른 매장을 지목할 방법이 없다. 도구가 늘어날 때도 이 두 성질은 유지한다.
 */
@Service
class AiAnalyticsToolRegistry {
    private final AiContextService context;

    AiAnalyticsToolRegistry(AiContextService context) {
        this.context = context;
    }

    /** 모델에 노출하는 도구 목록. 인자는 기간뿐이고 매장은 받지 않는다. */
    List<LlmTool> tools() {
        Map<String, Object> period = Map.of(
                "type", "object",
                "properties", Map.of(
                        "from", Map.of("type", "string", "description", "조회 시작일 yyyy-MM-dd. 생략하면 최근 30일."),
                        "to", Map.of("type", "string", "description", "조회 종료일 yyyy-MM-dd. 생략하면 오늘.")),
                "additionalProperties", false);
        return List.of(
                new LlmTool("get_period_summary", "기간의 참여 수, 쿠폰 발급/사용 수, 사용률을 반환한다.", period),
                new LlmTool("compare_periods", "요청 기간과 그 직전 같은 길이 기간을 비교한다.", period),
                new LlmTool("get_hourly_distribution", "시간대별 참여 수를 반환한다.", period),
                new LlmTool("get_weekday_distribution", "요일별 참여 수를 반환한다.", period),
                new LlmTool("get_prize_performance", "상품별 쿠폰 발급/사용 수와 사용률을 반환한다.", period),
                new LlmTool("get_result_distribution", "도개걸윷모 결과별과 등급별 참여 수를 반환한다.", period),
                new LlmTool("get_repeat_metrics", "익명 기준 참여자 수와 재참여율을 반환한다.", period));
    }

    /** 도구 실행. 알 수 없는 이름은 예외가 아니라 모델이 읽을 수 있는 오류로 돌려준다. */
    Map<String, Object> invoke(Store store, Plan plan, String name, JsonNode arguments) {
        AiContextService.Window w = context.window(plan, date(arguments, "from"), date(arguments, "to"));
        return switch (name) {
            case "get_period_summary" -> context.periodSummary(store.id, w);
            case "compare_periods" -> context.comparePeriods(store.id, w, context.previousWindow(plan, w));
            case "get_hourly_distribution" -> context.hourlyDistribution(store.id, w);
            case "get_weekday_distribution" -> context.weekdayDistribution(store.id, w);
            case "get_prize_performance" -> context.prizePerformance(store.id, w);
            case "get_result_distribution" -> context.resultDistribution(store.id, w);
            case "get_repeat_metrics" -> context.repeatMetrics(store.id, w);
            default -> Map.of("error", "알 수 없는 도구입니다: " + name);
        };
    }

    private static LocalDate date(JsonNode arguments, String field) {
        if (arguments == null) return null;
        String raw = arguments.path(field).asText("");
        if (raw.isBlank()) return null;
        try {
            return LocalDate.parse(raw);
        } catch (RuntimeException ignored) {
            // 모델이 형식을 틀리면 기본 구간으로 돌린다. 여기서 예외를 던지면 대화가 끊긴다.
            return null;
        }
    }
}

/**
 * AI 기능의 진입점.
 *
 * 모든 호출이 같은 순서를 지난다. 권한(요금제) → 한도 차감 → 모델 호출 → 사용 기록. 한도를 먼저
 * 깎고 실패하면 되돌리는 이유는 공짜 호출을 만들지 않기 위해서다.
 *
 * 이 서비스는 고객 흐름(QR·게임·쿠폰)에서 호출되지 않는다. AI 공급자가 죽어도 손님이 윷을 던지는
 * 데는 영향이 없어야 하고, 그 보장은 호출 지점을 관리자 API로만 두는 것으로 얻는다.
 */
@Service
class AiService {
    private static final Logger log = LoggerFactory.getLogger(AiService.class);
    /** 채팅 한 번에 허용하는 도구 호출 왕복. 모델이 도구를 계속 부르며 도는 것을 막는다. */
    static final int MAX_TOOL_CALLS = 4;
    static final int MAX_CHAT_MESSAGE_CHARS = 800;
    static final int MAX_CHAT_HISTORY_TURNS = 6;
    static final int MAX_CHAT_OUTPUT_TOKENS = 800;

    private final LlmProvider provider;
    private final AiPromptService prompts;
    private final AiContextService context;
    private final AiQuotaService quota;
    private final AiUsageService usage;
    private final AiAnalyticsToolRegistry toolRegistry;
    private final AiReportRepository reports;
    private final SubscriptionService subscriptions;
    private final PlanEntitlementService entitlements;
    private final ObjectMapper json;
    private final Clock clock;
    private final String fastModel;
    private final String analysisModel;
    private final String chatModel;

    AiService(LlmProvider provider, AiPromptService prompts, AiContextService context, AiQuotaService quota,
              AiUsageService usage, AiAnalyticsToolRegistry toolRegistry, AiReportRepository reports,
              SubscriptionService subscriptions, PlanEntitlementService entitlements, ObjectMapper json, Clock clock,
              @Value("${app.ai.model.fast:gpt-4.1-mini}") String fastModel,
              @Value("${app.ai.model.analysis:gpt-4.1}") String analysisModel,
              @Value("${app.ai.model.chat:gpt-4.1-mini}") String chatModel) {
        this.provider = provider;
        this.prompts = prompts;
        this.context = context;
        this.quota = quota;
        this.usage = usage;
        this.toolRegistry = toolRegistry;
        this.reports = reports;
        this.subscriptions = subscriptions;
        this.entitlements = entitlements;
        this.json = json;
        this.clock = clock;
        this.fastModel = fastModel;
        this.analysisModel = analysisModel;
        this.chatModel = chatModel;
    }

    record EventCopyRequest(String tone, String additionalRequest) {
    }

    record ChatTurn(String role, String content) {
    }

    /** 이벤트 안내 문구. 실제 상품·확률만 근거로 쓴다. */
    Map<String, Object> eventCopy(Store store, EventCopyRequest request) {
        Plan plan = requirePlan(store, AiFeature.AI_EVENT_COPY);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("instruction", "다음 이벤트 정보로 고객 안내 문구를 작성해라.");
        payload.put("storeName", store.name);
        payload.put("tone", request == null || request.tone() == null || request.tone().isBlank()
                ? "담백하고 신뢰감 있는" : request.tone().trim());
        payload.put("prizes", context.prizeConfig(store.id));
        payload.put("additionalRequest", request == null || request.additionalRequest() == null
                ? "" : request.additionalRequest().trim());
        return structured(store, plan, AiFeature.AI_EVENT_COPY, fastModel, payload, 900);
    }

    /**
     * 기간 리포트. 생성 결과를 저장해 다시 열어볼 때 재호출하지 않는다.
     *
     * 메서드 전체를 트랜잭션으로 묶지 않는다. 묶으면 집계 쿼리가 잡은 DB 커넥션을 최대 45초의 모델
     * 응답을 기다리는 내내 붙들고 있게 되고, 사장 몇 명이 동시에 누르면 커넥션 풀이 마른다. 그러면
     * 손님의 게임·쿠폰 요청이 커넥션을 못 받아 실패한다. AI 지연이 고객 흐름으로 번지는 정확한 경로다.
     * 그래서 읽기, 모델 호출, 저장을 각각 짧게 끊는다.
     */
    Map<String, Object> report(Store store, LocalDate from, LocalDate to) {
        Plan plan = requirePlan(store, AiFeature.AI_REPORT);
        AiContextService.Window w = context.window(plan, from, to);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("instruction", "다음 기간의 윷리뷰 이벤트 운영 데이터를 분석하고 핵심 변화와 개선점을 제시해라.");
        payload.put("analyticsContext", context.analyticsBundle(store, plan, w));
        Map<String, Object> result = structured(store, plan, AiFeature.AI_REPORT, analysisModel, payload, 1600);
        save(store, AiFeature.AI_REPORT, prompts.version(AiFeature.AI_REPORT), w, result);
        return withWindow(result, w);
    }

    /** 개선 실험 제안. PRO 전용. 트랜잭션 경계는 report와 같은 이유로 짧게 끊는다. */
    Map<String, Object> improvement(Store store, LocalDate from, LocalDate to) {
        Plan plan = requirePlan(store, AiFeature.AI_IMPROVEMENT);
        AiContextService.Window w = context.window(plan, from, to);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("instruction", "현재 매장 데이터에서 개선 가치가 큰 영역을 찾아 최대 3개의 실험을 설계해라.");
        payload.put("analyticsContext", context.analyticsBundle(store, plan, w));
        payload.put("availablePrizes", context.prizeConfig(store.id));
        Map<String, Object> result = structured(store, plan, AiFeature.AI_IMPROVEMENT, analysisModel, payload, 1400);
        save(store, AiFeature.AI_IMPROVEMENT, prompts.version(AiFeature.AI_IMPROVEMENT), w, result);
        return withWindow(result, w);
    }

    /**
     * AI 매니저 대화. 모델은 도구로만 숫자를 확인하고, 도구는 이 매장의 집계만 돌려준다.
     * 왕복 상한에 닿으면 도구 없이 한 번 더 물어 답을 받는다.
     */
    Map<String, Object> chat(Store store, String message, List<ChatTurn> history) {
        Plan plan = requirePlan(store, AiFeature.AI_CHAT);
        String question = message == null ? "" : message.trim();
        if (question.isEmpty()) throw new AppException("INVALID_REQUEST", "질문을 입력해 주세요.");
        if (question.length() > MAX_CHAT_MESSAGE_CHARS)
            throw new AppException("INVALID_REQUEST", "질문은 " + MAX_CHAT_MESSAGE_CHARS + "자 이내로 입력해 주세요.");

        List<LlmMessage> messages = new ArrayList<>();
        if (history != null) {
            List<ChatTurn> tail = history.size() > MAX_CHAT_HISTORY_TURNS
                    ? history.subList(history.size() - MAX_CHAT_HISTORY_TURNS, history.size())
                    : history;
            for (ChatTurn turn : tail) {
                if (turn == null || turn.content() == null || turn.content().isBlank()) continue;
                String content = turn.content().length() > MAX_CHAT_MESSAGE_CHARS
                        ? turn.content().substring(0, MAX_CHAT_MESSAGE_CHARS) : turn.content();
                messages.add("assistant".equals(turn.role()) ? LlmMessage.assistant(content) : LlmMessage.user(content));
            }
        }
        messages.add(LlmMessage.user(question));

        String chargedMonth = quota.consume(store, plan, AiFeature.AI_CHAT);
        String version = prompts.version(AiFeature.AI_CHAT);
        LlmUsage total = LlmUsage.NONE;
        List<String> toolsUsed = new ArrayList<>();
        try {
            LlmRequest request = new LlmRequest(chatModel, prompts.systemPrompt(AiFeature.AI_CHAT), messages,
                    null, null, MAX_CHAT_OUTPUT_TOKENS, toolRegistry.tools());
            LlmResponse response = provider.complete(request);
            total = add(total, response.usage());

            // 왕복을 버리지 않고 쌓는다. 마지막 것만 다시 보내면 앞 라운드에서 확인한 숫자가 사라져,
            // 모델이 근거 없이 답하거나 같은 것을 계속 되묻는다.
            List<LlmToolExchange> exchanges = new ArrayList<>();
            for (int round = 0; round < MAX_TOOL_CALLS && response.wantsTools(); round++) {
                for (LlmToolCall call : response.toolCalls()) {
                    toolsUsed.add(call.name());
                    exchanges.add(new LlmToolExchange(call, runTool(store, plan, call)));
                }
                response = provider.continueWithToolResults(request, exchanges);
                total = add(total, response.usage());
            }
            if (response.wantsTools()) {
                // 상한에 닿았다. 도구를 한 번 더 돌리지 않는다(계약상 요청당 4회가 상한이다).
                // 대신 지금까지 모은 결과를 그대로 들려 보내고 더 부르지만 못하게 닫는다. 근거를
                // 비운 채 답을 요구하면 모델이 숫자를 지어낸다. 정의를 지우지 않는 이유는, 지난
                // function_call만 남고 도구 정의가 없으면 공급자가 요청을 거부할 수 있어서다.
                LlmRequest closing = new LlmRequest(chatModel, prompts.systemPrompt(AiFeature.AI_CHAT), messages,
                        null, null, MAX_CHAT_OUTPUT_TOKENS, toolRegistry.tools(), true);
                response = provider.continueWithToolResults(closing, exchanges);
                total = add(total, response.usage());
            }
            usage.record(store, AiFeature.AI_CHAT, response.model(), version, total, true, null);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("answer", response.text());
            out.put("toolsUsed", toolsUsed);
            return out;
        } catch (AppException e) {
            throw e;
        } catch (RuntimeException e) {
            fail(store, AiFeature.AI_CHAT, chatModel, version, chargedMonth, total, e);
            throw aiUnavailable(e);
        }
    }

    /** 이번 달 사용 현황과 요금제. 화면이 무엇을 열어 줄지 이 응답만 보고 판단한다. */
    Map<String, Object> status(Store store) {
        Plan plan = subscriptions.planOf(store.id);
        List<Map<String, Object>> features = new ArrayList<>();
        for (AiQuotaService.Usage u : quota.usage(store.id, plan)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("feature", u.feature().name());
            item.put("allowed", entitlements.has(plan, u.feature()));
            item.put("used", u.used());
            item.put("limitPerMonth", u.limitPerMonth());
            item.put("remaining", u.remaining());
            features.add(item);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("plan", plan.name());
        out.put("month", quota.currentMonth());
        out.put("provider", provider.name());
        out.put("features", features);
        out.put("recentUsage", usage.recent(store.id));
        out.put("monthlyTokens", usage.monthlyCost(store.id, quota.currentMonth()));
        return out;
    }

    Map<String, Object> latestReport(Store store, AiFeature feature) {
        return reports.findFirstByStoreIdAndFeatureOrderByCreatedAtDesc(store.id, feature)
                .map(r -> {
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("promptVersion", r.promptVersion);
                    out.put("from", r.periodFrom.toString());
                    out.put("to", r.periodTo.toString());
                    out.put("createdAt", r.createdAt);
                    out.put("content", readStored(r.contentJson));
                    return out;
                })
                .orElseThrow(() -> new AppException("AI_REPORT_NOT_FOUND", "저장된 리포트가 없습니다.",
                        org.springframework.http.HttpStatus.NOT_FOUND));
    }

    private Plan requirePlan(Store store, AiFeature feature) {
        Plan plan = subscriptions.planOf(store.id);
        entitlements.require(plan, feature);
        return plan;
    }

    /** 스키마가 정해진 기능의 공통 경로. */
    private Map<String, Object> structured(Store store, Plan plan, AiFeature feature, String model,
                                           Map<String, Object> payload, int maxOutputTokens) {
        String chargedMonth = quota.consume(store, plan, feature);
        String version = prompts.version(feature);
        LlmUsage used = LlmUsage.NONE;
        try {
            LlmRequest request = new LlmRequest(model, prompts.systemPrompt(feature),
                    List.of(LlmMessage.user(write(payload))),
                    prompts.schema(feature), prompts.schemaName(feature), maxOutputTokens, List.of());
            LlmResponse response = provider.complete(request);
            used = response.usage();
            Map<String, Object> parsed = read(response.text());
            usage.record(store, feature, response.model(), version, used, true, null);
            return parsed;
        } catch (AppException e) {
            throw e;
        } catch (RuntimeException e) {
            fail(store, feature, model, version, chargedMonth, used, e);
            throw aiUnavailable(e);
        }
    }

    /**
     * 공급자에 닿기 전에 끝난 실패만 한도를 돌려준다.
     *
     * 응답을 받은 뒤의 실패(스키마 불일치, 출력 잘림)는 이미 과금된 호출이다. 그걸 돌려주면 그 매장은
     * 실패가 반복되는 동안 사용량이 오르지 않는 채로 계속 호출할 수 있고, 비용만 나간다.
     */
    private static final java.util.Set<String> REFUNDABLE =
            java.util.Set.of("AI_PROVIDER_UNAVAILABLE", "AI_NOT_CONFIGURED", "AI_REQUEST_INVALID");
    // AI_TIMEOUT과 AI_RESPONSE_INVALID는 여기 없다. 요청이 이미 나갔고 모델이 생성까지 마쳤을 수
    // 있어서, 되돌려 주면 느린 응답이 반복되는 동안 비용만 나가고 사용량은 오르지 않는다.

    private void fail(Store store, AiFeature feature, String model, String version, String chargedMonth,
                      LlmUsage used, RuntimeException e) {
        String code = e instanceof LlmException llm ? llm.code : "AI_FAILED";
        if (REFUNDABLE.contains(code)) quota.refund(store.id, feature, chargedMonth);
        usage.record(store, feature, model, version, used, false, code);
        // 예외 메시지만 남긴다. 프롬프트 본문은 기록하지 않는다.
        log.warn("AI 호출 실패 storeId={} feature={} code={}", store.id, feature, code);
    }

    private AppException aiUnavailable(RuntimeException e) {
        String code = e instanceof LlmException llm ? llm.code : "AI_FAILED";
        return new AppException(code, "AI 기능을 지금 사용할 수 없습니다. 잠시 후 다시 시도해 주세요.",
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE);
    }

    private String runTool(Store store, Plan plan, LlmToolCall call) {
        try {
            JsonNode arguments = call.argumentsJson() == null || call.argumentsJson().isBlank()
                    ? json.createObjectNode() : json.readTree(call.argumentsJson());
            return write(toolRegistry.invoke(store, plan, call.name(), arguments));
        } catch (AppException known) {
            // 이유를 알려 준다. "실패했습니다"만 돌려주면 모델이 같은 기간을 다시 물으며 왕복만
            // 소진하고, 사장은 그게 요금제 한계라는 걸 끝내 듣지 못한다.
            return "{\"error\":" + quote(known.getMessage()) + "}";
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
            return "{\"error\":\"도구 실행에 실패했습니다.\"}";
        }
    }

    private String quote(String value) {
        try {
            return json.writeValueAsString(value == null ? "" : value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return "\"\"";
        }
    }

    private void save(Store store, AiFeature feature, String version, AiContextService.Window w, Map<String, Object> content) {
        AiReport r = new AiReport();
        r.store = store;
        r.feature = feature;
        r.promptVersion = version;
        r.periodFrom = w.from();
        r.periodTo = w.to();
        r.contentJson = write(content);
        r.createdAt = clock.instant();
        reports.save(r);
    }

    private Map<String, Object> withWindow(Map<String, Object> result, AiContextService.Window w) {
        Map<String, Object> out = new LinkedHashMap<>(result);
        out.put("window", Map.of("from", w.from().toString(), "to", w.to().toString(),
                "clampedByPlanRetention", w.clampedByPlan()));
        return out;
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new LlmException("AI_REQUEST_INVALID", "요청을 만들 수 없습니다.", e);
        }
    }

    /** 저장된 리포트는 우리가 쓴 값이다. 그래도 깨져 있으면 스택트레이스 대신 이유를 돌려준다. */
    private Map<String, Object> readStored(String text) {
        try {
            return read(text);
        } catch (LlmException broken) {
            throw new AppException("AI_REPORT_UNREADABLE", "저장된 리포트를 읽을 수 없습니다.",
                    org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> read(String text) {
        try {
            return json.readValue(text, Map.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new LlmException("AI_RESPONSE_INVALID", "AI 응답 형식이 올바르지 않습니다.", e);
        }
    }

    private static LlmUsage add(LlmUsage a, LlmUsage b) {
        if (b == null) return a;
        return new LlmUsage(a.inputTokens() + b.inputTokens(), a.outputTokens() + b.outputTokens());
    }
}
