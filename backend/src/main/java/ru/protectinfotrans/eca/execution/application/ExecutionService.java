package ru.protectinfotrans.eca.execution.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.protectinfotrans.eca.FlightStage;
import ru.protectinfotrans.eca.eventprocessor.event.NormalizedEvent;
import ru.protectinfotrans.eca.execution.domain.ExecutionInstance;
import ru.protectinfotrans.eca.execution.domain.ExecutionStatus;
import ru.protectinfotrans.eca.execution.domain.StepExecution;
import ru.protectinfotrans.eca.execution.domain.StepResult;
import ru.protectinfotrans.eca.execution.dto.ExecutionContext;
import ru.protectinfotrans.eca.execution.event.ExecutionCompletedEvent;
import ru.protectinfotrans.eca.execution.event.ExecutionStartedEvent;
import ru.protectinfotrans.eca.execution.event.StepNotificationEvent;
import ru.protectinfotrans.eca.execution.event.StepTransitionEvent;
import ru.protectinfotrans.eca.execution.port.out.ConditionQueryPort;
import ru.protectinfotrans.eca.execution.port.out.ExecutionRepositoryPort;
import ru.protectinfotrans.eca.execution.port.out.SequenceQueryPort;
import ru.protectinfotrans.eca.execution.port.out.NotificationPort;
import ru.protectinfotrans.eca.sequence.domain.Sequence;
import ru.protectinfotrans.eca.sequence.domain.SequenceStatus;
import ru.protectinfotrans.eca.sequence.domain.Step;
import ru.protectinfotrans.eca.sequence.domain.StepType;
import ru.protectinfotrans.eca.sequence.domain.TransitionAction;

import com.fasterxml.jackson.core.type.TypeReference;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Основной сервис выполнения последовательностей.
 * Слушает NormalizedEvent, проверяет start/stop критерии,
 * управляет жизненным циклом экземпляров и таймаутами WAIT-шагов.
 */
@Service
@Transactional
@RequiredArgsConstructor
@Slf4j
public class ExecutionService {

    /**
     * Защита от бесконечного синхронного цикла CONTINUE/GOTO.
     * Если цепочка шагов без WAIT замыкается в цикл (ACTION/EVALUATE крутят друг друга
     * через GOTO), executeTransition→advanceExecution рекурсия не разрывается событием —
     * без лимита это StackOverflowError/зависание потока обработки события.
     * WAIT всегда разрывает цепочку (executeStep возвращает null, рекурсия не продолжается),
     * поэтому лимит — это защита именно от "горячих" ACTION/EVALUATE-циклов, а не от
     * легитимного семантического зацикливания через WAIT (которое допустимо в SITA).
     */
    public static final int MAX_SYNCHRONOUS_TRANSITIONS = 1000;

    private final ExecutionRepositoryPort executionRepository;
    private final SequenceQueryPort sequenceQuery;
    private final EcaRuleEngine ecaRuleEngine;
    private final CriterionEvaluator criterionEvaluator;
    private final NotificationPort notificationPort;
    private final ConditionQueryPort conditionQueryPort;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    /**
     * Точка входа для всех событий от Event Processor.
     */
    @ApplicationModuleListener
    public void processEvent(NormalizedEvent event) {
        log.info("Processing event: messageId={}, aircraftId={}, type={}, template={}",
                event.messageId(), event.aircraftId(), event.messageType(), event.templateName());

        checkStartCriteria(event);
        checkStopCriteria(event);
        processWaitingInstances(event);
    }

    /**
     * Для MESSAGE_RECEIVED сравниваем с текущим событием напрямую —
     * запрос к БД давал ложные срабатывания на старых сообщениях.
     */
    private void checkStartCriteria(NormalizedEvent event) {
        List<Sequence> activeSequences = sequenceQuery.findAllByStatus(SequenceStatus.ACTIVE);

        for (Sequence sequence : activeSequences) {
            if (sequence.getStartCriteriaJson() == null || sequence.getStartCriteriaJson().isBlank()) {
                if (event.flightStage() == FlightStage.INIT) {
                    startExecution(sequence.getId(), event.aircraftId(), event.flightNumber());
                }
            } else {
                boolean criterionMet = matchesStartCriteria(sequence.getStartCriteriaJson(), event);
                if (criterionMet) {
                    log.info("Start criteria met for sequence {} and aircraft {}", sequence.getId(), event.aircraftId());
                    startExecution(sequence.getId(), event.aircraftId(), event.flightNumber());
                }
            }
        }
    }

    private boolean matchesStartCriteria(String criteriaJson, NormalizedEvent event) {
        try {
            Map<String, Object> criteria = objectMapper.readValue(criteriaJson, new TypeReference<>() {});
            String type = (String) criteria.get("type");

            if ("MESSAGE_RECEIVED".equals(type)) {
                // сравниваем с текущим событием напрямую, не лезем в БД:
                // к моменту проверки сообщение уже сохранено, запрос вернул бы true
                // для всех предыдущих совпадений — запускали бы лишние экземпляры
                if (event.messageType() == null || event.templateName() == null) return false;
                String requiredType = (String) criteria.get("messageType");
                String requiredTemplate = (String) criteria.get("templateName");
                return event.messageType().name().equals(requiredType)
                        && event.templateName().equals(requiredTemplate);
            }

            ExecutionContext context = buildContext(event);
            return criterionEvaluator.evaluate(criteriaJson, context, null);
        } catch (Exception e) {
            log.error("Failed to evaluate start criterion: {}", criteriaJson, e);
            return false;
        }
    }

    private void checkStopCriteria(NormalizedEvent event) {
        List<ExecutionInstance> activeInstances = executionRepository.findActiveByAircraftId(event.aircraftId());

        for (ExecutionInstance instance : activeInstances) {
            Sequence sequence = sequenceQuery.findById(instance.getSequenceId()).orElse(null);
            if (sequence == null) {
                log.warn("Sequence {} not found for instance {}", instance.getSequenceId(), instance.getId());
                continue;
            }

            if (sequence.getStopCriteriaJson() == null || sequence.getStopCriteriaJson().isBlank()) {
                continue;
            }

            ExecutionContext context = buildContext(event);
            boolean criterionMet = criterionEvaluator.evaluate(sequence.getStopCriteriaJson(), context, null);

            if (criterionMet) {
                log.info("Stop criteria met for instance {} of sequence {}", instance.getId(), sequence.getId());
                abortExecution(instance);
            }
        }
    }

    private void processWaitingInstances(NormalizedEvent event) {
        // findActiveByAircraftId возвращает RUNNING+WAITING, фильтруем здесь —
        // один запрос вместо двух, и список уже в транзакции
        List<ExecutionInstance> waitingInstances = executionRepository.findActiveByAircraftId(event.aircraftId())
                .stream()
                .filter(inst -> inst.getStatus() == ExecutionStatus.WAITING)
                .toList();

        for (ExecutionInstance instance : waitingInstances) {
            tryResumeWaitingInstance(instance, event);
        }
    }

    private void tryResumeWaitingInstance(ExecutionInstance instance, NormalizedEvent event) {
        Sequence sequence = sequenceQuery.findById(instance.getSequenceId()).orElse(null);
        if (sequence == null) {
            log.warn("Sequence {} not found for instance {}", instance.getSequenceId(), instance.getId());
            return;
        }

        Step currentStep = sequence.getSteps().stream()
                .filter(s -> s.getOrderIndex().equals(instance.getCurrentStepIndex()))
                .findFirst()
                .orElse(null);

        if (currentStep == null) {
            log.warn("Current step {} not found in sequence {}", instance.getCurrentStepIndex(), sequence.getId());
            return;
        }

        ExecutionContext context = buildContext(event);
        StepResult result = ecaRuleEngine.executeStep(currentStep, instance, context);

        if (result != null) {
            log.info("WAIT step resolved with result {} for instance {}", result, instance.getId());
            advanceExecution(instance, currentStep, result);
        }
    }

    public void startExecution(Long sequenceId, String aircraftId, String flightNumber) {
        Sequence sequence = sequenceQuery.findById(sequenceId)
                .orElseThrow(() -> new IllegalArgumentException("Sequence not found: " + sequenceId));

        if (sequence.getSteps().isEmpty()) {
            log.warn("Cannot start execution: sequence {} has no steps", sequenceId);
            return;
        }

        ExecutionInstance instance = ExecutionInstance.builder()
                .sequenceId(sequenceId)
                .aircraftId(aircraftId)
                .flightNumber(flightNumber)
                .status(ExecutionStatus.RUNNING)
                .currentStepIndex(1)
                .contextJson("{}")
                .build();

        instance = executionRepository.save(instance);
        log.info("Started execution instance {} for sequence {} and aircraft {}", instance.getId(), sequenceId, aircraftId);

        eventPublisher.publishEvent(new ExecutionStartedEvent(
                instance.getId(),
                sequenceId,
                aircraftId,
                flightNumber
        ));

        // берём по orderIndex, а не по позиции в списке — порядок может расходиться
        Step firstStep = sequence.getSteps().stream()
                .min(java.util.Comparator.comparingInt(Step::getOrderIndex))
                .orElseThrow();
        ExecutionContext context = buildDefaultContext(aircraftId, flightNumber);
        StepResult result = ecaRuleEngine.executeStep(firstStep, instance, context);

        if (result != null) {
            advanceExecution(instance, firstStep, result);
        }
    }

    public void advanceExecution(ExecutionInstance instance, Step step, StepResult result) {
        advanceExecution(instance, step, result, 0);
    }

    private void advanceExecution(ExecutionInstance instance, Step step, StepResult result, int transitionCount) {
        TransitionAction action;
        Integer transitionTarget = null;
        boolean notify;

        if (result == StepResult.SUCCESS) {
            action = step.getOnSuccessAction();
            transitionTarget = step.getOnSuccessGotoStep();
            notify = step.getOnSuccessNotify() != null && step.getOnSuccessNotify();
        } else {
            action = step.getOnFailureAction();
            transitionTarget = step.getOnFailureGotoStep();
            notify = step.getOnFailureNotify() != null && step.getOnFailureNotify();
        }

        StepExecution stepExecution = StepExecution.builder()
                .executionInstance(instance)
                .stepIndex(step.getOrderIndex())
                .stepType(step.getStepType())
                .result(result)
                .transitionAction(action)
                .transitionTarget(transitionTarget)
                .build();

        instance.getStepHistory().add(stepExecution);

        // WAIT-шаг resolved (SUCCESS/FAILURE) — его окно ожидания закрыто. Не очищать здесь
        // waitStartedAt/waitTimeoutAt означает, что при повторном визите того же или другого
        // WAIT-шага (например через GOTO назад) WaitStepRule увидит УЖЕ истёкший таймаут от
        // предыдущего визита и немедленно вернёт FAILURE без реального ожидания — баг,
        // который проявляется именно в сценариях GOTO-назад на WAIT (паритет с SITA требует,
        // чтобы каждый новый визит WAIT-шага открывал новое окно ожидания).
        // Только для StepType.WAIT: ACTION WAIT_TIME использует эти же поля как метаданные
        // о вычисленной длительности паузы (см. ActionStepRule) и должен сохранять их после
        // завершения шага — это другая семантика, не активное ожидание критерия.
        if (step.getStepType() == StepType.WAIT) {
            instance.setWaitStartedAt(null);
            instance.setWaitTimeoutAt(null);
        }

        Integer nextStepIndex = determineNextStep(instance, action, transitionTarget);
        eventPublisher.publishEvent(new StepTransitionEvent(
                instance.getId(),
                step.getOrderIndex(),
                nextStepIndex,
                result,
                action
        ));

        if (notify) {
            notifyStepCompletion(instance, step, result);
        }

        // лимит — защита от бесконечного синхронного цикла CONTINUE/GOTO без WAIT (см. MAX_SYNCHRONOUS_TRANSITIONS)
        if (transitionCount >= MAX_SYNCHRONOUS_TRANSITIONS) {
            log.error("Synchronous transition limit ({}) exceeded for instance {} — aborting to prevent infinite loop",
                    MAX_SYNCHRONOUS_TRANSITIONS, instance.getId());
            abortExecution(instance);
            return;
        }

        executeTransition(instance, action, transitionTarget, transitionCount + 1);
    }

    private Integer determineNextStep(ExecutionInstance instance, TransitionAction action, Integer target) {
        return switch (action) {
            case CONTINUE -> instance.getCurrentStepIndex() + 1;
            case GOTO -> target;
            case END, ABORT -> null;
        };
    }

    private void executeTransition(ExecutionInstance instance, TransitionAction action, Integer transitionTarget,
                                    int transitionCount) {
        Sequence sequence = sequenceQuery.findById(instance.getSequenceId()).orElseThrow();

        switch (action) {
            case CONTINUE -> {
                int nextIndex = instance.getCurrentStepIndex() + 1;
                if (nextIndex <= sequence.getSteps().size()) {
                    instance.setCurrentStepIndex(nextIndex);
                    instance.setStatus(ExecutionStatus.RUNNING);
                    executionRepository.save(instance);

                    Step nextStep = sequence.getSteps().stream()
                            .filter(s -> s.getOrderIndex().equals(nextIndex))
                            .findFirst()
                            .orElseThrow();

                    ExecutionContext context = buildDefaultContext(instance.getAircraftId(), instance.getFlightNumber());
                    StepResult result = ecaRuleEngine.executeStep(nextStep, instance, context);

                    if (result != null) {
                        advanceExecution(instance, nextStep, result, transitionCount);
                    }
                } else {
                    completeExecution(instance);
                }
            }
            case GOTO -> {
                // невалидный GOTO → ABORT, не исключение:
                // последовательность не должна ронять весь поток событий для других ВС
                if (transitionTarget == null || transitionTarget < 1 || transitionTarget > sequence.getSteps().size()) {
                    log.error("Invalid GOTO target {} for instance {}", transitionTarget, instance.getId());
                    abortExecution(instance);
                    return;
                }

                // GOTO поддерживает переход и назад (target < currentStepIndex), и вперёд
                // (target > currentStepIndex) — это не ограничивается, как в SITA Sequencer;
                // защита от бесконечного цикла — лимит transitionCount в advanceExecution.
                instance.setCurrentStepIndex(transitionTarget);
                instance.setStatus(ExecutionStatus.RUNNING);
                executionRepository.save(instance);

                Step targetStep = sequence.getSteps().stream()
                        .filter(s -> s.getOrderIndex().equals(transitionTarget))
                        .findFirst()
                        .orElseThrow();

                ExecutionContext context = buildDefaultContext(instance.getAircraftId(), instance.getFlightNumber());
                StepResult result = ecaRuleEngine.executeStep(targetStep, instance, context);

                if (result != null) {
                    advanceExecution(instance, targetStep, result, transitionCount);
                }
            }
            case END -> completeExecution(instance);
            case ABORT -> abortExecution(instance);
        }
    }

    private void completeExecution(ExecutionInstance instance) {
        instance.setStatus(ExecutionStatus.COMPLETED);
        instance.setCompletedAt(LocalDateTime.now());
        executionRepository.save(instance);

        log.info("Execution instance {} completed", instance.getId());
        eventPublisher.publishEvent(new ExecutionCompletedEvent(instance.getId(), ExecutionStatus.COMPLETED));
    }

    private void abortExecution(ExecutionInstance instance) {
        instance.setStatus(ExecutionStatus.ABORTED);
        instance.setCompletedAt(LocalDateTime.now());
        executionRepository.save(instance);

        log.info("Execution instance {} aborted", instance.getId());
        eventPublisher.publishEvent(new ExecutionCompletedEvent(instance.getId(), ExecutionStatus.ABORTED));
    }

    private void notifyStepCompletion(ExecutionInstance instance, Step step, StepResult result) {
        String message = String.format("Step %d (%s) completed with result %s for aircraft %s",
                step.getOrderIndex(), step.getName(), result, instance.getAircraftId());

        notificationPort.notifyStepResult(
                instance.getId(),
                step.getOrderIndex(),
                result.name(),
                instance.getAircraftId(),
                message
        );

        eventPublisher.publishEvent(new StepNotificationEvent(
                instance.getId(),
                step.getOrderIndex(),
                result,
                instance.getAircraftId(),
                result == StepResult.SUCCESS
        ));
    }

    /** каждые 10 сек переводим просроченные WAIT-шаги в FAILURE */
    @Scheduled(fixedRate = 10000)
    public void checkWaitTimeouts() {
        LocalDateTime now = LocalDateTime.now();
        // NOTE: нужен составной индекс по (status, wait_timeout_at) —
        // без него при сотнях активных экземпляров это full scan каждые 10 сек
        List<ExecutionInstance> expiredInstances = executionRepository.findWaitingWithExpiredTimeout(now);

        for (ExecutionInstance instance : expiredInstances) {
            log.info("Timeout expired for instance {} at {}", instance.getId(), instance.getWaitTimeoutAt());

            Sequence sequence = sequenceQuery.findById(instance.getSequenceId()).orElse(null);
            if (sequence == null) {
                continue;
            }

            Step currentStep = sequence.getSteps().stream()
                    .filter(s -> s.getOrderIndex().equals(instance.getCurrentStepIndex()))
                    .findFirst()
                    .orElse(null);

            if (currentStep != null) {
                advanceExecution(instance, currentStep, StepResult.FAILURE);
            }
        }
    }

    private ExecutionContext buildContext(NormalizedEvent event) {
        Map<String, Object> additionalData = new HashMap<>();

        if (event.aircraftId() != null) {
            Set<String> activeConditions = conditionQueryPort.getActiveConditions(event.aircraftId());
            Map<String, Boolean> conditionsMap = new HashMap<>();
            for (String conditionName : activeConditions) {
                conditionsMap.put(conditionName, true);
            }
            additionalData.put("activeConditions", conditionsMap);
        }

        return new ExecutionContext(
                event.aircraftId(),
                event.flightNumber(),
                event.flightStage(),
                event.timestamp(),
                additionalData
        );
    }

    private ExecutionContext buildDefaultContext(String aircraftId, String flightNumber) {
        Map<String, Object> additionalData = new HashMap<>();
        if (aircraftId != null) {
            Set<String> conditions = conditionQueryPort.getActiveConditions(aircraftId);
            if (!conditions.isEmpty()) {
                Map<String, Boolean> conditionsMap = new HashMap<>();
                conditions.forEach(c -> conditionsMap.put(c, true));
                additionalData.put("activeConditions", conditionsMap);
            }
        }
        // INIT как нейтральная стадия при запуске первого шага —
        // реальная стадия придёт с первым NormalizedEvent для этого ВС
        return new ExecutionContext(
                aircraftId,
                flightNumber,
                FlightStage.INIT,
                LocalDateTime.now(),
                additionalData
        );
    }
}
