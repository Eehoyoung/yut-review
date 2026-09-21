package com.yutreview;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
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
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * 운영자 전용 콘솔 API.
 *
 * 매장 콘솔(`/api/admin/**`)과 주소부터 나눠 둔다. 그래야 Nginx나 앞단 프록시에서 이 경로만 통째로
 * 다르게 다룰 수 있고(추가 인증, IP 제한, 차단), 실수로 매장 API에 플랫폼 전체 조회가 섞여 들어가지
 * 않는다.
 *
 * 세 가지를 모든 엔드포인트가 같은 순서로 지킨다.
 * 1. {@link SystemConsoleGuard#enter} 계정·세션 재확인
 * 2. {@link SystemConsoleGuard#require} 권한 등급({@link ConsoleRole})
 * 3. 변경이면 {@link SystemConsoleGuard#requireStepUp} 재인증과 감사 기록
 *
 * 여기서 나가는 값에는 고객 개인정보가 없다. 운영자가 보는 것은 매장 단위 집계와 계정·요금제
 * 상태까지다. 손님 이름과 전화번호는 그 매장의 관리자만 볼 수 있다.
 */
@RestController
@RequestMapping("/api/system")
class SystemConsoleController {
    record OperatorLogin(@NotBlank @Size(max = 255) String email, @NotBlank @Size(max = 128) String password,
            @Size(max = 10) String code, @Size(max = 20) String backupCode) {
    }

    record TotpConfirm(@NotBlank @Size(max = 2000) String enrollmentToken, @NotBlank @Size(max = 10) String code) {
    }

    record StepUp(@NotBlank @Size(max = 10) String code) {
    }

    record PasswordChange(@NotBlank @Size(max = 128) String currentPassword,
            @NotBlank @Size(max = 128) String newPassword, @NotBlank @Size(max = 128) String newPasswordConfirm) {
    }

    /** 변경에는 사유를 받는다. 기록에 "무엇을"만 남고 "왜"가 없으면 나중에 아무 도움이 안 된다. */
    record PlanChange(@NotNull Plan plan, @NotBlank @Size(max = 200) String note) {
    }

    record StoreStatusChange(@NotNull StoreStatus status, @NotBlank @Size(max = 200) String note) {
    }

    record OperatorCreate(@NotBlank @Size(max = 255) String email, @NotBlank @Size(max = 50) String name,
            @NotBlank @Size(max = 128) String password, @NotNull ConsoleRole consoleRole) {
    }

    record OperatorUpdate(ConsoleRole consoleRole, Boolean disabled, @Size(max = 500) String allowedIps) {
    }

    private final OperatorAuthService auth;
    private final SystemConsoleGuard guard;
    private final SystemConsoleSettings settings;
    private final SystemAuditService audit;
    private final SystemAuditLogRepository auditLogs;
    private final OperatorSessionService sessionService;
    private final OperatorBackupCodeService backupCodes;
    private final OperatorDirectoryService operators;
    private final OperatorSecurityService securities;
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
            SystemAuditService audit, SystemAuditLogRepository auditLogs, OperatorSessionService sessionService,
            OperatorBackupCodeService backupCodes, OperatorDirectoryService operators,
            OperatorSecurityService securities, StoreRepository stores, AdminUserRepository admins, QrRepository qrs,
            PrizeRepository prizes, GameRepository games, CouponRepository coupons, PlatformStatsRepository platform,
            SubscriptionService subscriptions, GameConfigService gameConfig, JwtService jwt, Clock clock) {
        this.auth = auth;
        this.guard = guard;
        this.settings = settings;
        this.audit = audit;
        this.auditLogs = auditLogs;
        this.sessionService = sessionService;
        this.backupCodes = backupCodes;
        this.operators = operators;
        this.securities = securities;
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

    /* ---------------- 로그인 (토큰 없이 들어오는 유일한 경로) ---------------- */

    @PostMapping("/auth/login")
    ApiResponse<?> login(@Valid @RequestBody OperatorLogin body, HttpServletRequest req) {
        OperatorAuthService.Outcome outcome = auth.login(body.email(), body.password(), body.code(), body.backupCode(),
                ClientIps.of(req), ClientIps.userAgent(req));
        if (outcome.enrollment() != null) {
            OperatorAuthService.Enrollment e = outcome.enrollment();
            return ApiResponse.ok(Map.of("status", "TOTP_ENROLLMENT_REQUIRED", "enrollmentToken", e.enrollmentToken(),
                    "secret", e.secret(), "otpauthUrl", e.otpauthUrl(), "qrImage", e.qrImage()));
        }
        OperatorAuthService.Session s = outcome.session();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "AUTHENTICATED");
        out.put("accessToken", s.accessToken());
        out.put("tokenType", "Bearer");
        out.put("expiresInSeconds", s.expiresInSeconds());
        out.put("email", s.email());
        out.put("name", s.name());
        out.put("consoleRole", s.consoleRole().name());
        out.put("mustChangePassword", s.mustChangePassword());
        out.put("backupCodesRemaining", s.backupCodesRemaining());
        return ApiResponse.ok(out);
    }

    @PostMapping("/auth/totp/confirm")
    ApiResponse<?> confirmTotp(@Valid @RequestBody TotpConfirm body, HttpServletRequest req) {
        List<String> codes = auth.confirmEnrollment(body.enrollmentToken(), body.code(), ClientIps.of(req));
        // 복구 코드는 여기서 단 한 번 보여 준다. 해시만 저장하므로 다시 꺼낼 수 없다.
        return ApiResponse.ok(Map.of("status", "TOTP_ENROLLED", "backupCodes", codes));
    }

    /* ---------------- 내 계정과 세션 ---------------- */

    @GetMapping("/me")
    ApiResponse<?> me(Authentication a, HttpServletRequest req) {
        OperatorContext ctx = guard.enterAllowingPasswordChange(a, req);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("email", ctx.admin().email);
        out.put("name", ctx.admin().name);
        out.put("consoleRole", ctx.role().name());
        out.put("mustChangePassword", securities.mustChangePassword(ctx.security()));
        out.put("sessionMinutes", jwt.operatorTtlSeconds() / 60);
        out.put("idleMinutes", settings.idleMinutes());
        out.put("stepUpMinutes", settings.stepUpMinutes());
        out.put("stepUpFresh", sessionService.stepUpFresh(ctx.session()));
        out.put("ipRestricted", settings.restrictsIp() || !isBlank(ctx.security().allowedIps));
        out.put("backupCodesRemaining", backupCodes.remaining(ctx.admin().id));
        out.put("sessionExpiresAt", ctx.session().absoluteExpiresAt);
        return ApiResponse.ok(out);
    }

    @PostMapping("/session/step-up")
    ApiResponse<?> stepUp(@Valid @RequestBody StepUp body, Authentication a, HttpServletRequest req) {
        OperatorContext ctx = guard.enter(a, req);
        auth.stepUp(ctx, body.code(), ClientIps.of(req));
        return ApiResponse.ok(Map.of("stepUpFresh", true, "stepUpMinutes", settings.stepUpMinutes()));
    }

    @PostMapping("/session/logout")
    ApiResponse<?> logout(Authentication a, HttpServletRequest req) {
        OperatorContext ctx = guard.enterAllowingPasswordChange(a, req);
        auth.logout(ctx, ClientIps.of(req));
        return ApiResponse.ok(Map.of("status", "LOGGED_OUT"));
    }

    /** 내 세션 목록. OWNER는 `scope=all`로 모든 운영자의 활성 세션을 본다. */
    @GetMapping("/sessions")
    ApiResponse<?> sessions(@RequestParam(defaultValue = "mine") String scope, Authentication a,
            HttpServletRequest req) {
        OperatorContext ctx = guard.enter(a, req);
        boolean all = "all".equalsIgnoreCase(scope);
        if (all) guard.require(ctx, ConsoleRole.OWNER, req, "sessions:all");
        List<OperatorSession> rows = all ? sessionService.activeAll() : sessionService.of(ctx.admin().id);
        return ApiResponse.ok(rows.stream().map(s -> sessionView(s, ctx)).toList());
    }

    @DeleteMapping("/sessions/{id}")
    ApiResponse<?> revokeSession(@PathVariable Long id, Authentication a, HttpServletRequest req) {
        OperatorContext ctx = guard.enter(a, req);
        OperatorSession target = sessionService.byId(id)
                .orElseThrow(() -> new AppException("SESSION_NOT_FOUND", "세션을 찾을 수 없습니다.", HttpStatus.NOT_FOUND));
        boolean mine = target.admin.id.equals(ctx.admin().id);
        if (!mine) {
            // 남의 세션을 끊는 것은 계정 관리다. 권한과 재인증을 모두 요구한다.
            guard.require(ctx, ConsoleRole.OWNER, req, "sessions:revoke-others");
            guard.requireStepUp(ctx);
        }
        sessionService.revoke(target, mine ? "USER_REVOKED" : "ADMIN_REVOKED");
        audit.record(ctx.admin().id, ctx.admin().email, SystemAuditService.SESSION_REVOKED, "SESSION", target.id,
                mine ? "본인 세션" : target.admin.email, ClientIps.of(req), true);
        return ApiResponse.ok(Map.of("status", "REVOKED"));
    }

    @PostMapping("/account/password")
    ApiResponse<?> changePassword(@Valid @RequestBody PasswordChange body, Authentication a, HttpServletRequest req) {
        OperatorContext ctx = guard.enterAllowingPasswordChange(a, req);
        auth.changePassword(ctx, body.currentPassword(), body.newPassword(), body.newPasswordConfirm(),
                ClientIps.of(req));
        return ApiResponse.ok(Map.of("status", "PASSWORD_CHANGED"));
    }

    /** 복구 코드 재발급. 남은 코드가 몇 개든 새로 열 개를 만들고 예전 것은 모두 버린다. */
    @PostMapping("/account/backup-codes")
    ApiResponse<?> regenerateBackupCodes(Authentication a, HttpServletRequest req) {
        OperatorContext ctx = guard.enter(a, req);
        guard.requireStepUp(ctx);
        List<String> codes = backupCodes.regenerate(ctx.admin());
        audit.record(ctx.admin().id, ctx.admin().email, SystemAuditService.BACKUP_CODES_ISSUED, null, null,
                OperatorBackupCodeService.COUNT + "개 재발급", ClientIps.of(req), true);
        return ApiResponse.ok(Map.of("backupCodes", codes));
    }

    /* ---------------- 플랫폼 현황 ---------------- */

    @GetMapping("/overview")
    ApiResponse<?> overview(Authentication a, HttpServletRequest req) {
        guard.enter(a, req);
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

        Map<String, Object> security = new LinkedHashMap<>();
        security.put("ipRestricted", settings.restrictsIp());
        security.put("sessionMinutes", jwt.operatorTtlSeconds() / 60);
        security.put("idleMinutes", settings.idleMinutes());
        security.put("activeSessions", sessionService.activeCount());
        security.put("failedLogins24h", auditLogs.countBySucceededFalseAndCreatedAtAfter(
                clock.instant().minusSeconds(86400)));
        List<OperatorSecurity> everyOperator = operators.all();
        security.put("operators", everyOperator.size());
        security.put("lockedOperators", everyOperator.stream()
                .filter(s -> s.lockedUntil != null && s.lockedUntil.isAfter(clock.instant())).count());
        security.put("operatorsWithoutTotp", everyOperator.stream()
                .filter(s -> !s.disabled && !operators.hasTotp(s.admin.id)).count());
        out.put("security", security);
        return ApiResponse.ok(out);
    }

    /* ---------------- 매장 ---------------- */

    @GetMapping("/stores")
    ApiResponse<?> stores(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String query, Authentication a, HttpServletRequest req) {
        guard.enter(a, req);
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
        return ApiResponse.ok(pageView(content, found.getNumber(), found.getSize(), found.getTotalElements(),
                found.getTotalPages()));
    }

    @GetMapping("/stores/{id}")
    ApiResponse<?> store(@PathVariable Long id, Authentication a, HttpServletRequest req) {
        guard.enter(a, req);
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
     * 요금제 변경. 결제 연동 전까지 등급을 올리고 내리는 유일한 정식 경로이며, 재인증을 거쳐야 하고
     * 누가 언제 무엇을 왜 바꿨는지 기록이 남는다.
     */
    @PutMapping("/stores/{id}/plan")
    ApiResponse<?> changePlan(@PathVariable Long id, @Valid @RequestBody PlanChange body, Authentication a,
            HttpServletRequest req) {
        OperatorContext ctx = guard.enter(a, req);
        guard.require(ctx, ConsoleRole.OPERATOR, req, "store:plan");
        guard.requireStepUp(ctx);
        Store s = store(id);
        Plan before = subscriptions.planOf(id);
        StoreSubscription saved = subscriptions.changePlan(s, body.plan(), body.note());
        audit.record(ctx.admin().id, ctx.admin().email, SystemAuditService.PLAN_CHANGED, "STORE", s.id,
                before.name() + " -> " + saved.plan.name() + " (" + body.note().trim() + ")", ClientIps.of(req), true);
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
        OperatorContext ctx = guard.enter(a, req);
        guard.require(ctx, ConsoleRole.OPERATOR, req, "store:status");
        guard.requireStepUp(ctx);
        Store s = store(id);
        StoreStatus before = s.status;
        s.status = body.status();
        s.updatedAt = clock.instant();
        stores.save(s);
        audit.record(ctx.admin().id, ctx.admin().email, SystemAuditService.STORE_STATUS_CHANGED, "STORE", s.id,
                before.name() + " -> " + s.status.name() + " (" + body.note().trim() + ")", ClientIps.of(req), true);
        return ApiResponse.ok(Map.of("status", s.status.name()));
    }

    /* ---------------- 운영자 계정 (OWNER 전용) ---------------- */

    @GetMapping("/operators")
    ApiResponse<?> operators(Authentication a, HttpServletRequest req) {
        OperatorContext ctx = guard.enter(a, req);
        guard.require(ctx, ConsoleRole.OWNER, req, "operators:list");
        return ApiResponse.ok(operators.all().stream().map(s -> operatorView(s, ctx)).toList());
    }

    @PostMapping("/operators")
    ApiResponse<?> createOperator(@Valid @RequestBody OperatorCreate body, Authentication a, HttpServletRequest req) {
        OperatorContext ctx = guard.enter(a, req);
        guard.require(ctx, ConsoleRole.OWNER, req, "operators:create");
        guard.requireStepUp(ctx);
        OperatorDirectoryService.Created created = operators.create(body.email(), body.name(), body.password(),
                body.consoleRole());
        audit.record(ctx.admin().id, ctx.admin().email, SystemAuditService.OPERATOR_CREATED, "OPERATOR",
                created.admin().id, created.admin().email + " · " + body.consoleRole(), ClientIps.of(req), true);
        return ApiResponse.ok(Map.of("id", created.admin().id, "email", created.admin().email, "consoleRole",
                created.security().consoleRole.name(), "mustChangePassword", true));
    }

    @PutMapping("/operators/{id}")
    ApiResponse<?> updateOperator(@PathVariable Long id, @Valid @RequestBody OperatorUpdate body, Authentication a,
            HttpServletRequest req) {
        OperatorContext ctx = guard.enter(a, req);
        guard.require(ctx, ConsoleRole.OWNER, req, "operators:update");
        guard.requireStepUp(ctx);
        OperatorSecurity before = operators.of(id);
        String was = before.consoleRole + (before.disabled ? "/중지" : "");
        OperatorSecurity saved = operators.update(ctx, id, body.consoleRole(), body.disabled(), body.allowedIps());
        audit.record(ctx.admin().id, ctx.admin().email, SystemAuditService.OPERATOR_UPDATED, "OPERATOR", id,
                saved.admin.email + " · " + was + " -> " + saved.consoleRole + (saved.disabled ? "/중지" : ""),
                ClientIps.of(req), true);
        return ApiResponse.ok(operatorView(saved, ctx));
    }

    @PostMapping("/operators/{id}/totp/reset")
    ApiResponse<?> resetOperatorTotp(@PathVariable Long id, Authentication a, HttpServletRequest req) {
        OperatorContext ctx = guard.enter(a, req);
        guard.require(ctx, ConsoleRole.OWNER, req, "operators:totp-reset");
        guard.requireStepUp(ctx);
        OperatorSecurity target = operators.of(id);
        operators.resetTotp(id);
        audit.record(ctx.admin().id, ctx.admin().email, SystemAuditService.TOTP_RESET, "OPERATOR", id,
                target.admin.email, ClientIps.of(req), true);
        return ApiResponse.ok(Map.of("status", "TOTP_RESET"));
    }

    @PostMapping("/operators/{id}/unlock")
    ApiResponse<?> unlockOperator(@PathVariable Long id, Authentication a, HttpServletRequest req) {
        OperatorContext ctx = guard.enter(a, req);
        guard.require(ctx, ConsoleRole.OWNER, req, "operators:unlock");
        guard.requireStepUp(ctx);
        OperatorSecurity target = operators.of(id);
        operators.unlock(id);
        audit.record(ctx.admin().id, ctx.admin().email, SystemAuditService.OPERATOR_UPDATED, "OPERATOR", id,
                target.admin.email + " · 잠금 해제", ClientIps.of(req), true);
        return ApiResponse.ok(Map.of("status", "UNLOCKED"));
    }

    /* ---------------- 감사 기록 ---------------- */

    @GetMapping("/audit")
    ApiResponse<?> auditLog(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String action, @RequestParam(required = false) String actor,
            @RequestParam(defaultValue = "false") boolean failuresOnly,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            Authentication a, HttpServletRequest req) {
        guard.enter(a, req);
        LocalDate end = to == null ? LocalDate.now(clock) : to, start = from == null ? end.minusDays(29) : from;
        Page<SystemAuditLog> found = auditLogs.search(startOf(start), endOf(end), blankToNull(action),
                likeOrNull(actor), failuresOnly, pageRequest(page, size));
        return ApiResponse.ok(pageView(found.getContent().stream().map(this::auditView).toList(), found.getNumber(),
                found.getSize(), found.getTotalElements(), found.getTotalPages()));
    }

    /** 감사 기록 CSV. 내보내는 행위 자체도 기록에 남는다. */
    @GetMapping(value = "/audit/export", produces = "text/csv; charset=UTF-8")
    ResponseEntity<String> exportAudit(@RequestParam(required = false) String action,
            @RequestParam(required = false) String actor,
            @RequestParam(defaultValue = "false") boolean failuresOnly,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            Authentication a, HttpServletRequest req) {
        OperatorContext ctx = guard.enter(a, req);
        guard.require(ctx, ConsoleRole.OPERATOR, req, "audit:export");
        LocalDate end = to == null ? LocalDate.now(clock) : to, start = from == null ? end.minusDays(29) : from;
        List<SystemAuditLog> rows = auditLogs.searchForExport(startOf(start), endOf(end), blankToNull(action),
                likeOrNull(actor), failuresOnly, PageRequest.of(0, 5000));
        audit.record(ctx.admin().id, ctx.admin().email, SystemAuditService.AUDIT_EXPORTED, null, null,
                start + "~" + end + " · " + rows.size() + "행", ClientIps.of(req), true);
        String filename = "operator-audit_" + start + "_" + end + ".csv";
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build().toString())
                .body(SystemAuditService.csv(rows));
    }

    /**
     * 감사 기록 무결성 검증.
     *
     * 각 행이 앞 행의 해시를 품고 있어, 중간을 고치거나 지우면 그 지점부터 어긋난다. 무결성을
     * 보장하는 장치가 아니라 조용히 지울 수 없게 하는 장치다.
     */
    @GetMapping("/audit/verify")
    ApiResponse<?> verifyAudit(Authentication a, HttpServletRequest req) {
        OperatorContext ctx = guard.enter(a, req);
        guard.require(ctx, ConsoleRole.OWNER, req, "audit:verify");
        SystemAuditService.Integrity result = audit.verify();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("intact", result.intact());
        out.put("checked", result.checked());
        out.put("firstBrokenId", result.firstBrokenId());
        out.put("firstBrokenAt", result.firstBrokenAt());
        return ApiResponse.ok(out);
    }

    /* ---------------- 내부 ---------------- */

    private Map<String, Object> sessionView(OperatorSession s, OperatorContext ctx) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", s.id);
        out.put("email", s.admin.email);
        out.put("current", s.id.equals(ctx.session().id));
        out.put("device", s.userAgentLabel == null ? "" : s.userAgentLabel);
        out.put("ip", s.ip == null ? "" : s.ip);
        out.put("createdAt", s.createdAt);
        out.put("lastSeenAt", s.lastSeenAt);
        out.put("expiresAt", s.absoluteExpiresAt);
        out.put("revokedAt", s.revokedAt);
        out.put("revokedReason", s.revokedReason == null ? "" : s.revokedReason);
        return out;
    }

    private Map<String, Object> operatorView(OperatorSecurity s, OperatorContext ctx) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", s.admin.id);
        out.put("email", s.admin.email);
        out.put("name", s.admin.name);
        out.put("consoleRole", s.consoleRole.name());
        out.put("disabled", s.disabled);
        out.put("self", s.admin.id.equals(ctx.admin().id));
        out.put("totpEnrolled", operators.hasTotp(s.admin.id));
        out.put("locked", s.lockedUntil != null && s.lockedUntil.isAfter(clock.instant()));
        out.put("lockedUntil", s.lockedUntil);
        out.put("mustChangePassword", s.passwordChangedAt == null);
        out.put("allowedIps", s.allowedIps == null ? "" : s.allowedIps);
        out.put("lastLoginAt", s.lastLoginAt);
        out.put("lastLoginIp", s.lastLoginIp == null ? "" : s.lastLoginIp);
        out.put("createdAt", s.createdAt);
        return out;
    }

    private Map<String, Object> auditView(SystemAuditLog row) {
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
    }

    private Map<String, Object> pageView(List<?> content, int page, int size, long totalElements, int totalPages) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("content", content);
        out.put("page", page);
        out.put("size", size);
        out.put("totalElements", totalElements);
        out.put("totalPages", totalPages);
        return out;
    }

    private Store store(Long id) {
        return stores.findById(id)
                .orElseThrow(() -> new AppException("STORE_NOT_FOUND", "매장을 찾을 수 없습니다.", HttpStatus.NOT_FOUND));
    }

    private java.time.Instant startOf(LocalDate date) {
        return date.atStartOfDay(zone()).toInstant();
    }

    private java.time.Instant endOf(LocalDate date) {
        return date.plusDays(1).atStartOfDay(zone()).toInstant();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String likeOrNull(String value) {
        String cleaned = blankToNull(value);
        return cleaned == null ? null : "%" + cleaned.toLowerCase(java.util.Locale.ROOT) + "%";
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
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
