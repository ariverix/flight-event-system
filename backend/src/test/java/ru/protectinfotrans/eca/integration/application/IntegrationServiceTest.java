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

    @Nested
    @DisplayName("Condition management")
    class ConditionManagementTests {

        @Test
        @DisplayName("должен поднимать условие и сохранять его в памяти")
        void shouldRaiseConditionAndStoreIt() {
            // Arrange
            when(messageOutputPort.raiseCondition(anyString(), anyString(), anyString())).thenReturn(true);

            // Act
            boolean result = service.raiseCondition("VP-BXX", "DELAYED", "HIGH");

            // Assert
            assertThat(result).isTrue();
            assertThat(service.isConditionActive("VP-BXX", "DELAYED")).isTrue();
            verify(messageOutputPort).raiseCondition("VP-BXX", "DELAYED", "HIGH");
        }

        @Test
        @DisplayName("должен снимать условие и удалять из памяти")
        void shouldCloseConditionAndRemoveFromMemory() {
            // Arrange
            when(messageOutputPort.raiseCondition(anyString(), anyString(), anyString())).thenReturn(true);
            when(messageOutputPort.closeCondition(anyString(), anyString())).thenReturn(true);

            service.raiseCondition("VP-BXX", "DELAYED", "HIGH");
            assertThat(service.isConditionActive("VP-BXX", "DELAYED")).isTrue();

            // Act
            boolean result = service.closeCondition("VP-BXX", "DELAYED");

            // Assert
            assertThat(result).isTrue();
            assertThat(service.isConditionActive("VP-BXX", "DELAYED")).isFalse();
            verify(messageOutputPort).closeCondition("VP-BXX", "DELAYED");
        }

        @Test
        @DisplayName("должен возвращать false для неактивного условия")
        void shouldReturnFalseForInactiveCondition() {
            assertThat(service.isConditionActive("VP-BXX", "UNKNOWN")).isFalse();
        }

        @Test
        @DisplayName("должен поддерживать несколько условий для одного ВС")
        void shouldSupportMultipleConditionsPerAircraft() {
            // Arrange
            when(messageOutputPort.raiseCondition(anyString(), anyString(), anyString())).thenReturn(true);

            // Act
            service.raiseCondition("VP-BXX", "DELAYED", "HIGH");
            service.raiseCondition("VP-BXX", "NO_POSITION", "MEDIUM");

            // Assert
            assertThat(service.getActiveConditions("VP-BXX")).containsExactlyInAnyOrder("DELAYED", "NO_POSITION");
            assertThat(service.isConditionActive("VP-BXX", "DELAYED")).isTrue();
            assertThat(service.isConditionActive("VP-BXX", "NO_POSITION")).isTrue();
        }

        @Test
        @DisplayName("должен изолировать условия разных ВС")
        void shouldIsolateConditionsBetweenAircraft() {
            // Arrange
            when(messageOutputPort.raiseCondition(anyString(), anyString(), anyString())).thenReturn(true);

            // Act
            service.raiseCondition("VP-BXX", "DELAYED", "HIGH");
            service.raiseCondition("VP-BYY", "DELAYED", "HIGH");

            // Assert
            assertThat(service.isConditionActive("VP-BXX", "DELAYED")).isTrue();
            assertThat(service.isConditionActive("VP-BYY", "DELAYED")).isTrue();
            assertThat(service.isConditionActive("VP-BZZ", "DELAYED")).isFalse();
        }
    }

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
