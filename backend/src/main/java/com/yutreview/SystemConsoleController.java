package com.yutreview;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * 운영자 전용 콘솔 API.
 *
 * 매장 콘솔(`/api/admin/**`)과 주소부터 나눠 둔다. 그래야 Nginx나 앞단 프록시에서 이 경로만 통째로
 * 다르게 다룰 수 있고(추가 인증, IP 제한, 차단), 실수로 매장 API에 플랫폼 전체 조회가 섞여 들어가지
 * 않는다.
 *
 * 여기서 나가는 값에는 고객 개인정보가 없다. 운영자가 보는 것은 매장 단위 집계와 계정·요금제
 * 상태까지다. 손님 이름과 전화번호는 그 매장의 관리자만 볼 수 있다.
 */
@RestController
@RequestMapping("/api/system")
class SystemConsoleController {
    record OperatorLogin(@NotBlank @Size(max = 255) String email, @NotBlank @Size(max = 128) String password,
            @Size(max = 10) String code) {
    }

    record TotpConfirm(@NotBlank @Size(max = 2000) String enrollmentToken, @NotBlank @Size(max = 10) String code) {
    }

    record PlanChange(@NotNull Plan plan, @Size(max = 200) String note) {
    }

    record StoreStatusChange(@NotNull StoreStatus status, @Size(max = 200) String note) {
    }

    private final OperatorAuthService auth;
    private final SystemConsoleGuard guard;
    private final SystemConsoleSettings settings;
    private final SystemAuditService audit;
    private final SystemAuditLogRepository auditLogs;
    private final StoreRepository stores;
    private final AdminUserRepository admins;
    private final QrRepository qrs;
    private final PrizeRepository prizes;
    private final GameRepository games;
    private final CouponRepository coupons;
    private final PlatformStatsRepository platform;
    private final SubscriptionService subscriptions;
    private final GameConfigService gameConfig;
    private final JwtService jwt;
    private final Clock clock;

    SystemConsoleController(OperatorAuthService auth, SystemConsoleGuard guard, SystemConsoleSettings settings,
            SystemAuditService audit, SystemAuditLogRepository auditLogs, StoreRepository stores,
            AdminUserRepository admins, QrRepository qrs, PrizeRepository prizes, GameRepository games,
            CouponRepository coupons, PlatformStatsRepository platform, SubscriptionService subscriptions,
            GameConfigService gameConfig, JwtService jwt, Clock clock) {
        this.auth = auth;
        this.guard = guard;
        this.settings = settings;
        this.audit = audit;
        this.auditLogs = auditLogs;
        this.stores = stores;
        this.admins = admins;
        this.qrs = qrs;
        this.prizes = prizes;
        this.games = games;
        this.coupons = coupons;
        this.platform = platform;
        this.subscriptions = subscriptions;
        this.gameConfig = gameConfig;
        this.jwt = jwt;
        this.clock = clock;
    }

    @PostMapping("/auth/login")
    ApiResponse<?> login(@Valid @RequestBody OperatorLogin body, HttpServletRequest req) {
        OperatorAuthService.Outcome outcome = auth.login(body.email(), body.password(), body.code(),
                ClientIps.of(req));
        if (outcome.enrollment() != null) {
            OperatorAuthService.Enrollment e = outcome.enrollment();
            return ApiResponse.ok(Map.of("status", "TOTP_ENROLLMENT_REQUIRED", "enrollmentToken", e.enrollmentToken(),
                    "secret", e.secret(), "otpauthUrl", e.otpauthUrl(), "qrImage", e.qrImage()));
        }
        OperatorAuthService.Session s = outcome.session();
        return ApiResponse.ok(Map.of("status", "AUTHENTICATED", "accessToken", s.accessToken(), "tokenType", "Bearer",
                "expiresInSeconds", s.expiresInSeconds(), "email", s.email(), "name", s.name()));
    }

    @PostMapping("/auth/totp/confirm")
    ApiResponse<?> confirmTotp(@Valid @RequestBody TotpConfirm body, HttpServletRequest req) {
        auth.confirmEnrollment(body.enrollmentToken(), body.code(), ClientIps.of(req));
        return ApiResponse.ok(Map.of("status", "TOTP_ENROLLED"));
    }

    @GetMapping("/me")
    ApiResponse<?> me(Authentication a) {
        AdminUser operator = guard.operator(a);
        return ApiResponse.ok(Map.of("email", operator.email, "name", operator.name,
                "sessionMinutes", jwt.operatorTtlSeconds() / 60, "ipRestricted", settings.restrictsIp()));
    }

    /** 플랫폼 전체 한 화면. 매장 단위 집계만 담는다. */
    @GetMapping("/overview")
    ApiResponse<?> overview(Authentication a) {
        guard.operator(a);
        LocalDate today = LocalDate.now(clock);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("stores", Map.of("total", stores.count(), "active", stores.countByStatus(StoreStatus.ACTIVE),
                "inactive", stores.countByStatus(StoreStatus.INACTIVE)));
        out.put("admins", Map.of("total", admins.count(), "operators", admins.countByRole(AdminRole.SYSTEM_ADMIN)));
        out.put("plays", Map.of("total", games.count(), "today", games.countByPlayedDate(today)));
        out.put("coupons", Map.of("issued", coupons.countByStatus(CouponStatus.ISSUED), "redeemed",
                coupons.countByStatus(CouponStatus.REDEEMED)));
        Map<String, Long> plans = new LinkedHashMap<>();
        for (Plan plan : Plan.values()) plans.put(plan.name(), 0L);
        // 구독 행이 없는 매장은 BASIC으로 본다(SubscriptionService.planOf와 같은 규칙).
        long counted = 0;
        for (Object[] row : platform.planCounts()) {
            long count = ((Number) row[1]).longValue();
            plans.merge(((Plan) row[0]).name(), count, Long::sum);
            counted += count;
        }
        plans.merge(Plan.BASIC.name(), Math.max(0, stores.count() - counted), Long::sum);
        out.put("plans", plans);
        long aiTotal = 0, aiFailed = 0;
        for (Object[] row : platform.aiCallCounts(today.withDayOfMonth(1).atStartOfDay(zone()).toInstant())) {
            long count = ((Number) row[1]).longValue();
            aiTotal += count;
            if (!((Boolean) row[0])) aiFailed += count;
        }
        out.put("ai", Map.of("monthCalls", aiTotal, "monthFailures", aiFailed));
        out.put("security", Map.of("ipRestricted", settings.restrictsIp(), "sessionMinutes",
                jwt.operatorTtlSeconds() / 60));
        return ApiResponse.ok(out);
    }

    @GetMapping("/stores")
    ApiResponse<?> stores(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String query, Authentication a) {
        guard.operator(a);
        PageRequest request = pageRequest(page, size);
        String keyword = query == null ? "" : query.trim();
        Page<Store> found = keyword.isEmpty() ? stores.findAllByOrderByIdDesc(request)
                : stores.findByNameContainingIgnoreCaseOrderByIdDesc(keyword, request);
        List<Long> ids = found.getContent().stream().map(s -> s.id).toList();
        // 목록 한 줄마다 다시 세지 않는다. 매장이 늘어나도 질의 수는 그대로다.
        Map<Long, Long> plays = new HashMap<>();
        Map<Long, String> owners = new HashMap<>();
        Map<Long, Map<String, Long>> couponCounts = new HashMap<>();
        if (!ids.isEmpty()) {
            for (Object[] row : platform.playCounts(ids)) plays.put((Long) row[0], ((Number) row[1]).longValue());
            for (Object[] row : platform.owners(ids)) owners.putIfAbsent((Long) row[0], (String) row[1]);
            for (Object[] row : platform.couponCounts(ids))
                couponCounts.computeIfAbsent((Long) row[0], k -> new HashMap<>())
                        .put(((CouponStatus) row[1]).name(), ((Number) row[2]).longValue());
        }
        List<Map<String, Object>> content = new ArrayList<>();
        for (Store s : found.getContent()) {
            Map<String, Long> byStatus = new HashMap<>(couponCounts.getOrDefault(s.id, Map.of()));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", s.id);
            row.put("name", s.name);
            row.put("businessNumber", s.businessNumber == null ? "" : s.businessNumber);
            row.put("status", s.status.name());
            row.put("plan", subscriptions.planOf(s.id).name());
            row.put("ownerEmail", owners.getOrDefault(s.id, ""));
            row.put("plays", plays.getOrDefault(s.id, 0L));
            row.put("couponsIssued", byStatus.getOrDefault(CouponStatus.ISSUED.name(), 0L));
            row.put("couponsRedeemed", byStatus.getOrDefault(CouponStatus.REDEEMED.name(), 0L));
            row.put("createdAt", s.createdAt);
            content.add(row);
        }
        return ApiResponse.ok(Map.of("content", content, "page", found.getNumber(), "size", found.getSize(),
                "totalElements", found.getTotalElements(), "totalPages", found.getTotalPages()));
    }

    @GetMapping("/stores/{id}")
    ApiResponse<?> store(@PathVariable Long id, Authentication a) {
        guard.operator(a);
        Store s = store(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", s.id);
        out.put("name", s.name);
        out.put("businessNumber", s.businessNumber == null ? "" : s.businessNumber);
        out.put("phone", s.phone);
        out.put("address", s.address == null ? "" : s.address);
        out.put("naverPlaceUrl", s.naverPlaceUrl == null ? "" : s.naverPlaceUrl);
        out.put("status", s.status.name());
        out.put("createdAt", s.createdAt);
        out.put("updatedAt", s.updatedAt);
        out.put("ownerEmail", platform.owners(List.of(s.id)).stream().findFirst().map(row -> (String) row[1]).orElse(""));
        out.put("qrToken", qrs.findFirstByStoreIdAndStatus(s.id, QrStatus.ACTIVE).map(q -> q.publicToken).orElse(""));
        out.put("prizeCount", prizes.findByStoreIdOrderByRank(s.id).stream().filter(p -> p.active).count());
        out.put("rankCount", GameConfigService.rankCount(gameConfig.load(s.id)));
        out.put("plays", games.countByStoreId(s.id));
        out.put("couponsIssued", coupons.countByStoreIdAndStatus(s.id, CouponStatus.ISSUED));
        out.put("couponsRedeemed", coupons.countByStoreIdAndStatus(s.id, CouponStatus.REDEEMED));
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("plan", subscriptions.planOf(s.id).name());
        subscriptions.find(s.id).ifPresent(sub -> {
            plan.put("status", sub.status.name());
            plan.put("startedAt", sub.startedAt);
            plan.put("note", sub.note == null ? "" : sub.note);
        });
        out.put("subscription", plan);
        return ApiResponse.ok(out);
    }

    /**
     * 요금제 변경. 결제 연동 전까지 등급을 올리고 내리는 유일한 정식 경로이며, 누가 언제 무엇을
     * 바꿨는지 기록이 남는다.
     */
    @PutMapping("/stores/{id}/plan")
    ApiResponse<?> changePlan(@PathVariable Long id, @Valid @RequestBody PlanChange body, Authentication a,
            HttpServletRequest req) {
        AdminUser operator = guard.operator(a);
        Store s = store(id);
        Plan before = subscriptions.planOf(id);
        StoreSubscription saved = subscriptions.changePlan(s, body.plan(), body.note());
        audit.record(operator.id, operator.email, SystemAuditService.PLAN_CHANGED, "STORE", s.id,
                before.name() + " -> " + saved.plan.name() + (body.note() == null || body.note().isBlank() ? ""
                        : " (" + body.note().trim() + ")"),
                ClientIps.of(req), true);
        return ApiResponse.ok(Map.of("plan", saved.plan.name(), "status", saved.status.name()));
    }

    /**
     * 매장 중지/재개.
     *
     * 중지한 매장은 QR을 찍어도 손님이 들어오지 못한다({@link StoreAccessService#activeQr}). 이미 받은
     * 쿠폰은 그대로 살아 있다. 영업을 접은 매장의 이벤트를 멈추는 것이지, 손님에게 준 것을 회수하는
     * 기능이 아니다.
     */
    @PutMapping("/stores/{id}/status")
    ApiResponse<?> changeStatus(@PathVariable Long id, @Valid @RequestBody StoreStatusChange body, Authentication a,
            HttpServletRequest req) {
        AdminUser operator = guard.operator(a);
        Store s = store(id);
        StoreStatus before = s.status;
        s.status = body.status();
        s.updatedAt = clock.instant();
        stores.save(s);
        audit.record(operator.id, operator.email, SystemAuditService.STORE_STATUS_CHANGED, "STORE", s.id,
                before.name() + " -> " + s.status.name() + (body.note() == null || body.note().isBlank() ? ""
                        : " (" + body.note().trim() + ")"),
                ClientIps.of(req), true);
        return ApiResponse.ok(Map.of("status", s.status.name()));
    }

    /** 감사 로그. 콘솔에서 일어난 일을 운영자 자신이 되돌아볼 수 있어야 한다. */
    @GetMapping("/audit")
    ApiResponse<?> auditLog(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size,
            Authentication a) {
        guard.operator(a);
        Page<SystemAuditLog> found = auditLogs.findAllByOrderByCreatedAtDesc(pageRequest(page, size));
        List<Map<String, Object>> content = found.getContent().stream().map(row -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.id);
            item.put("actorEmail", row.actorEmail);
            item.put("action", row.action);
            item.put("targetType", row.targetType == null ? "" : row.targetType);
            item.put("targetId", row.targetId);
            item.put("detail", row.detail == null ? "" : row.detail);
            item.put("ip", row.ip == null ? "" : row.ip);
            item.put("succeeded", row.succeeded);
            item.put("createdAt", row.createdAt);
            return item;
        }).toList();
        return ApiResponse.ok(Map.of("content", content, "page", found.getNumber(), "size", found.getSize(),
                "totalElements", found.getTotalElements(), "totalPages", found.getTotalPages()));
    }

    private Store store(Long id) {
        return stores.findById(id)
                .orElseThrow(() -> new AppException("STORE_NOT_FOUND", "매장을 찾을 수 없습니다.", HttpStatus.NOT_FOUND));
    }

    private ZoneId zone() {
        return clock.getZone();
    }

    private PageRequest pageRequest(int page, int size) {
        if (page < 0 || size < 1 || size > 100)
            throw new AppException("INVALID_REQUEST", "page는 0 이상, size는 1~100이어야 합니다.");
        return PageRequest.of(page, size);
    }
}
