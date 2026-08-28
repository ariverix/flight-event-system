package ru.protectinfotrans.eca;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Фаза 3 (прогон апгрейда): rate limiting (token bucket, bucket4j) на брутфорс-логин и флуд
 * открытого ACARS-ингеста.
 *
 * <p><b>Защищаемые классы путей</b> (per client IP, {@link RateLimitProperties}):
 * <ul>
 *   <li>{@code AUTH} — {@code /api/v1/auth/login|refresh|logout} (брутфорс) и {@code /password}
 *       (PUT, P4-6: currentPassword — угадываемый секрет, тот же риск брутфорса, хоть эндпоинт и
 *       требует валидный JWT); {@code /api/v1/auth/me} и {@code /register} исключены (аутентифицированы,
 *       не brute-force поверхность, а {@code /me} часто опрашивается фронтом).</li>
 *   <li>{@code MESSAGES} — открытый {@code /api/v1/messages/**} (флуд). Потолок высокий (см.
 *       {@link RateLimitProperties}) — не режет штатный высокочастотный поток шлюза.</li>
 * </ul>
 *
 * <p><b>Порядок в цепочке:</b> добавляется в {@code SecurityConfig} ПЕРЕД
 * {@code JwtAuthenticationFilter}, т.е. ПОСЛЕ CorsFilter Spring Security — отказ 429 сохраняет
 * CORS-заголовки для браузерного клиента. Двойная регистрация (в servlet-цепочке + security-цепочке)
 * подавлена {@code FilterRegistrationBean.setEnabled(false)} в {@code SecurityConfig}.
 *
 * <p><b>Выключение:</b> при {@code app.ratelimit.enabled=false} (в тестах через surefire) —
 * прозрачный passthrough, чтобы не резать высоконагруженные интеграционные тесты ингеста/логина.
 * Фокусные тесты самой логики лимитера — юнит-уровня ({@code RateLimitFilterTest}).
 *
 * <p><b>Отклонение:</b> HTTP 429 в формате RFC 7807 (Problem Details) + заголовок {@code Retry-After};
 * метрика {@code eca.ratelimit.rejected{scope=auth|messages}} (Micrometer). Значения IP при
 * логировании санитизируются ({@link LogSanitizer}).
 */
@Component
@Slf4j
public class RateLimitFilter extends OncePerRequestFilter {

    private enum Scope { AUTH, MESSAGES, NONE }

    private final RateLimitProperties props;
    private final ObjectMapper objectMapper;
    private final Counter authRejected;
    private final Counter messagesRejected;

    /** Доверенные reverse-proxy (IP/CIDR) — только их XFF учитывается (см. {@link #clientIp}). */
    private final List<IpAddressMatcher> trustedProxies;

    // per-IP бакеты для каждого класса путей. Ограниченный LRU (access-order LinkedHashMap с
    // removeEldestEntry): ключ теоретически атакуем (rotation), поэтому карта не должна расти
    // безгранично — эвиктим наименее недавно использованный при достижении maxTrackedKeys.
    // synchronizedMap: computeIfAbsent атомарен, contention на hot-path незначителен (get+put).
    private final Map<String, Bucket> authBuckets;
    private final Map<String, Bucket> messagesBuckets;

    public RateLimitFilter(RateLimitProperties props, ObjectMapper objectMapper, MeterRegistry meterRegistry) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.authRejected = meterRegistry.counter("eca.ratelimit.rejected", "scope", "auth");
        this.messagesRejected = meterRegistry.counter("eca.ratelimit.rejected", "scope", "messages");
        this.trustedProxies = new ArrayList<>();
        for (String cidr : props.getTrustedProxies()) {
            if (cidr != null && !cidr.isBlank()) {
                this.trustedProxies.add(new IpAddressMatcher(cidr.trim()));
            }
        }
        this.authBuckets = boundedLru(props.getMaxTrackedKeys());
        this.messagesBuckets = boundedLru(props.getMaxTrackedKeys());
    }

    private static Map<String, Bucket> boundedLru(int maxKeys) {
        return Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Bucket> eldest) {
                return size() > maxKeys;
            }
        });
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // выключено (тесты) или preflight — прозрачный проход
        if (!props.isEnabled() || "OPTIONS".equalsIgnoreCase(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        Scope scope = resolveScope(request.getRequestURI());
        if (scope == Scope.NONE) {
            filterChain.doFilter(request, response);
            return;
        }

        String ip = clientIp(request);
        Bucket bucket = bucketFor(scope, ip);
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            filterChain.doFilter(request, response);
            return;
        }

        long retryAfterSeconds = Math.max(1, Duration.ofNanos(probe.getNanosToWaitForRefill()).toSeconds());
        (scope == Scope.AUTH ? authRejected : messagesRejected).increment();
        log.warn("Rate limit exceeded: scope={}, ip={}, retryAfter={}s",
                scope, LogSanitizer.sanitize(ip), retryAfterSeconds);
        writeTooManyRequests(response, scope, retryAfterSeconds);
    }

    private Scope resolveScope(String uri) {
        if (uri == null) {
            return Scope.NONE;
        }
        if (uri.startsWith("/api/v1/messages")) {
            return Scope.MESSAGES;
        }
        // неаутентифицированные auth-пути (брутфорс) + /password: хоть и требует валидный JWT,
        // сам currentPassword — угадываемый секрет (brute-force поверхность), поэтому лимитируем
        // так же, как login. /me и /register остаются исключены (не brute-force поверхность).
        if (uri.equals("/api/v1/auth/login")
                || uri.equals("/api/v1/auth/refresh")
                || uri.equals("/api/v1/auth/logout")
                || uri.equals("/api/v1/auth/password")) {
            return Scope.AUTH;
        }
        return Scope.NONE;
    }

    private Bucket bucketFor(Scope scope, String ip) {
        if (scope == Scope.AUTH) {
            return authBuckets.computeIfAbsent(ip, k -> newBucket(props.getAuth()));
        }
        return messagesBuckets.computeIfAbsent(ip, k -> newBucket(props.getMessages()));
    }

    private Bucket newBucket(RateLimitProperties.Limit limit) {
        Bandwidth bandwidth = Bandwidth.builder()
                .capacity(limit.getCapacity())
                .refillGreedy(limit.getCapacity(), Duration.ofSeconds(limit.getRefillPeriodSeconds()))
                .build();
        return Bucket.builder().addLimit(bandwidth).build();
    }

    /**
     * Client IP как ключ лимита. {@code X-Forwarded-For} учитывается ТОЛЬКО если непосредственный
     * отправитель ({@code remoteAddr}) — доверенный прокси (иначе клиент подставил бы произвольный
     * XFF и обошёл лимит / раздул карту). При доверенном источнике берём ПРАВЫЙ (ближайший к нам)
     * непроверенный хоп — реальный клиент, как его видел внутренний доверенный прокси; хопы самих
     * доверенных прокси в конце цепочки пропускаем. Дефолт (нет trusted-proxies) → всегда remoteAddr.
     */
    private String clientIp(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        if (!isTrustedProxy(remoteAddr)) {
            return remoteAddr;
        }
        String xff = request.getHeader("X-Forwarded-For");
        if (xff == null || xff.isBlank()) {
            return remoteAddr;
        }
        String[] hops = xff.split(",");
        for (int i = hops.length - 1; i >= 0; i--) {
            String hop = hops[i].trim();
            if (!hop.isEmpty() && !isTrustedProxy(hop)) {
                return hop;
            }
        }
        return remoteAddr;
    }

    private boolean isTrustedProxy(String ip) {
        for (IpAddressMatcher matcher : trustedProxies) {
            try {
                if (matcher.matches(ip)) {
                    return true;
                }
            } catch (IllegalArgumentException ignored) {
                // невалидный ip (напр. не-IP хост в XFF) — не считаем доверенным
            }
        }
        return false;
    }

    private void writeTooManyRequests(HttpServletResponse response, Scope scope, long retryAfterSeconds)
            throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS,
                "Too many requests for " + (scope == Scope.AUTH ? "authentication" : "message ingest")
                        + ". Retry after " + retryAfterSeconds + "s.");
        problem.setTitle("Rate limit exceeded");

        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));
        response.getWriter().write(objectMapper.writeValueAsString(problem));
    }
}
