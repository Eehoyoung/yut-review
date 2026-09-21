package com.yutreview;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * 관리자 전용 AI API.
 *
 * 고객 API(`/api/public/**`)에는 AI 진입점이 하나도 없다. 손님이 QR을 찍고 윷을 던지는 흐름은 AI
 * 공급자 상태와 무관해야 하고, 그 보장은 호출 지점을 여기로만 두는 것으로 얻는다.
 *
 * 모든 엔드포인트가 같은 검사를 지난다. JWT 인증(SecurityConfig) → 매장 멤버십(StoreAccessService)
 * → 요금제 권한 → 월 한도. 다른 매장의 storeId를 넣으면 멤버십 검사에서 막힌다.
 */
@RestController
@RequestMapping("/api/admin/stores/{storeId}/ai")
class AiController {
    private final AiService ai;
    private final StoreAccessService access;
    private final StoreRepository stores;

    AiController(AiService ai, StoreAccessService access, StoreRepository stores) {
        this.ai = ai;
        this.access = access;
        this.stores = stores;
    }

    record EventCopyBody(@Size(max = 40) String tone, @Size(max = 300) String additionalRequest) {
    }

    record ChatBody(@NotBlank @Size(max = AiService.MAX_CHAT_MESSAGE_CHARS) String message,
                    List<@Valid ChatTurnBody> history) {
    }

    record ChatTurnBody(@NotNull @Size(max = 20) String role,
                        @NotBlank @Size(max = AiService.MAX_CHAT_MESSAGE_CHARS) String content) {
    }

    @GetMapping("/status")
    ApiResponse<?> status(@PathVariable Long storeId, Authentication auth) {
        return ApiResponse.ok(ai.status(store(storeId, auth)));
    }

    @PostMapping("/event-copy")
    ApiResponse<?> eventCopy(@PathVariable Long storeId, @Valid @RequestBody(required = false) EventCopyBody body,
                             Authentication auth) {
        Store store = store(storeId, auth);
        AiService.EventCopyRequest request = body == null
                ? new AiService.EventCopyRequest(null, null)
                : new AiService.EventCopyRequest(body.tone(), body.additionalRequest());
        return ApiResponse.ok(ai.eventCopy(store, request));
    }

    @PostMapping("/report")
    ApiResponse<?> report(@PathVariable Long storeId,
                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                          Authentication auth) {
        return ApiResponse.ok(ai.report(store(storeId, auth), from, to));
    }

    @GetMapping("/report/latest")
    ApiResponse<?> latestReport(@PathVariable Long storeId, Authentication auth) {
        return ApiResponse.ok(ai.latestReport(store(storeId, auth), AiFeature.AI_REPORT));
    }

    @PostMapping("/improvement")
    ApiResponse<?> improvement(@PathVariable Long storeId,
                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                               Authentication auth) {
        return ApiResponse.ok(ai.improvement(store(storeId, auth), from, to));
    }

    @GetMapping("/improvement/latest")
    ApiResponse<?> latestImprovement(@PathVariable Long storeId, Authentication auth) {
        return ApiResponse.ok(ai.latestReport(store(storeId, auth), AiFeature.AI_IMPROVEMENT));
    }

    @PostMapping("/chat")
    ApiResponse<?> chat(@PathVariable Long storeId, @Valid @RequestBody ChatBody body, Authentication auth) {
        Store store = store(storeId, auth);
        List<AiService.ChatTurn> history = body.history() == null ? List.of()
                : body.history().stream().map(t -> new AiService.ChatTurn(t.role(), t.content())).toList();
        return ApiResponse.ok(ai.chat(store, body.message(), history));
    }

    /** 매장 조회 전에 멤버십을 먼저 본다. 남의 매장 id를 넣어도 존재 여부조차 알려주지 않는다. */
    private Store store(Long storeId, Authentication auth) {
        access.member((Long) auth.getPrincipal(), storeId);
        Store store = stores.findById(storeId)
                .orElseThrow(() -> new AppException("STORE_NOT_FOUND", "매장을 찾을 수 없습니다."));
        // 운영이 중지된 매장은 고객 경로에서도 막힌다. 관리자 AI만 계속 돌아가면 정지가 정지가 아니다.
        if (store.status != StoreStatus.ACTIVE)
            throw new AppException("STORE_INACTIVE", "운영 중인 매장이 아닙니다.");
        return store;
    }
}

/** 요금제 조회·변경. 결제 연동 전이라 변경은 관리자 조작으로만 일어난다. */
@RestController
@RequestMapping("/api/admin/stores/{storeId}/subscription")
class SubscriptionController {
    private final SubscriptionService subscriptions;
    private final PlanEntitlementService entitlements;
    private final StoreAccessService access;
    private final java.time.Clock clock;

    SubscriptionController(SubscriptionService subscriptions, PlanEntitlementService entitlements,
                           StoreAccessService access, java.time.Clock clock) {
        this.subscriptions = subscriptions;
        this.entitlements = entitlements;
        this.access = access;
        this.clock = clock;
    }

    @GetMapping
    ApiResponse<?> current(@PathVariable Long storeId, Authentication auth) {
        access.member((Long) auth.getPrincipal(), storeId);
        return ApiResponse.ok(view(storeId, subscriptions.planOf(storeId)));
    }

    /**
     * 이 경로는 닫혀 있다. 요금제 변경은 `PUT /api/system/stores/{storeId}/plan` 하나뿐이다.
     *
     * 예전에는 여기서도 바꿀 수 있었고, 운영자 콘솔 토큰인지까지 확인했다. 그런데 그 확인은
     * "콘솔 문으로 들어온 토큰인가"만 볼 뿐이라 콘솔이 실제로 요구하는 것들(콘솔 권한 등급,
     * 재인증, 살아 있는 세션, 계정 중지·IP 제한, 임시 비밀번호 변경, 그리고 감사 기록)을 전부
     * 건너뛴다. 조회 권한만 있는 계정이나 방금 세션이 끊긴 토큰으로도 등급이 바뀌었다.
     *
     * 같은 일을 하는 문이 둘이면 약한 쪽이 곧 그 기능의 보안 수준이 된다. 그래서 하나로 줄인다.
     */
    @PutMapping
    ApiResponse<?> change() {
        throw new AppException("OPERATOR_CONSOLE_REQUIRED",
                "요금제 변경은 운영자 콘솔에서만 할 수 있습니다.", org.springframework.http.HttpStatus.FORBIDDEN);
    }

    /** 요금제 안내에 쓰는 정적 목록. 화면이 가격과 포함 기능을 서버와 같은 값으로 보게 한다. */
    @GetMapping("/plans")
    ApiResponse<?> plans(@PathVariable Long storeId, Authentication auth) {
        access.member((Long) auth.getPrincipal(), storeId);
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (Plan plan : Plan.values()) {
            Map<String, Object> item = new java.util.LinkedHashMap<>();
            item.put("plan", plan.name());
            item.put("monthlyPriceKrw", plan.monthlyPriceKrw);
            item.put("analyticsRetentionDays", plan.analyticsRetentionDays);
            item.put("entitlements", entitlements.entitlements(plan).stream().map(Enum::name).sorted().toList());
            item.put("aiFeatures", entitlements.aiFeatures(plan).stream().map(Enum::name).sorted().toList());
            item.put("automaticWeeklyReport", entitlements.automaticWeeklyReport(plan));
            Map<String, Integer> quotas = new java.util.LinkedHashMap<>();
            for (AiFeature feature : AiFeature.values()) {
                int limit = entitlements.monthlyQuota(plan, feature);
                if (limit > 0) quotas.put(feature.name(), limit);
            }
            item.put("monthlyAiQuota", quotas);
            out.add(item);
        }
        return ApiResponse.ok(out);
    }

    private Map<String, Object> view(Long storeId, Plan plan) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("plan", plan.name());
        out.put("monthlyPriceKrw", plan.monthlyPriceKrw);
        out.put("entitlements", entitlements.entitlements(plan).stream().map(Enum::name).sorted().toList());
        out.put("aiFeatures", entitlements.aiFeatures(plan).stream().map(Enum::name).sorted().toList());
        out.put("analyticsRetentionDays", plan.analyticsRetentionDays);
        entitlements.analyticsFloor(plan, LocalDate.now(clock))
                .ifPresent(floor -> out.put("analyticsFrom", floor.toString()));
        subscriptions.find(storeId).ifPresent(s -> {
            out.put("status", s.status.name());
            out.put("startedAt", s.startedAt);
            out.put("note", s.note == null ? "" : s.note);
        });
        return out;
    }
}
