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
import ru.protectinfotrans.eca.sequence.domain.TransitionAction;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Центральный сервис выполнения последовательностей.
 * Реализует 4 информационных потока из раздела 1.4.3 диплома.
 *
 * Основные обязанности:
 * - Слушать NormalizedEvent и обрабатывать события (UC-06)
 * - Проверять start/stop критерии
 * - Управлять жизненным циклом экземпляров выполнения
 * - Координировать переходы между шагами через EcaRuleEngine
 * - Обрабатывать таймауты WAIT-шагов (UC-08)
 *
 * См. диплом: раздел 1.3.5 (UC-06, UC-07, UC-08), раздел 1.4.3
 */
@Service
@Transactional
@RequiredArgsConstructor
@Slf4j
public class ExecutionService {

    private final ExecutionRepositoryPort executionRepository;
    private final SequenceQueryPort sequenceQuery;
    private final EcaRuleEngine ecaRuleEngine;
    private final CriterionEvaluator criterionEvaluator;
    private final NotificationPort notificationPort;
    private final ConditionQueryPort conditionQueryPort;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    /**
     * UC-06: Обработать входящее событие.
     * Основная точка входа при получении любого события от Event Processor.
     * Слушает NormalizedEvent через Spring Modulith events.
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
     * Проверить start критерии всех активных последовательностей.
     * Если критерии совпадают — запустить новый экземпляр для данного ВС.
     */
    private void checkStartCriteria(NormalizedEvent event) {
        List<Sequence> activeSequences = sequenceQuery.findAllByStatus(SequenceStatus.ACTIVE);

        for (Sequence sequence : activeSequences) {
            if (sequence.getStartCriteriaJson() == null || sequence.getStartCriteriaJson().isBlank()) {
                // Пустой start criteria = запуск в начале каждого нового рейса (FlightStage = INIT)
                if (event.flightStage() == FlightStage.INIT) {
                    startExecution(sequence.getId(), event.aircraftId(), event.flightNumber());
                }
            } else {
                ExecutionContext context = buildContext(event);
                boolean criterionMet = criterionEvaluator.evaluate(sequence.getStartCriteriaJson(), context, null);

                if (criterionMet) {
                    log.info("Start criteria met for sequence {} and aircraft {}", sequence.getId(), event.aircraftId());
                    startExecution(sequence.getId(), event.aircraftId(), event.flightNumber());
                }
            }
        }
    }

    /**
     * Проверить stop критерии всех активных экземпляров данного ВС.
     * Если критерии совпадают — прервать выполнение (ABORT).
     */
    private void checkStopCriteria(NormalizedEvent event) {
        List<ExecutionInstance> activeInstances = executionRepository.findActiveByAircraftId(event.aircraftId());

        for (ExecutionInstance instance : activeInstances) {
            Sequence sequence = sequenceQuery.findById(instance.getSequenceId()).orElse(null);
            if (sequence == null) {
                log.warn("Sequence {} not found for instance {}", instance.getSequenceId(), instance.getId());
                continue;
            }

            if (sequence.getStopCriteriaJson() == null || sequence.getStopCriteriaJson().isBlank()) {
                continue; // Нет stop критериев
            }

            ExecutionContext context = buildContext(event);
            boolean criterionMet = criterionEvaluator.evaluate(sequence.getStopCriteriaJson(), context, null);

            if (criterionMet) {
                log.info("Stop criteria met for instance {} of sequence {}", instance.getId(), sequence.getId());
                abortExecution(instance);
            }
        }
    }

    /**
     * Обработать все WAITING экземпляры данного ВС.
     * Попытаться снова выполнить WAIT-шаг — возможно условие уже выполнено.
     */
    private void processWaitingInstances(NormalizedEvent event) {
        List<ExecutionInstance> waitingInstances = executionRepository.findActiveByAircraftId(event.aircraftId())
                .stream()
                .filter(inst -> inst.getStatus() == ExecutionStatus.WAITING)
                .toList();

        for (ExecutionInstance instance : waitingInstances) {
            tryResumeWaitingInstance(instance, event);
        }
    }

    /**
     * Попытаться возобновить WAITING экземпляр.
     */
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
            // Условие выполнено или таймаут истёк
            log.info("WAIT step resolved with result {} for instance {}", result, instance.getId());
            advanceExecution(instance, currentStep, result);
        }
        // Иначе продолжаем ждать
    }

    /**
     * Создать новый экземпляр выполнения и запустить первый шаг.
     */
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

        // Выполнить первый шаг
        Step firstStep = sequence.getSteps().get(0);
        ExecutionContext context = buildDefaultContext(aircraftId, flightNumber);
        StepResult result = ecaRuleEngine.executeStep(firstStep, instance, context);

        if (result != null) {
            advanceExecution(instance, firstStep, result);
        }
    }

    /**
     * Продвинуть выполнение: определить transition, записать историю, перейти к следующему шагу.
     * Result Decision Maker: CONTINUE → следующий по порядку, GOTO → указанный шаг, END → завершить, ABORT → прервать.
     * Если notify=true для данного результата → опубликовать StepNotificationEvent.
     */
    public void advanceExecution(ExecutionInstance instance, Step step, StepResult result) {
        // Определить действие на основе результата
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

        // Записать в историю
        StepExecution stepExecution = StepExecution.builder()
                .executionInstance(instance)
                .stepIndex(step.getOrderIndex())
                .stepType(step.getStepType())
                .result(result)
                .transitionAction(action)
                .transitionTarget(transitionTarget)
                .build();

        instance.getStepHistory().add(stepExecution);

        // Опубликовать событие перехода
        Integer nextStepIndex = determineNextStep(instance, action, transitionTarget);
        eventPublisher.publishEvent(new StepTransitionEvent(
                instance.getId(),
                step.getOrderIndex(),
                nextStepIndex,
                result,
                action
        ));

        // Уведомление
        if (notify) {
            notifyStepCompletion(instance, step, result);
        }

        // Выполнить переход
        executeTransition(instance, action, transitionTarget);
    }

    private Integer determineNextStep(ExecutionInstance instance, TransitionAction action, Integer target) {
        return switch (action) {
            case CONTINUE -> instance.getCurrentStepIndex() + 1;
            case GOTO -> target;
            case END, ABORT -> null;
        };
    }

    private void executeTransition(ExecutionInstance instance, TransitionAction action, Integer transitionTarget) {
        Sequence sequence = sequenceQuery.findById(instance.getSequenceId()).orElseThrow();

        switch (action) {
            case CONTINUE -> {
                int nextIndex = instance.getCurrentStepIndex() + 1;
                if (nextIndex <= sequence.getSteps().size()) {
                    instance.setCurrentStepIndex(nextIndex);
                    instance.setStatus(ExecutionStatus.RUNNING);
                    executionRepository.save(instance);

                    // Выполнить следующий шаг
                    Step nextStep = sequence.getSteps().stream()
                            .filter(s -> s.getOrderIndex().equals(nextIndex))
                            .findFirst()
                            .orElseThrow();

                    ExecutionContext context = buildDefaultContext(instance.getAircraftId(), instance.getFlightNumber());
                    StepResult result = ecaRuleEngine.executeStep(nextStep, instance, context);

                    if (result != null) {
                        advanceExecution(instance, nextStep, result);
                    }
                } else {
                    // Достигнут конец последовательности
                    completeExecution(instance);
                }
            }
            case GOTO -> {
                if (transitionTarget == null || transitionTarget < 1 || transitionTarget > sequence.getSteps().size()) {
                    log.error("Invalid GOTO target {} for instance {}", transitionTarget, instance.getId());
                    abortExecution(instance);
                    return;
                }

                instance.setCurrentStepIndex(transitionTarget);
                instance.setStatus(ExecutionStatus.RUNNING);
                executionRepository.save(instance);

                // Выполнить целевой шаг
                Step targetStep = sequence.getSteps().stream()
                        .filter(s -> s.getOrderIndex().equals(transitionTarget))
                        .findFirst()
                        .orElseThrow();

                ExecutionContext context = buildDefaultContext(instance.getAircraftId(), instance.getFlightNumber());
                StepResult result = ecaRuleEngine.executeStep(targetStep, instance, context);

                if (result != null) {
                    advanceExecution(instance, targetStep, result);
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

    /**
     * UC-08: Периодическая проверка WAITING экземпляров с истёкшим таймаутом.
     * Выполняется каждые 10 секунд.
     */
    @Scheduled(fixedRate = 10000)
    public void checkWaitTimeouts() {
        LocalDateTime now = LocalDateTime.now();
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
                // Таймаут истёк → результат FAILURE
                advanceExecution(instance, currentStep, StepResult.FAILURE);
            }
        }
    }

    private ExecutionContext buildContext(NormalizedEvent event) {
        Map<String, Object> additionalData = new HashMap<>();

        // Добавляем активные условия для CONDITION_ACTIVE критерия
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
        return new ExecutionContext(
                aircraftId,
                flightNumber,
                FlightStage.INIT,
                LocalDateTime.now(),
                new HashMap<>()
        );
    }
}
