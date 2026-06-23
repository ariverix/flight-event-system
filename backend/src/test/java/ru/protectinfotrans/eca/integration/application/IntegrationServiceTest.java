package ru.protectinfotrans.eca.integration.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.protectinfotrans.eca.execution.port.out.MessageOutputPort;
import ru.protectinfotrans.eca.execution.port.out.NotificationPort;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit-тесты для IntegrationService.
 *
 * См. диплом: раздел 1.3.5 (UC-07)
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("IntegrationService")
class IntegrationServiceTest {

    @Mock
    private MessageOutputPort messageOutputPort;

    @Mock
    private NotificationPort notificationPort;

    private IntegrationService service;

    @BeforeEach
    void setUp() {
        service = new IntegrationService(messageOutputPort, notificationPort);
    }

    @Nested
    @DisplayName("Message sending")
    class MessageSendingTests {

        @Test
        @DisplayName("должен отправлять uplink сообщения через порт")
        void shouldSendUplinkMessages() {
            // Arrange
            when(messageOutputPort.sendUplink(anyString(), anyString(), anyMap())).thenReturn(true);

            // Act
            boolean result = service.sendUplink("VP-BXX", "WEATHER", Map.of("airport", "UUEE"));

            // Assert
            assertThat(result).isTrue();
            verify(messageOutputPort).sendUplink("VP-BXX", "WEATHER", Map.of("airport", "UUEE"));
        }

        @Test
        @DisplayName("должен отправлять ground сообщения через порт")
        void shouldSendGroundMessages() {
            // Arrange
            when(messageOutputPort.sendGround(anyList(), anyString(), anyMap())).thenReturn(true);

            // Act
            boolean result = service.sendGround(List.of("OPS", "DISPATCH"), "ALERT", Map.of());

            // Assert
            assertThat(result).isTrue();
            verify(messageOutputPort).sendGround(List.of("OPS", "DISPATCH"), "ALERT", Map.of());
        }
    }

    // P3-3: управление условиями (raise/close/query) больше НЕ часть IntegrationService — переехало
    // в отдельный модуль conditions (ConditionManagementUseCase/ConditionQueryUseCase,
    // реализация ConditionService, персистентное per-flight хранилище). Покрытие raise/close/
    // auto-close/изоляция между рейсами и бортами — см. ConditionServiceTest в модуле conditions.

    @Nested
    @DisplayName("Operator notifications")
    class NotificationTests {

        @Test
        @DisplayName("должен отправлять уведомления оператору")
        void shouldNotifyOperator() {
            // Act
            service.notifyOperator("Aircraft delayed", "HIGH", "VP-BXX");

            // Assert
            verify(notificationPort).notifyStepResult(null, null, "HIGH", "VP-BXX", "Aircraft delayed");
        }
    }
}
