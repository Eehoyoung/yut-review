package com.yutreview;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 운영자 콘솔(`/api/system/**`)의 접근 통제.
 *
 * 매장 콘솔과 나누는 이유는 보는 범위가 다르기 때문이다. 매장 사장은 자기 매장만 보고, 운영자는
 * 플랫폼 전체를 본다. 그래서 같은 계정이라도 매장 콘솔로 로그인해 받은 토큰으로는 이 API가 열리지
 * 않고, 문을 네 겹으로 둔다.
 *
 * 1. {@link SystemConsoleGateFilter} 꺼짐 스위치와 IP 허용 목록 (토큰 이전 단계)
 * 2. 비밀번호 + TOTP 2단계 인증, 시도 제한 ({@link OperatorAuthService})
 * 3. 전용 스코프를 가진 짧은 토큰 ({@link JwtService#issueOperator})
 * 4. 요청마다 DB의 현재 역할 재확인 ({@link SystemConsoleGuard})
 *
 * 그리고 로그인과 모든 변경은 {@link SystemAuditService}가 남긴다.
 */
@Component
class SystemConsoleSettings {
    private final boolean enabled;
    private final List<IpRule> allowed;
    private final String issuer;

    SystemConsoleSettings(@Value("${app.system-console.enabled:true}") boolean enabled,
            @Value("${app.system-console.allowed-ips:}") String allowedIps,
            @Value("${app.system-console.issuer:윷리뷰 운영자}") String issuer) {
        this.enabled = enabled;
        this.allowed = IpRule.parse(allowedIps);
        this.issuer = issuer;
    }

    boolean enabled() {
        return enabled;
    }

    String issuer() {
        return issuer;
    }

    /** 허용 목록이 비어 있으면 IP로는 막지 않는다. 그래도 비밀번호와 2단계 인증은 그대로 남는다. */
    boolean ipAllowed(String ip) {
        return allowed.isEmpty() || allowed.stream().anyMatch(rule -> rule.matches(ip));
    }

    boolean restrictsIp() {
        return !allowed.isEmpty();
    }

    /** 단일 IP 또는 CIDR 한 줄. IPv4와 IPv6를 같은 방식(바이트 + 프리픽스 길이)으로 본다. */
    record IpRule(byte[] address, int prefixBits) {
        static List<IpRule> parse(String raw) {
            List<IpRule> rules = new ArrayList<>();
            for (String entry : (raw == null ? "" : raw).split(",")) {
                String value = entry.trim();
                if (value.isEmpty()) continue;
                try {
                    String[] parts = value.split("/", 2);
                    byte[] address = InetAddress.getByName(parts[0]).getAddress();
                    int bits = parts.length == 2 ? Integer.parseInt(parts[1].trim()) : address.length * 8;
                    if (bits < 0 || bits > address.length * 8) throw new IllegalArgumentException(value);
                    rules.add(new IpRule(address, bits));
                } catch (Exception e) {
                    throw new IllegalArgumentException("SYSTEM_CONSOLE_ALLOWED_IPS 항목이 올바르지 않습니다: " + value, e);
                }
            }
            return List.copyOf(rules);
        }

        boolean matches(String ip) {
            try {
                byte[] candidate = InetAddress.getByName(ip).getAddress();
                if (candidate.length != address.length) return false;
                for (int i = 0; i < prefixBits; i += 8) {
                    int mask = prefixBits - i >= 8 ? 0xFF : (0xFF << (8 - (prefixBits - i))) & 0xFF;
                    if ((candidate[i / 8] & mask) != (address[i / 8] & mask)) return false;
                }
                return true;
            } catch (Exception e) {
                return false;
            }
        }
    }
}

/**
 * 토큰을 보기도 전에 닫는 문.
 *
 * 꺼진 콘솔과 허용되지 않은 IP에는 "여기 뭔가 있다"는 신호조차 주지 않으려고 404로 답한다.
 * 로그인 엔드포인트도 이 필터를 지나야 한다. 사람이 없는 주소로 비밀번호를 던져 보는 시도를
 * 애플리케이션 앞에서 끊는 것이 목적이다.
 *
 * 주의: 여기서 보는 IP는 Nginx가 넣어 주는 X-Real-IP다. Cloudflare Quick Tunnel을 거치면 모든
 * 요청이 cloudflared 컨테이너 IP로 보여 허용 목록이 사실상 무의미해진다. 터널 뒤에서 IP 제한이
 * 필요하면 Cloudflare Access 같은 앞단 인증을 함께 써야 한다.
 */
@Component
class SystemConsoleGateFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(SystemConsoleGateFilter.class);
    private final SystemConsoleSettings settings;

    SystemConsoleGateFilter(SystemConsoleSettings settings) {
        this.settings = settings;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        if (!req.getRequestURI().startsWith("/api/system/")) {
            chain.doFilter(req, res);
            return;
        }
        if (!settings.enabled()) {
            SecurityConfig.writeError(res, 404, "NOT_FOUND", "요청하신 경로를 찾을 수 없습니다.");
            return;
        }
        String ip = ClientIps.of(req);
        if (!settings.ipAllowed(ip)) {
            log.warn("System console request rejected by IP allowlist");
            SecurityConfig.writeError(res, 404, "NOT_FOUND", "요청하신 경로를 찾을 수 없습니다.");
            return;
        }
        // 콘솔 응답은 저장되지도 색인되지도 않게 한다.
        res.setHeader("Cache-Control", "no-store");
        res.setHeader("X-Robots-Tag", "noindex, nofollow, noarchive");
        chain.doFilter(req, res);
    }
}

final class ClientIps {
    private ClientIps() {
    }

    /** Nginx가 넣는 X-Real-IP를 먼저 본다. 없으면 소켓 주소. */
    static String of(HttpServletRequest req) {
        String proxied = req.getHeader("X-Real-IP");
        return proxied == null || proxied.isBlank() ? req.getRemoteAddr() : proxied.trim();
    }
}

/**
 * RFC 6238 TOTP. 인증 앱(Google Authenticator, 1Password 등)이 쓰는 기본값 그대로:
 * SHA-1, 30초, 6자리. 앱과 서버가 서로 다른 기본값을 쓰면 아무 코드도 맞지 않는다.
 *
 * 새 라이브러리를 들이지 않았다. 필요한 것은 HMAC 한 번과 Base32뿐이고, 둘 다 표준 JDK에 있다.
 */
@Service
class TotpService {
    static final int DIGITS = 6, PERIOD_SECONDS = 30, SECRET_BYTES = 20;
    /** 앞뒤 한 칸(±30초)까지 받아 준다. 폰과 서버의 시계가 몇 초 어긋나는 건 흔한 일이다. */
    private static final int DRIFT_STEPS = 1;
    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private final SecureRandom random;

    TotpService(SecureRandom random) {
        this.random = random;
    }

    String newSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        random.nextBytes(bytes);
        return encode(bytes);
    }

    String otpauthUrl(String issuer, String account, String secret) {
        String label = encodeUrl(issuer) + ":" + encodeUrl(account);
        return "otpauth://totp/" + label + "?secret=" + secret + "&issuer=" + encodeUrl(issuer)
                + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + PERIOD_SECONDS;
    }

    static long step(Instant now) {
        return Math.floorDiv(now.getEpochSecond(), PERIOD_SECONDS);
    }

    /**
     * 코드가 맞으면 그 코드가 속한 시간 슬롯을 돌려준다.
     *
     * 슬롯을 밖으로 내보내는 이유는 호출부가 이미 쓴 코드를 기록해 두기 위해서다. 어깨너머로 본
     * 코드나 가로챈 코드를 30초 안에 다시 쓰는 것을 막는다.
     */
    Optional<Long> verify(String secret, String code, Instant now, long lastUsedStep) {
        if (code == null || !code.trim().matches("\\d{" + DIGITS + "}")) return Optional.empty();
        String candidate = code.trim();
        long current = step(now);
        for (long s = current - DRIFT_STEPS; s <= current + DRIFT_STEPS; s++) {
            if (s <= lastUsedStep) continue;
            if (MessageDigest.isEqual(code(secret, s).getBytes(StandardCharsets.US_ASCII),
                    candidate.getBytes(StandardCharsets.US_ASCII)))
                return Optional.of(s);
        }
        return Optional.empty();
    }

    static String code(String secret, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(decode(secret), "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int offset = hash[hash.length - 1] & 0x0F;
            int binary = ((hash[offset] & 0x7F) << 24) | ((hash[offset + 1] & 0xFF) << 16)
                    | ((hash[offset + 2] & 0xFF) << 8) | (hash[offset + 3] & 0xFF);
            return String.format(Locale.ROOT, "%0" + DIGITS + "d", binary % (int) Math.pow(10, DIGITS));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("TOTP code generation failed", e);
        }
    }

    static String encode(byte[] data) {
        StringBuilder out = new StringBuilder();
        int buffer = 0, bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                out.append(BASE32.charAt((buffer >> (bits - 5)) & 0x1F));
                bits -= 5;
            }
        }
        if (bits > 0) out.append(BASE32.charAt((buffer << (5 - bits)) & 0x1F));
        return out.toString();
    }

    static byte[] decode(String secret) {
        String cleaned = secret.replace("=", "").replace(" ", "").toUpperCase(Locale.ROOT);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int buffer = 0, bits = 0;
        for (char c : cleaned.toCharArray()) {
            int index = BASE32.indexOf(c);
            if (index < 0) throw new IllegalArgumentException("Invalid Base32 secret");
            buffer = (buffer << 5) | index;
            bits += 5;
            if (bits >= 8) {
                out.write((buffer >> (bits - 8)) & 0xFF);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }

    /** 인증 앱에 손으로 32자를 옮겨 적게 하지 않는다. 등록 화면이 이 이미지를 그대로 보여 준다. */
    static String qrDataUrl(String value, int size) {
        try {
            BitMatrix matrix = new QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, size, size,
                    Map.of(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M, EncodeHintType.MARGIN, 2));
            BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = image.createGraphics();
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, size, size);
            g.setColor(Color.BLACK);
            for (int row = 0; row < size; row++)
                for (int col = 0; col < size; col++)
                    if (matrix.get(col, row)) g.fillRect(col, row, 1, 1);
            g.dispose();
            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                ImageIO.write(image, "png", out);
                return "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(out.toByteArray());
            }
        } catch (WriterException | IOException e) {
            throw new IllegalStateException("QR 생성에 실패했습니다.", e);
        }
    }

    private static String encodeUrl(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}

/**
 * 운영자 로그인 시도 제한.
 *
 * 매장 로그인보다 좁게 잡는다(분당 5회 → 5분에 5회). 운영자는 한 사람이고, 정상적인 사람은 5분에
 * 다섯 번씩 비밀번호를 틀리지 않는다. IP와 계정 양쪽으로 센다: IP만 세면 주소를 바꿔 가며
 * 한 계정을 두드릴 수 있고, 계정만 세면 남의 계정을 잠가 버릴 수 있다.
 */
@Service
class OperatorLoginLimiter {
    private record Attempts(Instant since, int count) {
    }

    private static final int MAX_PER_IP = 5, MAX_PER_ACCOUNT = 10, WINDOW_SECONDS = 300;
    private final Clock clock;
    private final Map<String, Attempts> failures = new ConcurrentHashMap<>();

    OperatorLoginLimiter(Clock clock) {
        this.clock = clock;
    }

    void check(String ip, String email) {
        if (count("ip:" + ip) >= MAX_PER_IP || count("account:" + email) >= MAX_PER_ACCOUNT)
            throw new AppException("AUTH_RATE_LIMITED", "로그인 시도가 너무 많습니다. 잠시 후 다시 시도해 주세요.",
                    HttpStatus.TOO_MANY_REQUESTS);
    }

    void failed(String ip, String email) {
        record("ip:" + ip);
        record("account:" + email);
    }

    void succeeded(String ip, String email) {
        failures.remove("ip:" + ip);
        failures.remove("account:" + email);
    }

    private int count(String key) {
        Attempts a = failures.get(key);
        return a == null || a.since.plusSeconds(WINDOW_SECONDS).isBefore(clock.instant()) ? 0 : a.count;
    }

    private void record(String key) {
        Instant now = clock.instant();
        if (failures.size() > 10000)
            failures.entrySet().removeIf(e -> e.getValue().since.plusSeconds(WINDOW_SECONDS).isBefore(now));
        failures.compute(key, (k, v) -> v == null || v.since.plusSeconds(WINDOW_SECONDS).isBefore(now)
                ? new Attempts(now, 1)
                : new Attempts(v.since, v.count + 1));
    }
}

/** 운영자 콘솔에서 일어난 일을 남긴다. 고객 개인정보는 한 칸도 들어가지 않는다. */
@Service
class SystemAuditService {
    static final String LOGIN = "LOGIN", LOGIN_FAILED = "LOGIN_FAILED", TOTP_ENROLL_START = "TOTP_ENROLL_START",
            TOTP_ENROLLED = "TOTP_ENROLLED", PLAN_CHANGED = "PLAN_CHANGED", STORE_STATUS_CHANGED = "STORE_STATUS_CHANGED";
    private final SystemAuditLogRepository logs;
    private final Clock clock;

    SystemAuditService(SystemAuditLogRepository logs, Clock clock) {
        this.logs = logs;
        this.clock = clock;
    }

    /**
     * 실패한 요청의 기록이 그 요청과 함께 롤백되면 안 된다. 남겨야 할 것이 바로 실패이기 때문에
     * 별도 트랜잭션으로 쓴다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void record(Long actorId, String actorEmail, String action, String targetType, Long targetId, String detail,
            String ip, boolean succeeded) {
        SystemAuditLog row = new SystemAuditLog();
        row.actorAdminId = actorId;
        row.actorEmail = actorEmail == null ? "" : trim(actorEmail, 255);
        row.action = action;
        row.targetType = targetType;
        row.targetId = targetId;
        row.detail = detail == null ? null : trim(detail, 500);
        row.ip = ip == null ? null : trim(ip, 64);
        row.succeeded = succeeded;
        row.createdAt = clock.instant();
        logs.save(row);
    }

    private static String trim(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}

/**
 * 요청마다 DB의 현재 역할을 다시 본다.
 *
 * 토큰 안의 역할만 믿으면, 운영자 권한을 회수한 뒤에도 이미 발급된 토큰이 만료될 때까지 콘솔이
 * 열려 있다. 계정 하나짜리 콘솔이라 조회 한 번이 비싸지 않다.
 */
@Service
class SystemConsoleGuard {
    private final AdminUserRepository admins;

    SystemConsoleGuard(AdminUserRepository admins) {
        this.admins = admins;
    }

    AdminUser operator(Authentication auth) {
        Long adminId = auth == null ? null : (Long) auth.getPrincipal();
        return admins.findById(adminId == null ? -1L : adminId)
                .filter(a -> a.role == AdminRole.SYSTEM_ADMIN)
                .orElseThrow(() -> new AppException("FORBIDDEN", "접근 권한이 없습니다.", HttpStatus.FORBIDDEN));
    }
}

/**
 * 운영자 로그인과 2단계 인증 등록.
 *
 * 실패 메시지는 무엇이 틀렸는지 알려 주지 않는다. "그 이메일은 운영자가 아니다"를 알려 주면
 * 공격자가 계정 목록을 좁힐 수 있기 때문에, 없는 계정·틀린 비밀번호·운영자가 아닌 계정이 모두
 * 같은 답을 받는다.
 */
@Service
class OperatorAuthService {
    /** 로그인 결과는 둘 중 하나다. 토큰을 받거나, 2단계 인증을 먼저 등록하라는 안내를 받거나. */
    record Session(String accessToken, int expiresInSeconds, String email, String name) {
    }

    record Enrollment(String enrollmentToken, String secret, String otpauthUrl, String qrImage) {
    }

    record Outcome(Session session, Enrollment enrollment) {
    }

    private final AdminUserRepository admins;
    private final AdminTotpRepository credentials;
    private final PasswordEncoder encoder;
    private final TotpService totp;
    private final JwtService jwt;
    private final OperatorLoginLimiter limiter;
    private final SystemAuditService audit;
    private final SystemConsoleSettings settings;
    private final PhoneService secrets;
    private final Clock clock;

    OperatorAuthService(AdminUserRepository admins, AdminTotpRepository credentials, PasswordEncoder encoder,
            TotpService totp, JwtService jwt, OperatorLoginLimiter limiter, SystemAuditService audit,
            SystemConsoleSettings settings, PhoneService secrets, Clock clock) {
        this.admins = admins;
        this.credentials = credentials;
        this.encoder = encoder;
        this.totp = totp;
        this.jwt = jwt;
        this.limiter = limiter;
        this.audit = audit;
        this.settings = settings;
        this.secrets = secrets;
        this.clock = clock;
    }

    @Transactional
    Outcome login(String rawEmail, String password, String code, String ip) {
        String email = Inputs.lower(rawEmail);
        limiter.check(ip, email);
        Optional<AdminUser> found = admins.findByEmail(email)
                .filter(a -> a.role == AdminRole.SYSTEM_ADMIN)
                .filter(a -> encoder.matches(password, a.passwordHash));
        if (found.isEmpty()) {
            limiter.failed(ip, email);
            audit.record(null, email, SystemAuditService.LOGIN_FAILED, null, null, "password", ip, false);
            throw invalid();
        }
        AdminUser operator = found.get();
        AdminTotpCredential credential = credentials.findByAdminId(operator.id).orElse(null);

        // 아직 2단계 인증이 없는 운영자는 등록부터 한다. 비밀번호만으로 콘솔을 여는 경로는 없다.
        if (credential == null || !credential.confirmed) {
            Enrollment enrollment = startEnrollment(operator, credential);
            audit.record(operator.id, operator.email, SystemAuditService.TOTP_ENROLL_START, null, null, null, ip, true);
            return new Outcome(null, enrollment);
        }
        if (code == null || code.isBlank()) {
            limiter.failed(ip, email);
            throw new AppException("TOTP_REQUIRED", "인증 앱의 6자리 코드를 입력해 주세요.", HttpStatus.UNAUTHORIZED);
        }
        Optional<Long> step = totp.verify(secrets.decrypt(credential.secretEncrypted), code, clock.instant(),
                credential.lastUsedStep);
        if (step.isEmpty()) {
            limiter.failed(ip, email);
            audit.record(operator.id, operator.email, SystemAuditService.LOGIN_FAILED, null, null, "totp", ip, false);
            throw new AppException("TOTP_INVALID", "인증 코드가 올바르지 않습니다.", HttpStatus.UNAUTHORIZED);
        }
        credential.lastUsedStep = step.get();
        limiter.succeeded(ip, email);
        audit.record(operator.id, operator.email, SystemAuditService.LOGIN, null, null, null, ip, true);
        return new Outcome(new Session(jwt.issueOperator(operator), jwt.operatorTtlSeconds(), operator.email,
                operator.name), null);
    }

    /**
     * 등록 확인. 임시 토큰과 코드가 모두 맞아야 비밀값이 확정된다.
     *
     * 코드를 한 번 맞혀 보게 하는 이유는, 인증 앱에 실제로 들어갔는지 확인하지 않고 확정하면
     * 다음 로그인에서 아무 코드도 맞지 않아 콘솔이 통째로 잠기기 때문이다.
     */
    @Transactional
    void confirmEnrollment(String enrollmentToken, String code, String ip) {
        JwtService.Claims claims = jwt.verify(enrollmentToken == null ? "" : enrollmentToken);
        if (claims == null || !JwtService.SCOPE_ENROLL.equals(claims.scope()) || claims.role() != AdminRole.SYSTEM_ADMIN)
            throw new AppException("ENROLLMENT_EXPIRED", "등록 시간이 지났습니다. 다시 로그인해 주세요.", HttpStatus.UNAUTHORIZED);
        AdminUser operator = admins.findById(claims.adminId())
                .filter(a -> a.role == AdminRole.SYSTEM_ADMIN)
                .orElseThrow(() -> invalid());
        AdminTotpCredential credential = credentials.findByAdminId(operator.id)
                .orElseThrow(() -> new AppException("ENROLLMENT_EXPIRED", "등록 시간이 지났습니다. 다시 로그인해 주세요.",
                        HttpStatus.UNAUTHORIZED));
        if (credential.confirmed)
            throw new AppException("TOTP_ALREADY_ENROLLED", "이미 2단계 인증이 등록되어 있습니다.");
        Optional<Long> step = totp.verify(secrets.decrypt(credential.secretEncrypted), code, clock.instant(),
                credential.lastUsedStep);
        if (step.isEmpty()) {
            limiter.failed(ip, operator.email);
            throw new AppException("TOTP_INVALID", "인증 코드가 올바르지 않습니다.", HttpStatus.UNAUTHORIZED);
        }
        credential.confirmed = true;
        credential.confirmedAt = clock.instant();
        credential.lastUsedStep = step.get();
        audit.record(operator.id, operator.email, SystemAuditService.TOTP_ENROLLED, null, null, null, ip, true);
    }

    private Enrollment startEnrollment(AdminUser operator, AdminTotpCredential existing) {
        // 확정되지 않은 비밀값은 매번 새로 만든다. 등록을 중간에 그만둔 값이 남아 돌아다니지 않는다.
        AdminTotpCredential credential = existing == null ? new AdminTotpCredential() : existing;
        String secret = totp.newSecret();
        if (credential.id == null) {
            credential.admin = operator;
            credential.createdAt = clock.instant();
        }
        credential.secretEncrypted = secrets.encrypt(secret);
        credential.confirmed = false;
        credential.lastUsedStep = 0;
        credentials.save(credential);
        String url = totp.otpauthUrl(settings.issuer(), operator.email, secret);
        return new Enrollment(jwt.issueEnrollment(operator), secret, url, TotpService.qrDataUrl(url, 240));
    }

    private static AppException invalid() {
        return new AppException("AUTH_INVALID", "이메일 또는 비밀번호가 올바르지 않습니다.", HttpStatus.UNAUTHORIZED);
    }
}

/**
 * 운영자 계정 부트스트랩.
 *
 * 매장 시드(현장테스트용 {@link Bootstrap})와 분리한다. 실서비스에는 테스트 매장을 만들지 않고
 * 운영자 계정만 있어야 하기 때문이다. 이미 있는 계정은 건드리지 않는다. 환경변수 한 줄로 기존
 * 계정의 비밀번호가 덮이면, 그 변수를 읽을 수 있는 사람이 곧 운영자가 된다.
 */
@Component
class OperatorBootstrap implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(OperatorBootstrap.class);
    private final String email, password;
    private final AdminUserRepository admins;
    private final PasswordEncoder encoder;
    private final Clock clock;

    OperatorBootstrap(@Value("${app.system-console.operator-email:}") String email,
            @Value("${app.system-console.operator-password:}") String password, AdminUserRepository admins,
            PasswordEncoder encoder, Clock clock) {
        this.email = email;
        this.password = password;
        this.admins = admins;
        this.encoder = encoder;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (email.isBlank() || password.isBlank()) return;
        String normalized = Inputs.email(email);
        Optional<AdminUser> existing = admins.findByEmail(normalized);
        if (existing.isPresent()) {
            if (existing.get().role != AdminRole.SYSTEM_ADMIN)
                log.warn("SYSTEM_ADMIN_EMAIL points at an existing non-operator account; leaving it unchanged");
            return;
        }
        if (password.length() < 12)
            throw new IllegalStateException("SYSTEM_ADMIN_PASSWORD must contain at least 12 characters");
        AdminUser operator = new AdminUser();
        operator.email = normalized;
        operator.passwordHash = encoder.encode(password);
        operator.name = "운영자";
        operator.role = AdminRole.SYSTEM_ADMIN;
        operator.createdAt = clock.instant();
        admins.save(operator);
        log.info("Created the system operator account; enroll two-factor authentication on first console login");
    }
}
