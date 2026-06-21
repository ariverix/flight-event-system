package ru.protectinfotrans.eca.execution.rules;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.protectinfotrans.eca.FlightStage;
import ru.protectinfotrans.eca.execution.domain.ExecutionInstance;
import ru.protectinfotrans.eca.execution.domain.ExecutionStatus;
import ru.protectinfotrans.eca.execution.domain.StepResult;
import ru.protectinfotrans.eca.execution.dto.ExecutionContext;
import ru.protectinfotrans.eca.execution.port.out.MessageOutputPort;
import ru.protectinfotrans.eca.sequence.domain.Step;
import ru.protectinfotrans.eca.sequence.domain.StepType;
import ru.protectinfotrans.eca.sequence.domain.UplinkOrigin;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-тесты для ActionStepRule.
 * Проверяет все 5 типов действий: SEND_UPLINK, SEND_GROUND, RAISE_CONDITION, CLOSE_CONDITION, WAIT_TIME.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ActionStepRule")
class ActionStepRuleTest {

    @Mock
    private MessageOutputPort messageOutputPort;

    @Spy
    private ObjectMapper objectMapper;

    @InjectMocks
    private ActionStepRule rule;

    private Step step;
    private ExecutionInstance instance;
    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        step = Step.builder()
                .orderIndex(1)
                .stepType(StepType.ACTION)
                .build();

        instance = ExecutionInstance.builder()
                .id(1L)
                .sequenceId(100L)
                .aircraftId("VP-BAB")
                .status(ExecutionStatus.RUNNING)
                .build();

        context = new ExecutionContext(
                "VP-BAB",
                "SU1234",
                FlightStage.OFF,
                LocalDateTime.now(),
                new HashMap<>()
        );

        rule.reset();
    }

    @Test
    @DisplayName("должен совпадать только для ACTION шагов")
    void shouldMatchOnlyActionSteps() {
        assertThat(rule.matches(step)).isTrue();

        step.setStepType(StepType.EVALUATE);
        assertThat(rule.matches(step)).isFalse();

        step.setStepType(StepType.WAIT);
        assertThat(rule.matches(step)).isFalse();
    }

    @Test
    @DisplayName("SEND_UPLINK: должен отправить uplink сообщение с origin=COMPUTER_GENERATED по умолчанию и вернуть SUCCESS")
    void shouldExecuteSendUplink() {
        step.setConfigJson("""
            {
                "actionType": "SEND_UPLINK",
                "templateName": "CLEARANCE",
                "params": {"gate": "A1"}
            }
            """);

        when(messageOutputPort.sendUplink(anyString(), anyString(), any(), any(), any(), any())).thenReturn(true);

        rule.execute(step, instance, context);

        // instance.getId()/step.getOrderIndex() — дедуп-ключ идемпотентности (фикс регрессии
        // P1-4 x P2-3) — ActionStepRule ОБЯЗАН передавать его в 6-арг. перегрузку порта.
        verify(messageOutputPort).sendUplink(eq("VP-BAB"), eq("CLEARANCE"), any(Map.class),
                eq(UplinkOrigin.COMPUTER_GENERATED), eq(instance.getId()), eq(step.getOrderIndex()));
        assertThat(rule.getResult()).isEqualTo(StepResult.SUCCESS);
    }

    @Test
    @DisplayName("SEND_UPLINK: должен передать uplinkOrigin=EXTERNAL_USER если указан в конфиге")
    void shouldExecuteSendUplinkWithExternalUserOrigin() {
        step.setConfigJson("""
            {
                "actionType": "SEND_UPLINK",
                "templateName": "CUSTOM_CLEARANCE",
                "uplinkOrigin": "EXTERNAL_USER",
                "params": {}
            }
            """);

        when(messageOutputPort.sendUplink(anyString(), anyString(), any(), any(), any(), any())).thenReturn(true);

        rule.execute(step, instance, context);

        verify(messageOutputPort).sendUplink(eq("VP-BAB"), eq("CUSTOM_CLEARANCE"), any(Map.class),
                eq(UplinkOrigin.EXTERNAL_USER), eq(instance.getId()), eq(step.getOrderIndex()));
        assertThat(rule.getResult()).isEqualTo(StepResult.SUCCESS);
    }

    @Test
    @DisplayName("SEND_GROUND: должен отправить ground сообщение и вернуть SUCCESS")
    void shouldExecuteSendGround() {
        step.setConfigJson("""
            {
                "actionType": "SEND_GROUND",
                "templateName": "NOTIFICATION",
                "recipients": ["dispatcher@airline.com"],
                "params": {}
            }
            """);

        when(messageOutputPort.sendGround(any(), anyString(), any(), any(), any())).thenReturn(true);

        rule.execute(step, instance, context);

        // instance.getId()/step.getOrderIndex() — дедуп-ключ идемпотентности (фикс регрессии
        // P1-4 x P2-3) — ActionStepRule ОБЯЗАН передавать его в 5-арг. перегрузку порта.
        verify(messageOutputPort).sendGround(eq(List.of("dispatcher@airline.com")), eq("NOTIFICATION"), any(),
                eq(instance.getId()), eq(step.getOrderIndex()));
        assertThat(rule.getResult()).isEqualTo(StepResult.SUCCESS);
    }

    @Test
    @DisplayName("RAISE_CONDITION: должен поднять условие и вернуть SUCCESS")
    void shouldExecuteRaiseCondition() {
        step.setConfigJson("""
            {
                "actionType": "RAISE_CONDITION",
                "conditionName": "DELAYED",
                "alertLevel": "WARNING"
            }
            """);

        when(messageOutputPort.raiseCondition(anyString(), anyString(), anyString())).thenReturn(true);

        rule.execute(step, instance, context);

        verify(messageOutputPort).raiseCondition("VP-BAB", "DELAYED", "WARNING");
        assertThat(rule.getResult()).isEqualTo(StepResult.SUCCESS);
    }

    @Test
    @DisplayName("CLOSE_CONDITION: должен снять условие и вернуть SUCCESS")
    void shouldExecuteCloseCondition() {
        step.setConfigJson("""
            {
                "actionType": "CLOSE_CONDITION",
                "conditionName": "DELAYED"
            }
            """);

        when(messageOutputPort.closeCondition(anyString(), anyString())).thenReturn(true);

        rule.execute(step, instance, context);

        verify(messageOutputPort).closeCondition("VP-BAB", "DELAYED");
        assertThat(rule.getResult()).isEqualTo(StepResult.SUCCESS);
    }

    @Test
    @DisplayName("WAIT_TIME: должен установить таймаут и вернуть SUCCESS")
    void shouldExecuteWaitTime() {
        step.setConfigJson("""
            {
                "actionType": "WAIT_TIME",
                "durationSeconds": 300
            }
            """);

        rule.execute(step, instance, context);

        assertThat(instance.getStatus()).isEqualTo(ExecutionStatus.WAITING);
        assertThat(instance.getWaitStartedAt()).isNotNull();
        assertThat(instance.getWaitTimeoutAt()).isNotNull();
        assertThat(rule.getResult()).isEqualTo(StepResult.SUCCESS);
    }

    @Test
    @DisplayName("должен вернуть FAILURE при ошибке выполнения действия")
    void shouldReturnFailureOnError() {
        step.setConfigJson("""
            {
                "actionType": "SEND_UPLINK",
                "templateName": "TEST"
            }
            """);

        when(messageOutputPort.sendUplink(anyString(), anyString(), any(), any(), any(), any())).thenReturn(false);

        rule.execute(step, instance, context);

        assertThat(rule.getResult()).isEqualTo(StepResult.FAILURE);
    }

    @Test
    @DisplayName("WAIT_TIME: должен интерпретировать unit=MIN и умножить на 60")
    void shouldExecuteWaitTimeWithMinutesUnit() {
        step.setConfigJson("""
            {
                "actionType": "WAIT_TIME",
                "durationSeconds": 5,
                "unit": "MIN"
            }
            """);

        LocalDateTime fixedNow = LocalDateTime.of(2024, 1, 10, 10, 0);
        ExecutionContext fixedContext = new ExecutionContext(
                "VP-BAB", "SU1234", FlightStage.OFF, fixedNow, new HashMap<>()
        );

        rule.execute(step, instance, fixedContext);

        assertThat(instance.getWaitTimeoutAt()).isEqualTo(fixedNow.plusSeconds(5 * 60L));
        assertThat(rule.getResult()).isEqualTo(StepResult.SUCCESS);
    }

    @Test
    @DisplayName("WAIT_TIME: должен интерпретировать unit=HOUR и умножить на 3600")
    void shouldExecuteWaitTimeWithHoursUnit() {
        step.setConfigJson("""
            {
                "actionType": "WAIT_TIME",
                "durationSeconds": 2,
                "unit": "HOUR"
            }
            """);

        LocalDateTime fixedNow = LocalDateTime.of(2024, 1, 10, 10, 0);
        ExecutionContext fixedContext = new ExecutionContext(
                "VP-BAB", "SU1234", FlightStage.OFF, fixedNow, new HashMap<>()
        );

        rule.execute(step, instance, fixedContext);

        assertThat(instance.getWaitTimeoutAt()).isEqualTo(fixedNow.plusSeconds(2 * 3600L));
        assertThat(rule.getResult()).isEqualTo(StepResult.SUCCESS);
    }

    @Test
    @DisplayName("WAIT_TIME: без unit должен трактовать durationSeconds как секунды (обратная совместимость)")
    void shouldExecuteWaitTimeWithoutUnitDefaultsToSeconds() {
        step.setConfigJson("""
            {
                "actionType": "WAIT_TIME",
                "durationSeconds": 300
            }
            """);

        LocalDateTime fixedNow = LocalDateTime.of(2024, 1, 10, 10, 0);
        ExecutionContext fixedContext = new ExecutionContext(
                "VP-BAB", "SU1234", FlightStage.OFF, fixedNow, new HashMap<>()
        );

        rule.execute(step, instance, fixedContext);

        assertThat(instance.getWaitTimeoutAt()).isEqualTo(fixedNow.plusSeconds(300));
        assertThat(rule.getResult()).isEqualTo(StepResult.SUCCESS);
    }

    @Test
    @DisplayName("RAISE_CONDITION: должен принять нестандартный alertLevel без падения (лишь warn-лог)")
    void shouldRaiseConditionWithNonCanonicalAlertLevelWithoutFailing() {
        step.setConfigJson("""
            {
                "actionType": "RAISE_CONDITION",
                "conditionName": "LEGACY_ALERT",
                "alertLevel": "WARNING"
            }
            """);

        when(messageOutputPort.raiseCondition(anyString(), anyString(), anyString())).thenReturn(true);

        rule.execute(step, instance, context);

        verify(messageOutputPort).raiseCondition("VP-BAB", "LEGACY_ALERT", "WARNING");
        assertThat(rule.getResult()).isEqualTo(StepResult.SUCCESS);
    }

    @Test
    @DisplayName("RAISE_CONDITION: должен принять канонический уровень CRITICAL (паритет с SITA)")
    void shouldRaiseConditionWithCanonicalCriticalLevel() {
        step.setConfigJson("""
            {
                "actionType": "RAISE_CONDITION",
                "conditionName": "ENGINE_FAILURE",
                "alertLevel": "CRITICAL"
            }
            """);

        when(messageOutputPort.raiseCondition(anyString(), anyString(), anyString())).thenReturn(true);

        rule.execute(step, instance, context);

        verify(messageOutputPort).raiseCondition("VP-BAB", "ENGINE_FAILURE", "CRITICAL");
        assertThat(rule.getResult()).isEqualTo(StepResult.SUCCESS);
    }
}
