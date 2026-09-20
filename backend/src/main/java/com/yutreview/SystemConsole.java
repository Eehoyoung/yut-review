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
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
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
import org.springframework.scheduling.annotation.Scheduled;
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
 * 않고, 문을 겹으로 둔다.
 *
 * 1. {@link SystemConsoleGateFilter} 꺼짐 스위치와 전역 IP 허용 목록 (토큰 이전 단계)
 * 2. 비밀번호 + TOTP(또는 복구 코드), 계정 잠금과 시도 제한 ({@link OperatorAuthService})
 * 3. 전용 스코프 토큰 + 서버가 들고 있는 세션 행 ({@link OperatorSessionService})
 * 4. 요청마다 역할·계정 상태·세션 상태 재확인 ({@link SystemConsoleGuard})
 * 5. 권한 등급({@link ConsoleRole})과 위험한 조작 앞의 재인증(step-up)
 *
 * 그리고 일어난 일은 전부 {@link SystemAuditService}가 해시 사슬로 남긴다.
 */
@Component
class SystemConsoleSettings {
    private final boolean enabled;
    private final List<IpRule> allowed;
    private final String issuer;
    private final int idleMinutes, stepUpMinutes, maxSessions, maxFailedAttempts, lockMinutes;

    // 생성자가 둘이라 어느 쪽이 빈인지 명시한다. 아래 짧은 생성자는 테스트에서 규칙만 볼 때 쓴다.
    @org.springframework.beans.factory.annotation.Autowired
    SystemConsoleSettings(@Value("${app.system-console.enabled:true}") boolean enabled,
            @Value("${app.system-console.allowed-ips:}") String allowedIps,
            @Value("${app.system-console.issuer:윷리뷰 운영자}") String issuer,
            @Value("${app.system-console.idle-minutes:15}") int idleMinutes,
            @Value("${app.system-console.step-up-minutes:5}") int stepUpMinutes,
            @Value("${app.system-console.max-sessions:5}") int maxSessions,
            @Value("${app.system-console.max-failed-attempts:10}") int maxFailedAttempts,
            @Value("${app.system-console.lock-minutes:15}") int lockMinutes) {
        this.enabled = enabled;
        this.allowed = IpRule.parse(allowedIps);
        this.issuer = issuer;
        this.idleMinutes = Math.max(1, idleMinutes);
        this.stepUpMinutes = Math.max(1, stepUpMinutes);
        this.maxSessions = Math.max(1, maxSessions);
        this.maxFailedAttempts = Math.max(3, maxFailedAttempts);
        this.lockMinutes = Math.max(1, lockMinutes);
    }

    SystemConsoleSettings(boolean enabled, String allowedIps, String issuer) {
        this(enabled, allowedIps, issuer, 15, 5, 5, 10, 15);
    }

    boolean enabled() { return enabled; }
    String issuer() { return issuer; }
    int idleMinutes() { return idleMinutes; }
    int stepUpMinutes() { return stepUpMinutes; }
    int maxSessions() { return maxSessions; }
    int maxFailedAttempts() { return maxFailedAttempts; }
    int lockMinutes() { return lockMinutes; }

    /** 허용 목록이 비어 있으면 IP로는 막지 않는다. 그래도 비밀번호와 2단계 인증은 그대로 남는다. */
    boolean ipAllowed(String ip) {
        return allowed.isEmpty() || allowed.stream().anyMatch(rule -> rule.matches(ip));
    }

    boolean restrictsIp() { return !allowed.isEmpty(); }

    /** 계정별 허용 목록. 비어 있으면 그 계정에는 추가 제한이 없다는 뜻이다. */
    static boolean ipAllowedBy(String rules, String ip) {
        List<IpRule> parsed = IpRule.parse(rules);
        return parsed.isEmpty() || parsed.stream().anyMatch(rule -> rule.matches(ip));
    }

    /** 잘못된 값이 조용히 "제한 없음"이 되지 않게, 저장 전에 같은 파서로 검증한다. */
    static String validateIpRules(String rules) {
        String cleaned = rules == null ? "" : rules.trim();
        try {
            IpRule.parse(cleaned);
        } catch (IllegalArgumentException e) {
            throw new AppException("INVALID_IP_RULE", "IP 형식을 확인해 주세요. 예: 203.0.113.4, 10.0.0.0/8");
        }
        return cleaned;
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
                    throw new IllegalArgumentException("허용 IP 항목이 올바르지 않습니다: " + value, e);
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
    private final SystemAuditService audit;

    SystemConsoleGateFilter(SystemConsoleSettings settings, SystemAuditService audit) {
        this.settings = settings;
        this.audit = audit;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        if (!req.getRequestURI().startsWith("/api/system/")) {
            chain.doFilter(req, res);
            return;
        }
        if (!settings.enabled()) {
            audit.record(null, "", SystemAuditService.ACCESS_DENIED, null, null, "CONSOLE_DISABLED",
                    ClientIps.of(req), false);
            SecurityConfig.writeError(res, 404, "NOT_FOUND", "요청하신 경로를 찾을 수 없습니다.");
            return;
        }
        String ip = ClientIps.of(req);
        if (!settings.ipAllowed(ip)) {
            log.warn("System console request rejected by IP allowlist");
            audit.record(null, "", SystemAuditService.ACCESS_DENIED, null, null, "GLOBAL_IP_NOT_ALLOWED", ip, false);
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

    static String userAgent(HttpServletRequest req) {
        String value = req.getHeader("User-Agent");
        return value == null ? "" : value;
    }

    /** 원문을 저장하지 않는다. 기기가 바뀌었는지만 알면 되고, UA는 지문에 가까운 값이다. */
    static String userAgentHash(String userAgent) {
        return Hashes.sha256Hex(userAgent);
    }

    /** 사람이 세션 목록에서 자기 기기를 알아볼 만큼만 남긴다. */
    static String userAgentLabel(String userAgent) {
        String ua = userAgent == null ? "" : userAgent;
        String os = ua.contains("Android") ? "Android"
                : ua.contains("iPhone") || ua.contains("iPad") ? "iOS"
                : ua.contains("Mac OS X") ? "macOS"
                : ua.contains("Windows") ? "Windows"
                : ua.contains("Linux") ? "Linux" : "기타";
        String browser = ua.contains("Edg/") ? "Edge"
                : ua.contains("SamsungBrowser") ? "Samsung Internet"
                : ua.contains("Chrome/") ? "Chrome"
                : ua.contains("Safari/") ? "Safari"
                : ua.contains("Firefox/") ? "Firefox" : "기타";
        return os + " · " + browser;
    }
}

final class Hashes {
    private Hashes() {
    }

    static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
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
                return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
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
 * 운영자 로그인 시도 제한(IP·계정 단위, 프로세스 안).
 *
 * 계정 잠금({@link OperatorSecurity#lockedUntil})과 역할이 다르다. 이쪽은 초 단위로 쏟아지는 시도를
 * 값싸게 끊는 문지기이고, 저쪽은 재시작해도 남아야 하는 계정 상태다. 둘 다 있는 이유는, 인메모리
 * 제한만 두면 재시작 한 번으로 초기화되고 DB 잠금만 두면 모든 시도가 DB를 때리기 때문이다.
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

/**
 * 감사 로그 한 행을 쓰는 자리. 사슬의 마지막 고리를 읽고 새 고리를 얹는다.
 *
 * 별도 트랜잭션인 이유는, 실패한 요청의 기록이 그 요청과 함께 롤백되면 안 되기 때문이다.
 * 남겨야 할 것이 바로 그 실패다.
 */
@Service
class SystemAuditWriter {
    private final SystemAuditLogRepository logs;

    SystemAuditWriter(SystemAuditLogRepository logs) {
        this.logs = logs;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    SystemAuditLog append(SystemAuditLog row) {
        String prev = logs.findTopByOrderByIdDesc().map(last -> last.hash == null ? "" : last.hash).orElse("");
        row.prevHash = prev;
        row.hash = Hashes.sha256Hex(prev + "|" + SystemAuditService.payload(row));
        return logs.save(row);
    }
}

/** 운영자 콘솔에서 일어난 일을 남긴다. 고객 개인정보는 한 칸도 들어가지 않는다. */
@Service
class SystemAuditService {
    static final String LOGIN = "LOGIN", LOGIN_FAILED = "LOGIN_FAILED", LOGIN_BLOCKED = "LOGIN_BLOCKED",
            TOTP_ENROLL_START = "TOTP_ENROLL_START", TOTP_ENROLLED = "TOTP_ENROLLED", TOTP_RESET = "TOTP_RESET",
            BACKUP_CODE_USED = "BACKUP_CODE_USED", BACKUP_CODES_ISSUED = "BACKUP_CODES_ISSUED",
            STEP_UP = "STEP_UP", STEP_UP_FAILED = "STEP_UP_FAILED", LOGOUT = "LOGOUT",
            SESSION_REVOKED = "SESSION_REVOKED", PASSWORD_CHANGED = "PASSWORD_CHANGED",
            OPERATOR_CREATED = "OPERATOR_CREATED", OPERATOR_UPDATED = "OPERATOR_UPDATED",
            PLAN_CHANGED = "PLAN_CHANGED", STORE_STATUS_CHANGED = "STORE_STATUS_CHANGED",
            ACCESS_DENIED = "ACCESS_DENIED", AUDIT_EXPORTED = "AUDIT_EXPORTED";

    /** 사슬을 한 줄씩 이어 붙이는 동안 다른 요청이 끼어들면 두 행이 같은 앞고리를 물게 된다. */
    private final Object chainLock = new Object();
    private final SystemAuditWriter writer;
    private final SystemAuditLogRepository logs;
    private final Clock clock;

    SystemAuditService(SystemAuditWriter writer, SystemAuditLogRepository logs, Clock clock) {
        this.writer = writer;
        this.logs = logs;
        this.clock = clock;
    }

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
        // DB가 나노초를 그대로 담지 못한다. 저장 뒤 값과 해시를 계산한 값이 달라지면 사슬이
        // 기록 직후부터 깨진 것처럼 보인다. 그래서 해시를 만들기 전에 정밀도를 맞춰 둔다.
        row.createdAt = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        // 커밋까지 잠금 안에서 끝나야 사슬이 갈라지지 않는다(단일 인스턴스 전제).
        synchronized (chainLock) {
            writer.append(row);
        }
    }

    /** 해시에 들어가는 값. 순서와 표현이 바뀌면 과거 행의 검증이 통째로 깨지므로 건드리지 않는다. */
    static String payload(SystemAuditLog row) {
        return String.join("|", String.valueOf(row.actorAdminId), row.actorEmail, row.action,
                row.targetType == null ? "" : row.targetType, String.valueOf(row.targetId),
                row.detail == null ? "" : row.detail, row.ip == null ? "" : row.ip,
                String.valueOf(row.succeeded), String.valueOf(row.createdAt));
    }

    /** 어디서 끊겼는지까지 돌려준다. "무결하다/아니다"만으로는 손쓸 데를 못 찾는다. */
    record Integrity(boolean intact, long checked, Long firstBrokenId, Instant firstBrokenAt) {
    }

    Integrity verify() {
        String prev = "";
        long checked = 0;
        for (SystemAuditLog row : logs.findAllByOrderByIdAsc()) {
            checked++;
            if (row.hash == null) { // 사슬을 넣기 전에 쌓인 행. 여기서 다시 시작한다.
                prev = "";
                continue;
            }
            String expected = Hashes.sha256Hex(prev + "|" + payload(row));
            if (!expected.equals(row.hash) || (row.prevHash != null && !row.prevHash.equals(prev)))
                return new Integrity(false, checked, row.id, row.createdAt);
            prev = row.hash;
        }
        return new Integrity(true, checked, null, null);
    }

    static String csv(List<SystemAuditLog> rows) {
        StringBuilder out = new StringBuilder("시각,계정,행위,대상,대상ID,내용,IP,성공\n");
        for (SystemAuditLog row : rows)
            out.append(cell(String.valueOf(row.createdAt))).append(',').append(cell(row.actorEmail)).append(',')
                    .append(cell(row.action)).append(',').append(cell(row.targetType)).append(',')
                    .append(cell(row.targetId == null ? "" : String.valueOf(row.targetId))).append(',')
                    .append(cell(row.detail)).append(',').append(cell(row.ip)).append(',')
                    .append(row.succeeded ? "Y" : "N").append('\n');
        return out.toString();
    }

    private static String cell(String value) {
        String v = value == null ? "" : value;
        // 스프레드시트가 =, +, -, @로 시작하는 칸을 수식으로 읽는다. 감사 로그를 여는 것만으로
        // 무언가 실행되는 일이 없게 앞에 작은따옴표를 붙인다.
        if (!v.isEmpty() && "=+-@".indexOf(v.charAt(0)) >= 0) v = "'" + v;
        return '"' + v.replace("\"", "\"\"") + '"';
    }

    private static String trim(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}

/**
 * 운영자 계정의 보안 상태를 읽고 바꾼다.
 *
 * 행이 없는 SYSTEM_ADMIN에게는 만들어 준다. 등급은 최소 권한으로 정한다: 활성 OWNER가 하나도
 * 없으면 그 계정이 OWNER(첫 운영자), 이미 있으면 VIEWER다. 콘솔 밖에서 만들어진 운영자 계정이
 * 자동으로 최고 권한을 얻지 않게 하기 위해서다.
 */
@Service
class OperatorSecurityService {
    private final OperatorSecurityRepository securities;
    private final SystemConsoleSettings settings;
    private final Clock clock;

    OperatorSecurityService(OperatorSecurityRepository securities, SystemConsoleSettings settings, Clock clock) {
        this.securities = securities;
        this.settings = settings;
        this.clock = clock;
    }

    @Transactional
    OperatorSecurity ensure(AdminUser admin) {
        return securities.findByAdminId(admin.id).orElseGet(() -> {
            boolean firstOwner = securities.countByConsoleRoleAndDisabledFalse(ConsoleRole.OWNER) == 0;
            return create(admin, firstOwner ? ConsoleRole.OWNER : ConsoleRole.VIEWER, clock.instant());
        });
    }

    @Transactional
    OperatorSecurity create(AdminUser admin, ConsoleRole role, Instant passwordChangedAt) {
        OperatorSecurity security = new OperatorSecurity();
        security.admin = admin;
        security.consoleRole = role;
        security.disabled = false;
        security.failedAttempts = 0;
        security.passwordChangedAt = passwordChangedAt;
        security.createdAt = clock.instant();
        security.updatedAt = security.createdAt;
        return securities.save(security);
    }

    /** 잠겨 있으면 남은 시간과 함께 거절한다. 비밀번호가 맞은 뒤에만 부른다(계정 존재 노출 방지). */
    void requireUsable(OperatorSecurity security) {
        Instant now = clock.instant();
        if (security.disabled)
            throw new AppException("ACCOUNT_DISABLED", "사용이 중지된 계정입니다. 다른 운영자에게 문의해 주세요.",
                    HttpStatus.FORBIDDEN);
        if (security.lockedUntil != null && security.lockedUntil.isAfter(now)) {
            long minutes = Math.max(1, ChronoUnit.MINUTES.between(now, security.lockedUntil) + 1);
            throw new AppException("ACCOUNT_LOCKED", minutes + "분 뒤에 다시 시도해 주세요.", HttpStatus.LOCKED);
        }
    }

    @Transactional
    void recordFailure(OperatorSecurity security) {
        Instant now = clock.instant();
        security.failedAttempts = security.failedAttempts + 1;
        if (security.failedAttempts >= settings.maxFailedAttempts()) {
            security.lockedUntil = now.plusSeconds(settings.lockMinutes() * 60L);
            security.failedAttempts = 0;
        }
        security.updatedAt = now;
        securities.save(security);
    }

    @Transactional
    void recordSuccess(OperatorSecurity security, String ip) {
        Instant now = clock.instant();
        security.failedAttempts = 0;
        security.lockedUntil = null;
        security.lastLoginAt = now;
        security.lastLoginIp = ip;
        security.updatedAt = now;
        securities.save(security);
    }

    boolean mustChangePassword(OperatorSecurity security) {
        return security.passwordChangedAt == null;
    }
}

/**
 * 콘솔 세션.
 *
 * JWT만으로는 로그아웃도 강제 종료도 만료를 기다려야 한다. 그래서 토큰이 세션 행을 가리키고,
 * 요청마다 그 행을 본다. 유휴 제한(기본 15분)과 절대 만료(토큰 수명)를 둘 다 두는 이유는,
 * 자리를 비운 화면과 오래 살아 있는 토큰이 서로 다른 위험이기 때문이다.
 */
@Service
class OperatorSessionService {
    private final OperatorSessionRepository sessions;
    private final SystemConsoleSettings settings;
    private final SystemAuditService audit;
    private final JwtService jwt;
    private final Clock clock;

    OperatorSessionService(OperatorSessionRepository sessions, SystemConsoleSettings settings,
            SystemAuditService audit, JwtService jwt, Clock clock) {
        this.sessions = sessions;
        this.settings = settings;
        this.audit = audit;
        this.jwt = jwt;
        this.clock = clock;
    }

    record Issued(OperatorSession session, String accessToken) {
    }

    @Transactional
    Issued open(AdminUser admin, String ip, String userAgent) {
        Instant now = clock.instant();
        // 동시에 살아 있는 세션 수를 묶는다. 넘치면 가장 오래된 것부터 닫는다.
        List<OperatorSession> active = sessions.findByAdminIdAndRevokedAtIsNullOrderByCreatedAtAsc(admin.id).stream()
                .filter(s -> s.absoluteExpiresAt.isAfter(now)).toList();
        for (int i = 0; i <= active.size() - settings.maxSessions(); i++) revoke(active.get(i), "SESSION_LIMIT");

        OperatorSession session = new OperatorSession();
        session.tokenId = Tokens.random();
        session.admin = admin;
        session.createdAt = now;
        session.lastSeenAt = now;
        session.absoluteExpiresAt = now.plusSeconds(jwt.operatorTtlSeconds());
        session.stepUpAt = now; // 방금 2단계 인증을 통과했다.
        session.ip = ip;
        session.userAgentHash = ClientIps.userAgentHash(userAgent);
        session.userAgentLabel = ClientIps.userAgentLabel(userAgent);
        sessions.save(session);
        return new Issued(session, jwt.issueOperator(admin, session.tokenId));
    }

    /**
     * 요청마다 부르는 검사. 끝난 세션은 여기서 닫고 401을 돌려준다.
     *
     * 기기가 바뀌면(UA 해시 불일치) 세션을 닫는다. 토큰을 복사해 다른 기기에서 쓰는 가장 흔한
     * 모양이 이것이고, 정상 사용자에게는 거의 일어나지 않는다.
     *
     * 트랜잭션을 걸지 않는다. 여기서 하는 일은 "닫고 거절하기"인데, 한 트랜잭션에 묶이면 거절
     * 예외가 방금 한 폐기까지 되돌린다. 그러면 같은 토큰으로 계속 두드릴 수 있다.
     */
    OperatorSession validate(String tokenId, Long adminId, String ip, String userAgent) {
        if (tokenId == null || tokenId.isBlank()) throw expired("세션이 종료되었습니다. 다시 로그인해 주세요.");
        OperatorSession session = sessions.findByTokenId(tokenId)
                .orElseThrow(() -> expired("세션이 종료되었습니다. 다시 로그인해 주세요."));
        Instant now = clock.instant();
        // 토큰이 가리키는 사람과 세션의 주인은 같아야 한다. 지금은 발급 시점에 둘이 함께 묶이지만,
        // 나중에 토큰 발급 경로가 하나 늘어날 때 이 한 줄이 없으면 남의 세션에 올라탈 수 있다.
        if (adminId == null || !session.admin.id.equals(adminId))
            throw expired("세션이 종료되었습니다. 다시 로그인해 주세요.");
        if (session.revokedAt != null) throw expired("종료된 세션입니다. 다시 로그인해 주세요.");
        if (!session.absoluteExpiresAt.isAfter(now)) {
            revoke(session, "EXPIRED");
            throw expired("세션 시간이 지났습니다. 다시 로그인해 주세요.");
        }
        if (session.lastSeenAt.plusSeconds(settings.idleMinutes() * 60L).isBefore(now)) {
            revoke(session, "IDLE_TIMEOUT");
            throw expired(settings.idleMinutes() + "분 이상 조작이 없어 로그아웃했습니다.");
        }
        if (!ClientIps.userAgentHash(userAgent).equals(session.userAgentHash)) {
            revoke(session, "DEVICE_MISMATCH");
            audit.record(session.admin.id, session.admin.email, SystemAuditService.SESSION_REVOKED, "SESSION",
                    session.id, "DEVICE_MISMATCH", ip, false);
            throw expired("다른 기기에서 쓰인 세션입니다. 다시 로그인해 주세요.");
        }
        // 매 요청 UPDATE는 낭비다. 유휴 판정에 필요한 만큼(30초)만 갱신한다.
        if (session.lastSeenAt.plusSeconds(30).isBefore(now)) {
            session.lastSeenAt = now;
            session.ip = ip;
            sessions.save(session);
        }
        return session;
    }

    @Transactional
    void markStepUp(OperatorSession session) {
        session.stepUpAt = clock.instant();
        sessions.save(session);
    }

    boolean stepUpFresh(OperatorSession session) {
        return session.stepUpAt != null
                && session.stepUpAt.plusSeconds(settings.stepUpMinutes() * 60L).isAfter(clock.instant());
    }

    @Transactional
    void revoke(OperatorSession session, String reason) {
        if (session.revokedAt != null) return;
        session.revokedAt = clock.instant();
        session.revokedReason = reason;
        sessions.save(session);
    }

    @Transactional
    int revokeAllOf(Long adminId, String reason, Long exceptSessionId) {
        int closed = 0;
        for (OperatorSession session : sessions.findByAdminIdAndRevokedAtIsNullOrderByCreatedAtAsc(adminId)) {
            if (exceptSessionId != null && exceptSessionId.equals(session.id)) continue;
            revoke(session, reason);
            closed++;
        }
        return closed;
    }

    List<OperatorSession> of(Long adminId) {
        return sessions.findByAdminIdOrderByCreatedAtDesc(adminId);
    }

    List<OperatorSession> activeAll() {
        return sessions.findByRevokedAtIsNullAndAbsoluteExpiresAtAfterOrderByLastSeenAtDesc(clock.instant());
    }

    long activeCount() {
        return sessions.countByRevokedAtIsNullAndAbsoluteExpiresAtAfter(clock.instant());
    }

    Optional<OperatorSession> byId(Long id) {
        return sessions.findById(id);
    }

    /** 끝난 세션 행을 30일까지만 둔다. 무슨 일이 있었는지는 감사 로그가 따로 들고 있다. */
    @Scheduled(cron = "0 10 4 * * *", zone = "Asia/Seoul")
    @Transactional
    public void purgeExpired() {
        sessions.deleteByAbsoluteExpiresAtBefore(clock.instant().minus(30, ChronoUnit.DAYS));
    }

    private static AppException expired(String message) {
        return new AppException("SESSION_EXPIRED", message, HttpStatus.UNAUTHORIZED);
    }
}

/**
 * 2단계 인증 복구 코드.
 *
 * 폰을 잃어버린 운영자를 위한 유일한 자력 복구 수단이다. 이게 없으면 다른 OWNER가 초기화해 주는
 * 길뿐이고, 운영자가 한 명인 서비스에서는 그 길이 없다.
 */
@Service
class OperatorBackupCodeService {
    static final int COUNT = 10;
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // 헷갈리는 0/O/1/I 제외
    private final OperatorBackupCodeRepository codes;
    private final PasswordEncoder encoder;
    private final SecureRandom random;
    private final Clock clock;

    OperatorBackupCodeService(OperatorBackupCodeRepository codes, PasswordEncoder encoder, SecureRandom random,
            Clock clock) {
        this.codes = codes;
        this.encoder = encoder;
        this.random = random;
        this.clock = clock;
    }

    /** 새로 만들면 예전 코드는 전부 버린다. 화면은 이때 한 번만 코드를 보여 준다. */
    @Transactional
    List<String> regenerate(AdminUser admin) {
        codes.deleteByAdminId(admin.id);
        List<String> issued = new ArrayList<>();
        for (int i = 0; i < COUNT; i++) {
            String code = block() + "-" + block();
            issued.add(code);
            OperatorBackupCode row = new OperatorBackupCode();
            row.admin = admin;
            row.codeHash = encoder.encode(code);
            row.createdAt = clock.instant();
            codes.save(row);
        }
        return issued;
    }

    /** 맞으면 그 코드를 소모한다. 한 번 쓴 코드는 다시 통하지 않는다. */
    @Transactional
    boolean consume(Long adminId, String candidate) {
        String normalized = normalize(candidate);
        if (normalized.isEmpty()) return false;
        for (OperatorBackupCode row : codes.findUnusedForUpdate(adminId))
            if (encoder.matches(normalized, row.codeHash)) {
                row.usedAt = clock.instant();
                codes.save(row);
                return true;
            }
        return false;
    }

    long remaining(Long adminId) {
        return codes.countByAdminIdAndUsedAtIsNull(adminId);
    }

    @Transactional
    void clear(Long adminId) {
        codes.deleteByAdminId(adminId);
    }

    /** 사람이 옮겨 적는 값이라 대소문자와 하이픈 유무를 가리지 않는다. */
    static String normalize(String value) {
        if (value == null) return "";
        String cleaned = value.trim().toUpperCase(Locale.ROOT).replace(" ", "").replace("-", "");
        return cleaned.length() == 8 ? cleaned.substring(0, 4) + "-" + cleaned.substring(4) : "";
    }

    private String block() {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < 4; i++) out.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        return out.toString();
    }
}

/**
 * 요청 한 건의 운영자 문맥. 컨트롤러는 이걸 받아 권한과 재인증만 확인하면 된다.
 */
record OperatorContext(AdminUser admin, OperatorSecurity security, OperatorSession session) {
    ConsoleRole role() {
        return security.consoleRole;
    }
}

/**
 * 요청마다 계정과 세션을 다시 본다.
 *
 * 토큰 안의 값만 믿으면, 권한을 회수하거나 계정을 중지하거나 로그아웃해도 토큰이 만료될 때까지
 * 콘솔이 열려 있다. 운영자 수가 적은 서비스라 조회 몇 번이 비싸지 않다.
 */
@Service
class SystemConsoleGuard {
    private final AdminUserRepository admins;
    private final OperatorSecurityService securities;
    private final OperatorSessionService sessions;
    private final SystemAuditService audit;

    SystemConsoleGuard(AdminUserRepository admins, OperatorSecurityService securities,
            OperatorSessionService sessions, SystemAuditService audit) {
        this.admins = admins;
        this.securities = securities;
        this.sessions = sessions;
        this.audit = audit;
    }

    /** 일반 경로. 임시 비밀번호를 아직 바꾸지 않았으면 여기서 막힌다. */
    OperatorContext enter(Authentication auth, HttpServletRequest req) {
        OperatorContext ctx = enterAllowingPasswordChange(auth, req);
        if (securities.mustChangePassword(ctx.security()))
            throw new AppException("PASSWORD_CHANGE_REQUIRED", "임시 비밀번호를 먼저 변경해 주세요.", HttpStatus.FORBIDDEN);
        return ctx;
    }

    /** 비밀번호 변경과 내 정보 조회만 이 문으로 들어온다. */
    OperatorContext enterAllowingPasswordChange(Authentication auth, HttpServletRequest req) {
        Long adminId = auth == null ? null : (Long) auth.getPrincipal();
        JwtService.Claims claims = auth != null && auth.getCredentials() instanceof JwtService.Claims c ? c : null;
        AdminUser admin = admins.findById(adminId == null ? -1L : adminId)
                .filter(a -> a.role == AdminRole.SYSTEM_ADMIN)
                .orElseThrow(() -> new AppException("FORBIDDEN", "접근 권한이 없습니다.", HttpStatus.FORBIDDEN));
        OperatorSecurity security = securities.ensure(admin);
        if (security.disabled)
            throw new AppException("ACCOUNT_DISABLED", "사용이 중지된 계정입니다.", HttpStatus.FORBIDDEN);
        String ip = ClientIps.of(req);
        if (!SystemConsoleSettings.ipAllowedBy(security.allowedIps, ip))
            throw new AppException("NOT_FOUND", "요청하신 경로를 찾을 수 없습니다.", HttpStatus.NOT_FOUND);
        OperatorSession session = sessions.validate(claims == null ? null : claims.tokenId(), admin.id, ip,
                ClientIps.userAgent(req));
        return new OperatorContext(admin, security, session);
    }

    /** 권한 등급 확인. 모자라면 거절하고 그 시도 자체를 기록한다. */
    void require(OperatorContext ctx, ConsoleRole minimum, HttpServletRequest req, String what) {
        if (ctx.role().ordinal() < minimum.ordinal()) {
            audit.record(ctx.admin().id, ctx.admin().email, SystemAuditService.ACCESS_DENIED, null, null,
                    what + " (" + ctx.role() + " < " + minimum + ")", ClientIps.of(req), false);
            throw new AppException("FORBIDDEN", "이 작업을 할 수 있는 권한이 없습니다.", HttpStatus.FORBIDDEN);
        }
    }

    /**
     * 위험한 조작 앞의 재인증.
     *
     * 로그인한 채로 자리를 비운 화면, 빌려준 노트북, 열어 둔 탭이 곧바로 요금제 변경 권한이 되지
     * 않게 한다. 로그인 직후에는 이미 통과한 상태라 바로 이어서 할 수 있다.
     */
    void requireStepUp(OperatorContext ctx) {
        if (!sessions.stepUpFresh(ctx.session()))
            throw new AppException("STEP_UP_REQUIRED", "보안을 위해 인증 앱 코드를 한 번 더 확인합니다.",
                    HttpStatus.FORBIDDEN);
    }
}

/**
 * 운영자 로그인, 2단계 인증 등록, 재인증, 비밀번호 변경.
 *
 * 실패 메시지는 무엇이 틀렸는지 알려 주지 않는다. "그 이메일은 운영자가 아니다"를 알려 주면
 * 공격자가 계정 목록을 좁힐 수 있기 때문에, 없는 계정·틀린 비밀번호·운영자가 아닌 계정이 모두
 * 같은 답을 받는다. 계정 잠금과 중지는 비밀번호가 맞은 뒤에만 알려 준다.
 */
@Service
class OperatorAuthService {
    record Session(String accessToken, int expiresInSeconds, String email, String name, ConsoleRole consoleRole,
            boolean mustChangePassword, long backupCodesRemaining) {
    }

    record Enrollment(String enrollmentToken, String secret, String otpauthUrl, String qrImage) {
    }

    record Outcome(Session session, Enrollment enrollment, List<String> backupCodes) {
    }

    private final AdminUserRepository admins;
    private final AdminTotpRepository credentials;
    private final PasswordEncoder encoder;
    private final TotpService totp;
    private final JwtService jwt;
    private final OperatorLoginLimiter limiter;
    private final SystemAuditService audit;
    private final SystemConsoleSettings settings;
    private final OperatorSecurityService securityService;
    private final OperatorSecurityRepository securities;
    private final OperatorSessionService sessions;
    private final OperatorBackupCodeService backupCodes;
    private final PhoneService secrets;
    private final Clock clock;

    OperatorAuthService(AdminUserRepository admins, AdminTotpRepository credentials, PasswordEncoder encoder,
            TotpService totp, JwtService jwt, OperatorLoginLimiter limiter, SystemAuditService audit,
            SystemConsoleSettings settings, OperatorSecurityService securityService,
            OperatorSecurityRepository securities, OperatorSessionService sessions,
            OperatorBackupCodeService backupCodes, PhoneService secrets, Clock clock) {
        this.admins = admins;
        this.credentials = credentials;
        this.encoder = encoder;
        this.totp = totp;
        this.jwt = jwt;
        this.limiter = limiter;
        this.audit = audit;
        this.settings = settings;
        this.securityService = securityService;
        this.securities = securities;
        this.sessions = sessions;
        this.backupCodes = backupCodes;
        this.secrets = secrets;
        this.clock = clock;
    }

    /**
     * 트랜잭션에 묶지 않는다. 실패로 끝나는 로그인이 남겨야 할 것(실패 횟수, 계정 잠금, 복구 코드
     * 소모)이 거절 예외와 함께 되돌아가면, 아무리 두드려도 잠기지 않는 문이 된다. 각 단계는 자기
     * 트랜잭션에서 스스로 커밋한다.
     */
    Outcome login(String rawEmail, String password, String code, String backupCode, String ip, String userAgent) {
        String email = Inputs.lower(rawEmail);
        limiter.check(ip, email);
        Optional<AdminUser> found = admins.findByEmail(email)
                .filter(a -> a.role == AdminRole.SYSTEM_ADMIN)
                .filter(a -> encoder.matches(password, a.passwordHash));
        if (found.isEmpty()) {
            limiter.failed(ip, email);
            admins.findByEmail(email).filter(a -> a.role == AdminRole.SYSTEM_ADMIN)
                    .ifPresent(a -> securityService.recordFailure(securityService.ensure(a)));
            audit.record(null, email, SystemAuditService.LOGIN_FAILED, null, null, "password", ip, false);
            throw invalid();
        }
        AdminUser operator = found.get();
        OperatorSecurity security = securityService.ensure(operator);
        try {
            securityService.requireUsable(security);
        } catch (AppException e) {
            audit.record(operator.id, operator.email, SystemAuditService.LOGIN_BLOCKED, null, null, e.code, ip, false);
            throw e;
        }
        if (!SystemConsoleSettings.ipAllowedBy(security.allowedIps, ip)) {
            audit.record(operator.id, operator.email, SystemAuditService.LOGIN_BLOCKED, null, null, "IP_NOT_ALLOWED",
                    ip, false);
            throw new AppException("NOT_FOUND", "요청하신 경로를 찾을 수 없습니다.", HttpStatus.NOT_FOUND);
        }

        AdminTotpCredential credential = credentials.findByAdminId(operator.id).orElse(null);
        // 아직 2단계 인증이 없는 운영자는 등록부터 한다. 비밀번호만으로 콘솔을 여는 경로는 없다.
        if (credential == null || !credential.confirmed) {
            Enrollment enrollment = startEnrollment(operator, credential);
            audit.record(operator.id, operator.email, SystemAuditService.TOTP_ENROLL_START, null, null, null, ip, true);
            return new Outcome(null, enrollment, null);
        }

        boolean usedBackupCode = false;
        if (backupCode != null && !backupCode.isBlank()) {
            if (!backupCodes.consume(operator.id, backupCode)) {
                limiter.failed(ip, email);
                securityService.recordFailure(security);
                audit.record(operator.id, operator.email, SystemAuditService.LOGIN_FAILED, null, null, "backup-code",
                        ip, false);
                throw new AppException("BACKUP_CODE_INVALID", "복구 코드가 올바르지 않습니다.", HttpStatus.UNAUTHORIZED);
            }
            usedBackupCode = true;
            audit.record(operator.id, operator.email, SystemAuditService.BACKUP_CODE_USED, null, null,
                    "남은 코드 " + backupCodes.remaining(operator.id) + "개", ip, true);
        } else {
            if (code == null || code.isBlank()) {
                limiter.failed(ip, email);
                throw new AppException("TOTP_REQUIRED", "인증 앱의 6자리 코드를 입력해 주세요.", HttpStatus.UNAUTHORIZED);
            }
            Optional<Long> step = totp.verify(secrets.decrypt(credential.secretEncrypted), code, clock.instant(),
                    credential.lastUsedStep);
            if (step.isEmpty()) {
                limiter.failed(ip, email);
                securityService.recordFailure(security);
                audit.record(operator.id, operator.email, SystemAuditService.LOGIN_FAILED, null, null, "totp", ip,
                        false);
                throw new AppException("TOTP_INVALID", "인증 코드가 올바르지 않습니다.", HttpStatus.UNAUTHORIZED);
            }
            if (credentials.advanceLastUsedStep(credential.id, credential.lastUsedStep, step.get()) != 1) {
                limiter.failed(ip, email);
                throw new AppException("TOTP_INVALID", "인증 코드가 올바르지 않습니다.", HttpStatus.UNAUTHORIZED);
            }
        }

        limiter.succeeded(ip, email);
        securityService.recordSuccess(security, ip);
        OperatorSessionService.Issued issued = sessions.open(operator, ip, userAgent);
        audit.record(operator.id, operator.email, SystemAuditService.LOGIN, "SESSION", issued.session().id,
                (usedBackupCode ? "복구 코드 · " : "") + ClientIps.userAgentLabel(userAgent), ip, true);
        return new Outcome(new Session(issued.accessToken(), jwt.operatorTtlSeconds(), operator.email, operator.name,
                security.consoleRole, securityService.mustChangePassword(security),
                backupCodes.remaining(operator.id)), null, null);
    }

    /**
     * 등록 확인. 임시 토큰과 코드가 모두 맞아야 비밀값이 확정된다.
     *
     * 코드를 한 번 맞혀 보게 하는 이유는, 인증 앱에 실제로 들어갔는지 확인하지 않고 확정하면
     * 다음 로그인에서 아무 코드도 맞지 않아 콘솔이 통째로 잠기기 때문이다. 확정과 동시에 복구
     * 코드를 발급한다. 폰을 잃어버렸을 때 쓸 수단을 등록 시점에 함께 주지 않으면 아무도 나중에
     * 만들어 두지 않는다.
     */
    @Transactional
    List<String> confirmEnrollment(String enrollmentToken, String code, String ip) {
        JwtService.Claims claims = jwt.verify(enrollmentToken == null ? "" : enrollmentToken);
        if (claims == null || !JwtService.SCOPE_ENROLL.equals(claims.scope()) || claims.role() != AdminRole.SYSTEM_ADMIN)
            throw new AppException("ENROLLMENT_EXPIRED", "등록 시간이 지났습니다. 다시 로그인해 주세요.", HttpStatus.UNAUTHORIZED);
        AdminUser operator = admins.findById(claims.adminId())
                .filter(a -> a.role == AdminRole.SYSTEM_ADMIN)
                .orElseThrow(OperatorAuthService::invalid);
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
        if (credentials.confirmAndAdvance(credential.id, credential.lastUsedStep, step.get(), clock.instant()) != 1)
            throw new AppException("TOTP_INVALID", "이미 사용했거나 올바르지 않은 인증 코드입니다.", HttpStatus.UNAUTHORIZED);
        audit.record(operator.id, operator.email, SystemAuditService.TOTP_ENROLLED, null, null, null, ip, true);
        List<String> codes = backupCodes.regenerate(operator);
        audit.record(operator.id, operator.email, SystemAuditService.BACKUP_CODES_ISSUED, null, null,
                OperatorBackupCodeService.COUNT + "개", ip, true);
        return codes;
    }

    /** 위험한 조작 앞의 재인증. 코드가 맞으면 세션에 "방금 확인했다"는 표시를 남긴다. */
    @Transactional
    void stepUp(OperatorContext ctx, String code, String ip) {
        limiter.check(ip, ctx.admin().email);
        AdminTotpCredential credential = credentials.findByAdminId(ctx.admin().id)
                .filter(c -> c.confirmed)
                .orElseThrow(() -> new AppException("TOTP_REQUIRED", "2단계 인증을 먼저 등록해 주세요.",
                        HttpStatus.UNAUTHORIZED));
        Optional<Long> step = totp.verify(secrets.decrypt(credential.secretEncrypted), code, clock.instant(),
                credential.lastUsedStep);
        if (step.isEmpty()) {
            limiter.failed(ip, ctx.admin().email);
            audit.record(ctx.admin().id, ctx.admin().email, SystemAuditService.STEP_UP_FAILED, null, null, null, ip,
                    false);
            throw new AppException("TOTP_INVALID", "인증 코드가 올바르지 않습니다.", HttpStatus.UNAUTHORIZED);
        }
        if (credentials.advanceLastUsedStep(credential.id, credential.lastUsedStep, step.get()) != 1) {
            limiter.failed(ip, ctx.admin().email);
            throw new AppException("TOTP_INVALID", "이미 사용했거나 올바르지 않은 인증 코드입니다.", HttpStatus.UNAUTHORIZED);
        }
        limiter.succeeded(ip, ctx.admin().email);
        sessions.markStepUp(ctx.session());
        audit.record(ctx.admin().id, ctx.admin().email, SystemAuditService.STEP_UP, null, null, null, ip, true);
    }

    @Transactional
    void logout(OperatorContext ctx, String ip) {
        sessions.revoke(ctx.session(), "LOGOUT");
        audit.record(ctx.admin().id, ctx.admin().email, SystemAuditService.LOGOUT, "SESSION", ctx.session().id, null,
                ip, true);
    }

    /**
     * 비밀번호 변경. 현재 비밀번호를 다시 묻고, 바꾼 뒤에는 다른 세션을 전부 닫는다.
     *
     * 비밀번호를 바꾸는 흔한 이유가 "누군가 알아낸 것 같다"인데, 기존 세션을 남겨 두면 그 사람이
     * 계속 들어와 있다.
     */
    @Transactional
    void changePassword(OperatorContext ctx, String current, String next, String confirm, String ip) {
        if (!encoder.matches(current, ctx.admin().passwordHash))
            throw new AppException("AUTH_INVALID", "현재 비밀번호가 올바르지 않습니다.", HttpStatus.UNAUTHORIZED);
        operatorPassword(next, confirm, ctx.admin().email);
        if (encoder.matches(next, ctx.admin().passwordHash))
            throw new AppException("PASSWORD_REUSED", "지금 쓰고 있는 비밀번호와 다른 값을 입력해 주세요.");
        ctx.admin().passwordHash = encoder.encode(next);
        admins.save(ctx.admin());
        ctx.security().passwordChangedAt = clock.instant();
        ctx.security().updatedAt = clock.instant();
        securities.save(ctx.security());
        int closed = sessions.revokeAllOf(ctx.admin().id, "PASSWORD_CHANGED", ctx.session().id);
        audit.record(ctx.admin().id, ctx.admin().email, SystemAuditService.PASSWORD_CHANGED, null, null,
                "다른 세션 " + closed + "개 종료", ip, true);
    }

    /**
     * 운영자 비밀번호 규칙. 매장 관리자보다 길고 종류도 더 요구한다.
     * 이 계정 하나가 플랫폼 전체를 열기 때문이다.
     */
    static void operatorPassword(String password, String confirm, String email) {
        if (password == null || !password.equals(confirm))
            throw new AppException("PASSWORD_MISMATCH", "비밀번호가 일치하지 않습니다.");
        if (password.length() < 12 || !password.matches(".*[A-Za-z].*") || !password.matches(".*\\d.*")
                || !password.matches(".*[^A-Za-z0-9].*"))
            throw new AppException("WEAK_PASSWORD", "비밀번호는 영문·숫자·기호를 포함해 12자 이상이어야 합니다.");
        String local = email == null ? "" : email.split("@")[0];
        if (local.length() >= 4 && password.toLowerCase(Locale.ROOT).contains(local.toLowerCase(Locale.ROOT)))
            throw new AppException("WEAK_PASSWORD", "이메일과 겹치지 않는 비밀번호를 사용해 주세요.");
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
 * 운영자 계정 관리(OWNER 전용).
 *
 * 콘솔 밖(DB 직접 수정, 환경변수)에서 운영자를 늘리지 않아도 되게 한다. 그래야 누가 언제 누구에게
 * 무슨 권한을 줬는지가 기록으로 남는다.
 */
@Service
class OperatorDirectoryService {
    record Created(AdminUser admin, OperatorSecurity security) {
    }

    private final AdminUserRepository admins;
    private final OperatorSecurityRepository securities;
    private final OperatorSecurityService securityService;
    private final OperatorSessionService sessions;
    private final AdminTotpRepository credentials;
    private final OperatorBackupCodeService backupCodes;
    private final PasswordEncoder encoder;
    private final Clock clock;

    OperatorDirectoryService(AdminUserRepository admins, OperatorSecurityRepository securities,
            OperatorSecurityService securityService, OperatorSessionService sessions, AdminTotpRepository credentials,
            OperatorBackupCodeService backupCodes, PasswordEncoder encoder, Clock clock) {
        this.admins = admins;
        this.securities = securities;
        this.securityService = securityService;
        this.sessions = sessions;
        this.credentials = credentials;
        this.backupCodes = backupCodes;
        this.encoder = encoder;
        this.clock = clock;
    }

    List<OperatorSecurity> all() {
        return securities.findAllByOrderByIdAsc();
    }

    OperatorSecurity of(Long adminId) {
        return securities.findByAdminId(adminId)
                .orElseThrow(() -> new AppException("OPERATOR_NOT_FOUND", "운영자 계정을 찾을 수 없습니다.",
                        HttpStatus.NOT_FOUND));
    }

    boolean hasTotp(Long adminId) {
        return credentials.findByAdminId(adminId).map(c -> c.confirmed).orElse(false);
    }

    /**
     * 새 운영자. 비밀번호는 만든 사람이 정한 임시값이며, 본인이 바꾸기 전에는 조회 외에 아무것도
     * 하지 못한다. 만든 사람이 계속 알고 있는 비밀번호로 플랫폼이 열려 있으면 안 된다.
     */
    @Transactional
    Created create(String rawEmail, String name, String password, ConsoleRole role) {
        String email = Inputs.email(rawEmail);
        String operatorName = Inputs.required(name, "운영자 이름을 입력해 주세요.");
        OperatorAuthService.operatorPassword(password, password, email);
        if (admins.existsByEmail(email))
            throw new AppException("DUPLICATE_EMAIL", "이미 사용 중인 이메일입니다.");
        AdminUser admin = new AdminUser();
        admin.email = email;
        admin.passwordHash = encoder.encode(password);
        admin.name = operatorName;
        admin.role = AdminRole.SYSTEM_ADMIN;
        admin.createdAt = clock.instant();
        admins.save(admin);
        return new Created(admin, securityService.create(admin, role, null));
    }

    /**
     * 권한·중지·접속 IP 변경.
     *
     * 마지막 OWNER를 내리거나 중지하는 것은 막는다. 아무도 운영자 관리 화면에 들어가지 못하는
     * 상태가 되면 DB를 직접 고치는 수밖에 없어진다. 자기 자신의 권한을 내리는 것도 같은 이유로
     * 막는다(실수 한 번으로 스스로 잠기는 흔한 사고다).
     */
    @Transactional
    OperatorSecurity update(OperatorContext actor, Long targetAdminId, ConsoleRole role, Boolean disabled,
            String allowedIps) {
        OperatorSecurity target = of(targetAdminId);
        boolean self = actor.admin().id.equals(targetAdminId);
        if (self && role != null && role != ConsoleRole.OWNER)
            throw new AppException("INVALID_REQUEST", "자기 자신의 권한은 내릴 수 없습니다.");
        if (self && Boolean.TRUE.equals(disabled))
            throw new AppException("INVALID_REQUEST", "자기 자신의 계정은 중지할 수 없습니다.");
        boolean losingOwner = target.consoleRole == ConsoleRole.OWNER && !target.disabled
                && ((role != null && role != ConsoleRole.OWNER) || Boolean.TRUE.equals(disabled));
        if (losingOwner) securities.lockActiveOwners();
        if (losingOwner && securities.countByConsoleRoleAndDisabledFalse(ConsoleRole.OWNER) <= 1)
            throw new AppException("LAST_OWNER", "마지막 OWNER는 권한을 내리거나 중지할 수 없습니다.");
        if (role != null) target.consoleRole = role;
        if (disabled != null) target.disabled = disabled;
        if (allowedIps != null) target.allowedIps = SystemConsoleSettings.validateIpRules(allowedIps);
        target.updatedAt = clock.instant();
        securities.save(target);
        // 권한을 내렸거나 계정을 중지했으면 그 사람의 세션도 지금 닫는다.
        if (target.disabled || (role != null && role != ConsoleRole.OWNER))
            sessions.revokeAllOf(targetAdminId, "ACCOUNT_UPDATED", null);
        return target;
    }

    /** 2단계 인증 초기화. 다음 로그인에서 새 인증 앱을 등록하게 된다. */
    @Transactional
    void resetTotp(Long targetAdminId) {
        of(targetAdminId);
        credentials.findByAdminId(targetAdminId).ifPresent(credentials::delete);
        backupCodes.clear(targetAdminId);
        sessions.revokeAllOf(targetAdminId, "TOTP_RESET", null);
    }

    /** 계정 잠금 해제. 잠긴 사람이 15분을 기다리지 않아도 되게 한다. */
    @Transactional
    void unlock(Long targetAdminId) {
        OperatorSecurity target = of(targetAdminId);
        target.lockedUntil = null;
        target.failedAttempts = 0;
        target.updatedAt = clock.instant();
        securities.save(target);
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
    private final OperatorSecurityService securities;
    private final PasswordEncoder encoder;
    private final Clock clock;

    OperatorBootstrap(@Value("${app.system-console.operator-email:}") String email,
            @Value("${app.system-console.operator-password:}") String password, AdminUserRepository admins,
            OperatorSecurityService securities, PasswordEncoder encoder, Clock clock) {
        this.email = email;
        this.password = password;
        this.admins = admins;
        this.securities = securities;
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
        OperatorAuthService.operatorPassword(password, password, normalized);
        AdminUser operator = new AdminUser();
        operator.email = normalized;
        operator.passwordHash = encoder.encode(password);
        operator.name = "운영자";
        operator.role = AdminRole.SYSTEM_ADMIN;
        operator.createdAt = clock.instant();
        admins.save(operator);
        // 첫 운영자는 OWNER다. 비밀번호를 스스로 정한 계정이라 강제 변경은 걸지 않는다.
        securities.create(operator, ConsoleRole.OWNER, clock.instant());
        log.info("Created the system operator account; enroll two-factor authentication on first console login");
    }
}
