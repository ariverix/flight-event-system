package ru.protectinfotrans.eca;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Unit-тесты для {@link CorrelationIdFilter}.
 * Проверяет генерацию/проброс correlationId через заголовки и очистку MDC после запроса.
 */
@DisplayName("CorrelationIdFilter")
class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    @DisplayName("должен сгенерировать новый correlationId если заголовка нет")
    void shouldGenerateCorrelationIdWhenHeaderAbsent() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain filterChain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, filterChain);

        String generated = response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER);
        assertThat(generated).isNotBlank();
        assertThat(UUID.fromString(generated)).isNotNull();
        verify(filterChain).doFilter(request, response);

        // MDC должен быть очищен после обработки запроса (нет утечки между запросами)
        assertThat(MDC.get(CorrelationContext.CORRELATION_ID)).isNull();
    }

    @Test
    @DisplayName("должен пробросить входящий correlationId без изменений")
    void shouldPropagateIncomingCorrelationId() throws Exception {
        String incoming = "11111111-1111-1111-1111-111111111111";
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, incoming);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain filterChain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER)).isEqualTo(incoming);
        verify(filterChain).doFilter(request, response);
        assertThat(MDC.get(CorrelationContext.CORRELATION_ID)).isNull();
    }

    @Test
    @DisplayName("должен положить correlationId в MDC во время обработки запроса")
    void shouldPutCorrelationIdInMdcDuringRequest() throws Exception {
        String incoming = "22222222-2222-2222-2222-222222222222";
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, incoming);
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<String> mdcValueDuringChain = new AtomicReference<>();
        FilterChain filterChain = (req, res) -> mdcValueDuringChain.set(MDC.get(CorrelationContext.CORRELATION_ID));

        filter.doFilterInternal(request, response, filterChain);

        assertThat(mdcValueDuringChain.get()).isEqualTo(incoming);
        // после завершения фильтра MDC очищен
        assertThat(MDC.get(CorrelationContext.CORRELATION_ID)).isNull();
    }

    @Test
    @DisplayName("должен очистить MDC даже если цепочка фильтров выбросила исключение")
    void shouldClearMdcEvenWhenChainThrows() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain filterChain = (req, res) -> {
            throw new RuntimeException("boom");
        };

        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> filter.doFilterInternal(request, response, filterChain));

        assertThat(MDC.get(CorrelationContext.CORRELATION_ID)).isNull();
    }

    @Test
    @DisplayName("должен отбросить входящий correlationId с CRLF и сгенерировать новый UUID")
    void shouldRejectCorrelationIdWithCrlf() throws Exception {
        String malicious = "abc\r\nInjected: evil";
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, malicious);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain filterChain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, filterChain);

        String generated = response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER);
        assertThat(generated).isNotEqualTo(malicious);
        assertThat(UUID.fromString(generated)).isNotNull();
        verify(filterChain).doFilter(request, response);
        assertThat(MDC.get(CorrelationContext.CORRELATION_ID)).isNull();
    }

    @Test
    @DisplayName("должен отбросить входящий correlationId с control-символом (пробел) и сгенерировать новый UUID")
    void shouldRejectCorrelationIdWithControlCharacter() throws Exception {
        String malicious = "abc def";
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, malicious);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain filterChain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, filterChain);

        String generated = response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER);
        assertThat(generated).isNotEqualTo(malicious);
        assertThat(UUID.fromString(generated)).isNotNull();
        verify(filterChain).doFilter(request, response);
        assertThat(MDC.get(CorrelationContext.CORRELATION_ID)).isNull();
    }

    @Test
    @DisplayName("должен отбросить входящий correlationId длиннее 64 символов и сгенерировать новый UUID")
    void shouldRejectCorrelationIdLongerThan64Chars() throws Exception {
        String tooLong = "a".repeat(65);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, tooLong);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain filterChain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, filterChain);

        String generated = response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER);
        assertThat(generated).isNotEqualTo(tooLong);
        assertThat(UUID.fromString(generated)).isNotNull();
        verify(filterChain).doFilter(request, response);
        assertThat(MDC.get(CorrelationContext.CORRELATION_ID)).isNull();
    }

    @Test
    @DisplayName("должен пробросить валидный входящий correlationId без изменений (буквы/цифры/дефис)")
    void shouldPropagateValidCorrelationIdMatchingWhitelist() throws Exception {
        String valid = "req-12345";
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, valid);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain filterChain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER)).isEqualTo(valid);
        verify(filterChain).doFilter(request, response);
        assertThat(MDC.get(CorrelationContext.CORRELATION_ID)).isNull();
    }
}
