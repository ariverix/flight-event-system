package ru.protectinfotrans.eca;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-тесты для {@link CorrelationContext}: каркас бизнес-полей корреляции (борт/рейс/
 * инстанс/сообщение) поверх MDC, который наполняется движком/интеграцией в P1/P2.
 */
@DisplayName("CorrelationContext")
class CorrelationContextTest {

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    @DisplayName("должен положить и прочитать correlationId")
    void shouldPutAndGetCorrelationId() {
        CorrelationContext.putCorrelationId("cid-123");

        assertThat(CorrelationContext.getCorrelationId()).isEqualTo("cid-123");
        assertThat(MDC.get(CorrelationContext.CORRELATION_ID)).isEqualTo("cid-123");
    }

    @Test
    @DisplayName("должен заполнять слоты борт/рейс/инстанс/сообщение")
    void shouldPutBusinessSlots() {
        CorrelationContext.putTail("VP-BQR");
        CorrelationContext.putFlight("SU1234");
        CorrelationContext.putInstance("instance-42");
        CorrelationContext.putMessage("msg-7");

        assertThat(MDC.get(CorrelationContext.TAIL_NUMBER)).isEqualTo("VP-BQR");
        assertThat(MDC.get(CorrelationContext.FLIGHT_ID)).isEqualTo("SU1234");
        assertThat(MDC.get(CorrelationContext.INSTANCE_ID)).isEqualTo("instance-42");
        assertThat(MDC.get(CorrelationContext.MESSAGE_ID)).isEqualTo("msg-7");
    }

    @Test
    @DisplayName("не должен класть null/пустые значения в MDC")
    void shouldIgnoreNullAndBlankValues() {
        CorrelationContext.putTail(null);
        CorrelationContext.putFlight("");
        CorrelationContext.putInstance("   ");

        assertThat(MDC.get(CorrelationContext.TAIL_NUMBER)).isNull();
        assertThat(MDC.get(CorrelationContext.FLIGHT_ID)).isNull();
        assertThat(MDC.get(CorrelationContext.INSTANCE_ID)).isNull();
    }

    @Test
    @DisplayName("clear() должен удалить все слоты контекста корреляции")
    void shouldClearAllSlots() {
        CorrelationContext.putCorrelationId("cid-1");
        CorrelationContext.putTail("VP-BQR");
        CorrelationContext.putFlight("SU1234");
        CorrelationContext.putInstance("instance-1");
        CorrelationContext.putMessage("msg-1");

        CorrelationContext.clear();

        assertThat(MDC.get(CorrelationContext.CORRELATION_ID)).isNull();
        assertThat(MDC.get(CorrelationContext.TAIL_NUMBER)).isNull();
        assertThat(MDC.get(CorrelationContext.FLIGHT_ID)).isNull();
        assertThat(MDC.get(CorrelationContext.INSTANCE_ID)).isNull();
        assertThat(MDC.get(CorrelationContext.MESSAGE_ID)).isNull();
    }
}
