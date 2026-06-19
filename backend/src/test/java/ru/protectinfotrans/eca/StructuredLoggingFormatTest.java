package ru.protectinfotrans.eca;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.boot.logging.logback.StructuredLogEncoder;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Проверяет, что встроенный в Spring Boot 3.5 структурный JSON-формат лога (ECS,
 * см. logging.structured.format.console в application.yml) реально сериализует
 * correlationId из MDC в JSON-запись — без сторонних библиотек типа
 * logstash-logback-encoder.
 */
@DisplayName("Структурный JSON-формат лога (ECS)")
class StructuredLoggingFormatTest {

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    @DisplayName("JSON-запись лога должна содержать correlationId из MDC")
    void jsonLogEntryShouldContainCorrelationId() {
        MDC.put(CorrelationContext.CORRELATION_ID, "cid-test-123");

        LoggerContext loggerContext = new LoggerContext();
        // StructuredLogEncoder ищет Spring Environment в контексте логгера
        // (так же делает реальный LogbackLoggingSystem при старте приложения).
        loggerContext.putObject(Environment.class.getName(), new MockEnvironment());
        loggerContext.start();

        StructuredLogEncoder encoder = new StructuredLogEncoder();
        encoder.setContext(loggerContext);
        encoder.setFormat("ecs");
        encoder.setCharset(StandardCharsets.UTF_8);
        encoder.start();

        LoggingEvent event = new LoggingEvent();
        event.setLoggerName("ru.protectinfotrans.eca.test");
        event.setLevel(Level.INFO);
        event.setMessage("test message for structured logging");
        event.setMDCPropertyMap(MDC.getCopyOfContextMap());
        event.setTimeStamp(System.currentTimeMillis());

        String json = new String(encoder.encode(event), StandardCharsets.UTF_8);

        assertThat(json).contains("\"correlationId\":\"cid-test-123\"");
        assertThat(json).contains("test message for structured logging");

        encoder.stop();
        loggerContext.stop();
    }

    @Test
    @DisplayName("JSON-запись лога должна экранировать спецсимволы (CRLF) в значении MDC, не ломая структуру JSON")
    void jsonLogEntryShouldEscapeDirtyMdcValue() {
        // В норме CorrelationIdFilter не пропускает такие значения в MDC (whitelist-валидация),
        // но фиксируем поведение StructuredLogEncoder как дополнительный рубеж защиты:
        // даже "грязное" значение в MDC не должно ломать JSON-структуру записи лога.
        String dirty = "abc\r\nInjected: evil\"};{\"forged\":true";
        MDC.put(CorrelationContext.CORRELATION_ID, dirty);

        LoggerContext loggerContext = new LoggerContext();
        loggerContext.putObject(Environment.class.getName(), new MockEnvironment());
        loggerContext.start();

        StructuredLogEncoder encoder = new StructuredLogEncoder();
        encoder.setContext(loggerContext);
        encoder.setFormat("ecs");
        encoder.setCharset(StandardCharsets.UTF_8);
        encoder.start();

        LoggingEvent event = new LoggingEvent();
        event.setLoggerName("ru.protectinfotrans.eca.test");
        event.setLevel(Level.INFO);
        event.setMessage("test message with dirty correlationId");
        event.setMDCPropertyMap(MDC.getCopyOfContextMap());
        event.setTimeStamp(System.currentTimeMillis());

        String json = new String(encoder.encode(event), StandardCharsets.UTF_8);

        // Запись остаётся одной валидной JSON-строкой (без сырых CRLF, разрывающих лог на строки/записи).
        assertThat(json.split("\n").length).isEqualTo(1);
        assertThat(json).contains("\\r\\n");
        assertThat(json).contains("test message with dirty correlationId");

        encoder.stop();
        loggerContext.stop();
    }
}
