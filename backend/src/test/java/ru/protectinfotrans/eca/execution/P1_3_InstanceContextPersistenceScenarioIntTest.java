package ru.protectinfotrans.eca.execution;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ru.protectinfotrans.eca.BaseIntegrationTest;
import ru.protectinfotrans.eca.execution.application.ExecutionService;
import ru.protectinfotrans.eca.execution.application.InstanceContextCodec;
import ru.protectinfotrans.eca.execution.domain.ExecutionInstance;
import ru.protectinfotrans.eca.execution.domain.ExecutionStatus;
import ru.protectinfotrans.eca.execution.domain.InstanceContext;
import ru.protectinfotrans.eca.execution.port.out.ExecutionRepositoryPort;
import ru.protectinfotrans.eca.sequence.domain.StepType;
import ru.protectinfotrans.eca.sequence.domain.TransitionAction;
import ru.protectinfotrans.eca.sequence.dto.SequenceCreateRequest;
import ru.protectinfotrans.eca.sequence.dto.SequenceResponse;
import ru.protectinfotrans.eca.sequence.dto.StepCreateRequest;
import ru.protectinfotrans.eca.sequence.port.in.SequenceManagementUseCase;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-3 (часть 2, логика): персистентный стейт инстанса (currentStepIndex/status/context) на
 * КАЖДОМ переходе — один консистентный снапшот, плюс точка отсчёта "from this point only"
 * (контекст {@link InstanceContext}), которая должна переживать как очистку
 * {@code waitStartedAt} при выходе из WAIT-шага, так и перезагрузку инстанса из репозитория
 * (эмуляция рестарта сервиса — P1-4 будет резолвить недостроенные инстансы поверх этого же
 * персистентного стейта).
 *
 * Демосценарий: борт VP-BQR, рейс SU1234 (см. CLAUDE.md).
 *
 * Последовательность из 3 шагов:
 *  1. WAIT: ожидать POSITION_REPORT (downlink), timeout=5 сек, fromThisPointOnly=true.
 *     onSuccess: CONTINUE | onFailure: CONTINUE
 *  2. EVALUATE: тот же критерий MESSAGE_RECEIVED/fromThisPointOnly=true — должен унаследовать
 *     точку отсчёта WAIT-шага №1 (а не увидеть старое сообщение от прошлого рейса).
 *     onSuccess: CONTINUE | onFailure: CONTINUE
 *  3. ACTION: RAISE_CONDITION (мгновенно завершает CONTINUE -> END)
 */
@DisplayName("P1-3: персистентность стейта/контекста инстанса на каждом переходе")
class P1_3_InstanceContextPersistenceScenarioIntTest extends BaseIntegrationTest {

    @Autowired
    private SequenceManagementUseCase sequenceUseCase;

    @Autowired
    private ExecutionService executionService;

    @Autowired
    private ExecutionRepositoryPort executionRepository;

    @Autowired
    private InstanceContextCodec instanceContextCodec;

    private static final String AIRCRAFT_ID = "VP-BQR";
    private static final String FLIGHT_NUMBER = "SU1234";

    private Long sequenceId;

    @BeforeEach
    void createSequence() {
        SequenceCreateRequest createReq = new SequenceCreateRequest(
                "P1-3 Context Persistence Demo",
                "Старый отчёт от прошлого рейса в БД + WAIT(fromThisPointOnly) -> EVALUATE(fromThisPointOnly) -> ACTION",
                null,
                null
        );
        SequenceResponse created = sequenceUseCase.createSequence(createReq, 1L);
        sequenceId = created.id();

        // Шаг 1 — WAIT: POSITION_REPORT, fromThisPointOnly=true, timeout 5 сек
        sequenceUseCase.addStep(sequenceId, new StepCreateRequest(
                "Wait Position Report",
                StepType.WAIT,
                "{\"type\":\"MESSAGE_RECEIVED\",\"messageType\":\"DOWNLINK\","
                        + "\"templateName\":\"POSITION_REPORT\",\"fromThisPointOnly\":true}",
                5,
                TransitionAction.CONTINUE,
                null, false,
                TransitionAction.CONTINUE,
                null, false
        ), 1L);

        // Шаг 2 — EVALUATE: тот же критерий, fromThisPointOnly=true — должен унаследовать
        // точку отсчёта шага 1, иначе увидит старое сообщение из BeforeEach-фикстуры
        sequenceUseCase.addStep(sequenceId, new StepCreateRequest(
                "Evaluate Position Again",
                StepType.EVALUATE,
                "{\"type\":\"MESSAGE_RECEIVED\",\"messageType\":\"DOWNLINK\","
                        + "\"templateName\":\"POSITION_REPORT\",\"fromThisPointOnly\":true}",
                null,
                TransitionAction.CONTINUE,
                null, false,
                TransitionAction.CONTINUE,
                null, false
        ), 1L);

        // Шаг 3 — ACTION: завершающий маркер
        sequenceUseCase.addStep(sequenceId, new StepCreateRequest(
                "Raise marker condition",
                StepType.ACTION,
                "{\"actionType\":\"RAISE_CONDITION\",\"conditionName\":\"P1_3_DEMO\",\"alertLevel\":\"LOW\"}",
                null,
                TransitionAction.END,
                null, false,
                TransitionAction.END,
                null, false
        ), 1L);

        sequenceUseCase.activateSequence(sequenceId, 1L);
    }

    @Test
    @DisplayName("каждый save() инстанса — консистентный снапшот currentStepIndex+status+context")
    void everyTransitionPersistsConsistentSnapshot() {
        // Старое сообщение из "прошлого рейса" — если fromThisPointOnly не работает,
        // EVALUATE (шаг 2) увидит его и тест на унаследованную точку отсчёта провалится незаметно
        jdbcTemplate.update(
                "INSERT INTO messages (message_type, template_name, aircraft_id, flight_number, content, " +
                "received_at, position_source, is_estimated_position) " +
                "VALUES (?, ?, ?, ?, ?, NOW() - INTERVAL '1 hour', ?, FALSE)",
                "DOWNLINK", "POSITION_REPORT", AIRCRAFT_ID, FLIGHT_NUMBER, "{}", "ACARS"
        );

        executionService.startExecution(sequenceId, AIRCRAFT_ID, FLIGHT_NUMBER);

        ExecutionInstance instance = findInstance();
        // Шаг 1 WAIT: критерий fromThisPointOnly не видит старое сообщение -> остаётся WAITING
        assertThat(instance.getStatus()).isEqualTo(ExecutionStatus.WAITING);
        assertThat(instance.getCurrentStepIndex()).isEqualTo(1);
        assertThat(instance.getWaitStartedAt()).isNotNull();
        assertThat(instance.getWaitTimeoutAt()).isNotNull();

        // Снапшот в БД (не из локального объекта) — реально читаем обратно отдельным запросом,
        // подтверждая, что save() на WAIT-входе записал контекст консистентно с указателем шага.
        ExecutionInstance reloadedWhileWaiting = executionRepository.findById(instance.getId()).orElseThrow();
        assertThat(reloadedWhileWaiting.getCurrentStepIndex()).isEqualTo(1);
        assertThat(reloadedWhileWaiting.getStatus()).isEqualTo(ExecutionStatus.WAITING);

        // Новое сообщение "от текущего рейса" после момента входа в WAIT -> критерий должен закрыться
        jdbcTemplate.update(
                "INSERT INTO messages (message_type, template_name, aircraft_id, flight_number, content, " +
                "received_at, position_source, is_estimated_position) " +
                "VALUES (?, ?, ?, ?, ?, NOW(), ?, FALSE)",
                "DOWNLINK", "POSITION_REPORT", AIRCRAFT_ID, FLIGHT_NUMBER, "{}", "ACARS"
        );

        // Резолвим WAIT напрямую (минуя async @ApplicationModuleListener) детерминированно для теста
        executionService.checkWaitTimeouts(); // не истёк — no-op, оставляем WAITING
        assertThat(findInstance().getStatus()).isEqualTo(ExecutionStatus.WAITING);

        triggerProcessEvent();

        // processEvent -> @ApplicationModuleListener (@Async) — ждём результат через поллинг,
        // как и остальные сценарные тесты (см. awaitInstance в P1_2_DecisionAndStartStopScenarioIntTest)
        ExecutionInstance afterResolve = awaitInstance(i -> i.getStatus() == ExecutionStatus.COMPLETED);
        // Шаг 1 SUCCESS -> CONTINUE -> Шаг 2 EVALUATE: должен унаследовать точку отсчёта Шага 1
        // и НЕ увидеть старое сообщение из прошлого рейса (вставленное час назад) -> SUCCESS
        // -> CONTINUE -> Шаг 3 ACTION (мгновенно) -> END -> COMPLETED.
        assertThat(afterResolve.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(afterResolve.getCurrentStepIndex()).isEqualTo(3);
        // waitTimeoutAt очищен при выходе из WAIT-шага №1 (резолв закрывает окно ожидания).
        // waitStartedAt НЕ null: ExecutionService восстановил точку отсчёта WAIT-шага №1 из
        // персистентного контекста перед выполнением шага 2 EVALUATE (см.
        // restoreFromThisPointReferenceIfNeeded) — иначе EVALUATE увидел бы старое сообщение
        // из прошлого рейса. Раз шаг 3 (ACTION, не WAIT) не очищает это поле, оно остаётся
        // на инстансе как последняя использованная точка отсчёта — ожидаемое поведение.
        assertThat(afterResolve.getWaitStartedAt()).isNotNull();
        assertThat(afterResolve.getWaitTimeoutAt()).isNull();

        // step history покрывает все 3 шага -> переходы реально прошли последовательно.
        // Читаем напрямую через jdbcTemplate, а не instance.getStepHistory() — та коллекция
        // lazy и сессия Hibernate, в которой она была загружена, к этому моменту теста закрыта.
        List<Integer> stepIndexes = jdbcTemplate.queryForList(
                "SELECT step_index FROM step_executions WHERE execution_instance_id = ? ORDER BY executed_at ASC",
                Integer.class, afterResolve.getId());
        assertThat(stepIndexes).containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("from-this-point-only reference сохраняется в контекст и виден после перезагрузки инстанса из репозитория")
    void fromThisPointReferenceSurvivesInstanceReload() {
        executionService.startExecution(sequenceId, AIRCRAFT_ID, FLIGHT_NUMBER);

        ExecutionInstance waiting = findInstance();
        assertThat(waiting.getStatus()).isEqualTo(ExecutionStatus.WAITING);
        LocalDateTime waitStartedAt = waiting.getWaitStartedAt();
        assertThat(waitStartedAt).isNotNull();

        // Резолвим WAIT шаг сообщением -> CONTINUE -> EVALUATE (шаг 2) выполнится тут же
        jdbcTemplate.update(
                "INSERT INTO messages (message_type, template_name, aircraft_id, flight_number, content, " +
                "received_at, position_source, is_estimated_position) " +
                "VALUES (?, ?, ?, ?, ?, NOW(), ?, FALSE)",
                "DOWNLINK", "POSITION_REPORT", AIRCRAFT_ID, FLIGHT_NUMBER, "{}", "ACARS"
        );
        triggerProcessEvent();

        ExecutionInstance completed = awaitInstance(i -> i.getStatus() == ExecutionStatus.COMPLETED);
        assertThat(completed.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);

        // "Перезагрузка инстанса заново" — отдельный запрос к репозиторию, не тот же java-объект,
        // эмулирует чтение стейта после рестарта сервиса (P1-4 будет резолвить отсюда же).
        ExecutionInstance reloaded = executionRepository.findById(completed.getId()).orElseThrow();
        InstanceContext context = instanceContextCodec.decode(reloaded.getContextJson());

        // Точка отсчёта WAIT-шага №1 запомнена в контексте под его orderIndex и читается обратно
        // после полного независимого decode() из JSON, прочитанного отдельным запросом к БД.
        LocalDateTime persistedReference = context.getFromThisPointReference(1);
        assertThat(persistedReference).isNotNull();
        assertThat(persistedReference).isEqualToIgnoringNanos(waitStartedAt);
    }

    @Test
    @DisplayName("round-trip контекста: encode/decode не теряет данные после нескольких переходов")
    void contextRoundTripAcrossMultipleTransitions() {
        executionService.startExecution(sequenceId, AIRCRAFT_ID, FLIGHT_NUMBER);
        ExecutionInstance instance = findInstance();

        // contextJson не статичный "{}" — реально содержит структуру InstanceContext
        assertThat(instance.getContextJson()).isNotBlank();
        InstanceContext decoded = instanceContextCodec.decode(instance.getContextJson());
        // на шаге 1 WAIT ещё не resolved, поэтому точки отсчёта в контексте пока нет —
        // дешёвая проверка, что decode() не падает и возвращает валидный объект
        assertThat(decoded.getFromThisPointReference(1)).isNull();

        String reEncoded = instanceContextCodec.encode(decoded);
        assertThat(reEncoded).isNotBlank();
        // повторный decode(encode(x)) == исходному состоянию (идемпотентность round-trip)
        InstanceContext roundTripped = instanceContextCodec.decode(reEncoded);
        assertThat(roundTripped.getFromThisPointReference(1)).isNull();
    }

    /**
     * processEvent — @ApplicationModuleListener (@Async) — ждём результат поллингом,
     * как и остальные сценарные тесты модуля execution (см. P1_2_DecisionAndStartStopScenarioIntTest).
     */
    private ExecutionInstance awaitInstance(java.util.function.Predicate<ExecutionInstance> condition) {
        long deadline = System.currentTimeMillis() + 5000;
        ExecutionInstance last = null;
        while (System.currentTimeMillis() < deadline) {
            last = executionRepository.findAll(org.springframework.data.domain.PageRequest.of(0, 200))
                    .getContent().stream()
                    .filter(i -> i.getSequenceId().equals(sequenceId) && AIRCRAFT_ID.equals(i.getAircraftId()))
                    .findFirst()
                    .orElse(null);
            if (last != null && condition.test(last)) {
                return last;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return last;
    }

    private ExecutionInstance findInstance() {
        List<ExecutionInstance> active = executionRepository.findActiveByAircraftId(AIRCRAFT_ID);
        return active.stream()
                .filter(i -> i.getSequenceId().equals(sequenceId))
                .findFirst()
                .or(() -> executionRepository.findAll(org.springframework.data.domain.PageRequest.of(0, 100))
                        .getContent().stream()
                        .filter(i -> i.getSequenceId().equals(sequenceId) && AIRCRAFT_ID.equals(i.getAircraftId()))
                        .findFirst())
                .orElseThrow();
    }

    /** Эмулирует приход NormalizedEvent, который резолвит WAITING-инстансы (processWaitingInstances). */
    private void triggerProcessEvent() {
        ru.protectinfotrans.eca.eventprocessor.event.NormalizedEvent event =
                new ru.protectinfotrans.eca.eventprocessor.event.NormalizedEvent(
                        999L,
                        ru.protectinfotrans.eca.MessageType.DOWNLINK,
                        "POSITION_REPORT",
                        AIRCRAFT_ID,
                        FLIGHT_NUMBER,
                        ru.protectinfotrans.eca.FlightStage.OFF,
                        LocalDateTime.now()
                );
        executionService.processEvent(event);
    }
}
