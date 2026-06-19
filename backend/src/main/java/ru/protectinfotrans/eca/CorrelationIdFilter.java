package ru.protectinfotrans.eca;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Сквозной фильтр correlationId для всех HTTP-запросов.
 * <p>
 * Берёт {@value #CORRELATION_ID_HEADER} из входящего запроса; если заголовка нет,
 * пуст или не проходит whitelist-валидацию ({@link #VALID_CORRELATION_ID}) — генерирует
 * новый UUID. Фильтр применяется глобально, в том числе к открытому незащищённому
 * эндпоинту приёма ACARS, поэтому входящему значению заголовка не доверяем "как есть":
 * непровалидированное значение может содержать CRLF/control-символы (log forging) или
 * быть неограниченной длины (log flooding/DoS). Невалидное значение не "чистится"
 * построчно, а целиком отбрасывается и заменяется сгенерированным UUID.
 * <p>
 * Кладёт значение в {@link CorrelationContext} (MDC), благодаря чему оно попадает
 * в каждую структурную JSON-запись лога за время обработки запроса, и возвращает
 * его же в заголовке ответа — для корреляции на стороне клиента/борта.
 * <p>
 * Регистрируется с наивысшим приоритетом ({@link Ordered#HIGHEST_PRECEDENCE}), чтобы
 * отработать до Spring Security и бизнес-логики. В {@code finally} обязательно чистит
 * MDC, чтобы значение не "утекло" в следующий запрос, обслуживаемый тем же потоком пула.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

    /**
     * Whitelist для входящего correlationId: буквы/цифры/дефис, длина 1..64.
     * Лимит длины синхронизирован с колонкой {@code audit_log.correlation_id VARCHAR(64)} (V20).
     */
    private static final Pattern VALID_CORRELATION_ID = Pattern.compile("^[A-Za-z0-9-]{1,64}$");

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        String correlationId = request.getHeader(CORRELATION_ID_HEADER);
        if (correlationId == null || !VALID_CORRELATION_ID.matcher(correlationId).matches()) {
            correlationId = UUID.randomUUID().toString();
        }

        try {
            CorrelationContext.putCorrelationId(correlationId);
            response.setHeader(CORRELATION_ID_HEADER, correlationId);
            filterChain.doFilter(request, response);
        } finally {
            CorrelationContext.clear();
        }
    }
}
