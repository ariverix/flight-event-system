package ru.protectinfotrans.eca.conditions.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.protectinfotrans.eca.FlightStage;
import ru.protectinfotrans.eca.conditions.domain.ConditionAlreadyRaisedException;
import ru.protectinfotrans.eca.conditions.domain.RaisedCondition;
import ru.protectinfotrans.eca.conditions.port.in.ConditionManagementUseCase;
import ru.protectinfotrans.eca.conditions.port.in.ConditionQueryUseCase;
import ru.protectinfotrans.eca.conditions.port.in.FlightConditionLifecycleUseCase;
import ru.protectinfotrans.eca.conditions.port.out.ConditionRepositoryPort;
import ru.protectinfotrans.eca.sequence.domain.AlertLevel;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Движок условий/алертов (P3-3) — паритет с SITA Sequencer: raise/close custom condition + один
 * запрос активных условий + авто-закрытие при завершении рейса.
 *
 * <p><b>Метрики (по аналогии с {@code DeadLetterQueueService}/{@code OutboundMessageDeliveryScheduler}):</b>
 * {@code eca.conditions.raised} — каждый успешный raise; {@code eca.conditions.raise.rejected} —
 * попытка повторного raise активного условия ({@link ConditionAlreadyRaisedException});
 * {@code eca.conditions.closed} — каждое закрытие (вручную ИЛИ авто, count включает оба пути);
 * {@code eca.conditions.active} — gauge текущего числа активных условий (по всем рейсам),
 * обновляется push-once/update-many ({@link AtomicLong}, та же идиома, что
 * {@code ExecutionResumeRunner}/{@code OutboundMessageDeliveryScheduler} — регистрация
 * {@code meterRegistry.gauge(name, AtomicLong)} один раз в конструкторе, далее только
 * {@code .set(...)} на каждое изменение состояния).
 */
@Service
@Slf4j
@Transactional
public class ConditionService implements ConditionManagementUseCase, ConditionQueryUseCase, FlightConditionLifecycleUseCase {

    private final ConditionRepositoryPort repository;
    private final Counter raisedCounter;
    private final Counter raiseRejectedCounter;
    private final Counter closedCounter;
    private final AtomicLong activeGauge;

    public ConditionService(ConditionRepositoryPort repository, MeterRegistry meterRegistry) {
        this.repository = repository;
        this.raisedCounter = meterRegistry.counter("eca.conditions.raised");
        this.raiseRejectedCounter = meterRegistry.counter("eca.conditions.raise.rejected");
        this.closedCounter = meterRegistry.counter("eca.conditions.closed");
        this.activeGauge = meterRegistry.gauge("eca.conditions.active", new AtomicLong(0));
    }

    private void refreshActiveGauge() {
        activeGauge.set(repository.findAllActive().size());
    }

    /**
     * Raise — паритет SITA "нельзя поднять дважды одним именем" (см.
     * {@link ConditionAlreadyRaisedException} javadoc для обоснования "ошибка, не no-op").
     * Уровень алерта персистируется КАК АТРИБУТ ЭТОГО КОНКРЕТНОГО ПОДЪЁМА — условие и алерт
     * независимы (см. {@code RaisedCondition} javadoc): можно поднять с {@link AlertLevel#NO}.
     *
     * <p><b>{@code noRollbackFor = ConditionAlreadyRaisedException}:</b> отказ — это
     * ВОССТАНОВИМЫЙ бизнес-исход, а не сбой записи (проверка дубля — read-then-throw ДО любого
     * INSERT, ничего не записано на момент броска). ACTION-шаг движка
     * ({@code ActionStepRule#executeRaiseCondition}) ловит это исключение и уходит в FAILURE-ветку
     * decision-графа — БЕЗ {@code noRollbackFor} брошенное из этого {@code @Transactional}-метода
     * RuntimeException пометило бы ОБЪЕМЛЮЩУЮ транзакцию движка rollback-only, и весь переход
     * последовательности падал бы с {@code UnexpectedRollbackException} на commit (см. сценарии
     * GOTO-цикла, повторно поднимающие то же условие, в {@code P1_2_DecisionAndStartStopScenarioIntTest}).
     */
    @Override
    @Transactional(noRollbackFor = ConditionAlreadyRaisedException.class)
    public void raiseCondition(String aircraftId, String flightNumber, String conditionName, AlertLevel alertLevel) {
        repository.findActiveByAircraftIdAndFlightNumberAndConditionName(aircraftId, flightNumber, conditionName)
                .ifPresent(existing -> {
                    raiseRejectedCounter.increment();
                    throw new ConditionAlreadyRaisedException(aircraftId, flightNumber, conditionName);
                });

        RaisedCondition condition = RaisedCondition.builder()
                .aircraftId(aircraftId)
                .flightNumber(flightNumber)
                .conditionName(conditionName)
                .alertLevel(alertLevel)
                .raisedAt(LocalDateTime.now())
                .build();

        repository.save(condition);
        raisedCounter.increment();
        refreshActiveGauge();
        log.info("Condition '{}' raised for aircraft={}/flight={}, alertLevel={}",
                conditionName, aircraftId, flightNumber, alertLevel);
    }

    /**
     * Idempotent — закрытие уже закрытого/никогда не поднятого условия — no-op (см.
     * {@code ConditionManagementUseCase#closeCondition} javadoc, почему close ≠ raise по этому
     * признаку).
     */
    @Override
    public void closeCondition(String aircraftId, String flightNumber, String conditionName) {
        repository.findActiveByAircraftIdAndFlightNumberAndConditionName(aircraftId, flightNumber, conditionName)
                .ifPresentOrElse(condition -> {
                    condition.setClosedAt(LocalDateTime.now());
                    repository.save(condition);
                    closedCounter.increment();
                    refreshActiveGauge();
                    log.info("Condition '{}' closed for aircraft={}/flight={}", conditionName, aircraftId, flightNumber);
                }, () -> log.debug(
                        "Condition '{}' close: no active row for aircraft={}/flight={} (idempotent no-op)",
                        conditionName, aircraftId, flightNumber));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isConditionActive(String aircraftId, String flightNumber, String conditionName) {
        return repository.findActiveByAircraftIdAndFlightNumberAndConditionName(aircraftId, flightNumber, conditionName)
                .isPresent();
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, AlertLevel> getActiveConditions(String aircraftId, String flightNumber) {
        if (aircraftId == null || flightNumber == null) {
            return Map.of();
        }
        Map<String, AlertLevel> result = new LinkedHashMap<>();
        for (RaisedCondition condition : repository.findActiveByAircraftIdAndFlightNumber(aircraftId, flightNumber)) {
            result.put(condition.getConditionName(), condition.getAlertLevel());
        }
        return result;
    }

    /**
     * Авто-закрытие при завершении рейса — паритет SITA "при завершении рейса все активные
     * условия закрываются автоматически" (CLAUDE.md). Точный аналог
     * {@code CustomFieldExtractionService#onFlightStageChanged} (P3-2) — терминальные стадии
     * IN/SUMMARY, идемпотентно (повторный вызов на уже закрытом рейсе — 0 закрытых строк, не
     * ошибка), защита от закрытия условий чужого рейса того же борта при отсутствующем
     * {@code flightNumber}.
     */
    @Override
    public void onFlightStageChanged(String aircraftId, String flightNumber, FlightStage stage) {
        if (stage != FlightStage.IN && stage != FlightStage.SUMMARY) {
            return;
        }
        if (aircraftId == null || flightNumber == null) {
            log.warn("Cannot auto-close conditions: aircraftId/flightNumber missing (aircraft={}, flight={}, stage={})",
                    aircraftId, flightNumber, stage);
            return;
        }

        int closed = repository.closeAllActiveForFlight(aircraftId, flightNumber, LocalDateTime.now());
        if (closed > 0) {
            closedCounter.increment(closed);
            refreshActiveGauge();
        }
        log.info("Conditions auto-closed for aircraft={}/flight={} (stage={}, closedConditions={})",
                aircraftId, flightNumber, stage, closed);
    }

    @Override
    @Transactional(readOnly = true)
    public List<RaisedCondition> listAllActive() {
        return repository.findAllActive();
    }
}
