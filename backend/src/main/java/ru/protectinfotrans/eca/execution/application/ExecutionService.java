package ru.protectinfotrans.eca.execution.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.protectinfotrans.eca.CorrelationContext;
import ru.protectinfotrans.eca.FlightStage;
import ru.protectinfotrans.eca.eventprocessor.event.NormalizedEvent;
import ru.protectinfotrans.eca.execution.domain.ExecutionInstance;
import ru.protectinfotrans.eca.execution.domain.ExecutionStatus;
import ru.protectinfotrans.eca.execution.domain.InstanceContext;
import ru.protectinfotrans.eca.execution.domain.StepExecution;
import ru.protectinfotrans.eca.execution.domain.StepResult;
import ru.protectinfotrans.eca.execution.domain.TrackingEventLog;
import ru.protectinfotrans.eca.execution.domain.TrackingEventType;
import ru.protectinfotrans.eca.execution.dto.ExecutionContext;
import ru.protectinfotrans.eca.execution.event.ExecutionCompletedEvent;
import ru.protectinfotrans.eca.execution.event.ExecutionStartedEvent;
import ru.protectinfotrans.eca.execution.event.StepNotificationEvent;
import ru.protectinfotrans.eca.execution.event.StepTransitionEvent;
import ru.protectinfotrans.eca.execution.port.out.ConditionQueryPort;
import ru.protectinfotrans.eca.execution.port.out.ExecutionRepositoryPort;
import ru.protectinfotrans.eca.execution.port.out.SequenceQueryPort;
import ru.protectinfotrans.eca.execution.port.out.NotificationPort;
import ru.protectinfotrans.eca.execution.port.out.TrackingEventLogPort;
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
/*
 * P1-6: класс БОЛЬШЕ НЕ помечен общим {@code @Transactional} (как было до P1-6). Раньше эта
 * классовая аннотация означала, что {@link #processEvent} целиком — включая фан-аут по ВСЕМ
 * затронутым инстансам в {@code checkStartCriteria}/{@code checkStopCriteria}/
 * {@code processWaitingInstances} — выполнялся в ОДНОЙ транзакции: длинная транзакция, держащая
 * блокировки строк всех затронутых инстансов до самого конца, и при сбое (включая оптимистический
 * конфликт версии после включения {@code @Version}) откатывались бы заодно и уже корректно
 * обработанные соседние инстансы. Теперь транзакционность расставлена точечно на каждом методе,
 * который должен быть атомарной единицей: per-instance операции — {@code REQUIRES_NEW} (см.
 * {@link #checkStopCriterionTransactional}, {@link #tryResumeWaitingInstanceTransactional},
 * {@link #claimAndAdvanceTimeout}, {@link #resumeRunningInstanceAfterRestart}); цепочка переходов
 * ОДНОГО инстанса ({@link #startExecution}, {@link #advanceExecution}) — обычная {@code @Transactional}
 * (REQUIRED) каждая своя; верхнеуровневые обходчики кандидатов ({@link #processEvent},
 * {@link #checkWaitTimeouts}) сами НЕ транзакционны — они только читают read-only список кандидатов
 * и диспетчеризуют каждого в его собственную транзакцию.
 */
@Service
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
    private final InstanceContextCodec instanceContextCodec;
    private final TrackingEventLogPort trackingEventLogPort;

    /**
     * P1-5: self-инъекция через {@code ObjectProvider}, а не прямое поле {@code ExecutionService},
     * чтобы получить Spring AOP-прокси САМОГО СЕБЯ для вызова {@link #claimAndAdvanceTimeout}
     * из {@link #checkWaitTimeouts} (тот же класс). Прямой вызов {@code this.claimAndAdvanceTimeout(...)}
     * был бы self-invocation — Spring transactional proxy НЕ перехватывает вызовы метода на
     * {@code this} внутри того же объекта, поэтому {@code @Transactional(REQUIRES_NEW)} на
     * {@code claimAndAdvanceTimeout} был бы безмолвно проигнорирован (выполнился бы в текущей,
     * НЕ новой транзакции, или вовсе без транзакции, если вызывающий метод не транзакционен).
     * {@code ObjectProvider<ExecutionService>} разрывает цикл конструкторной DI (обычное
     * {@code final ExecutionService self}-поле в конструкторе с {@code @RequiredArgsConstructor}
     * привело бы к само-зависимости бина от самого себя на этапе создания) и резолвится в
     * прокси лениво, при первом обращении в {@code checkWaitTimeouts}, когда контекст уже
     * полностью поднят.
     */
    private final ObjectProvider<ExecutionService> self;

    /**
     * P1-6: ограничение количества попыток retry при конфликте оптимистической блокировки
     * (см. {@link #withOptimisticRetry}). Конфликт версии под реальной нагрузкой — событие
     * довольно редкое (один и тот же инстанс должен быть конкурентно тронут ДВУМЯ событиями
     * почти одновременно) и почти всегда разрешается за 1-2 повторных попытки; ограничение
     * нужно лишь как защита от патологического случая (бесконечный retry если что-то системно
     * не так), а не как ожидаемый рабочий путь.
     */
    public static final int MAX_OPTIMISTIC_LOCK_RETRIES = 5;

    /**
     * P1-6: точка входа для всех событий от Event Processor. НЕ {@code @Transactional}
     * (как и {@link #checkWaitTimeouts}) — один {@code NormalizedEvent} может затронуть
     * МНОГО инстансов (фан-аут: много последовательностей на один борт; одна последовательность
     * на много бортов, у каждого свой указатель шага — см. CLAUDE.md). Раньше вся обработка шла
     * в ОДНОЙ транзакции класса ({@code @Transactional} на уровне класса) — длинная транзакция,
     * держащая блокировки строк ВСЕХ затронутых инстансов до самого конца обработки события, и
     * при сбое одного инстанса (включая оптимистический конфликт версии) откатывались бы заодно
     * и уже обработанные соседние инстансы. Теперь каждый кандидат (start/stop/waiting) обрабатывается
     * в СОБСТВЕННОЙ транзакции: для start (новый инстанс — INSERT, конфликт версии невозможен
     * по построению) — {@link #startExecution} (REQUIRED); для stop/waiting на УЖЕ существующем
     * инстансе — {@link #checkStopCriterionTransactional}, {@link #tryResumeWaitingInstanceTransactional}
     * (обе REQUIRES_NEW) —
     * так же, как уже сделано для таймаутов (P1-5) и resume (P1-4). Инстансы независимы — их
     * обработка корректна и при выполнении строго последовательно в одном потоке (как сейчас вызывает
     * {@code @ApplicationModuleListener}), и при потенциальном параллельном фан-ауте в будущем.
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
     * P1-6: выполняет {@code action} с bounded retry при конфликте оптимистической блокировки
     * ({@link ObjectOptimisticLockingFailureException}). Стратегия — retry, а не skip: конфликт
     * версии означает, что СТРОКА инстанса изменилась между чтением и записью в рамках текущей
     * попытки (например другое событие на тот же борт успело продвинуть тот же инстанс первым) —
     * сам факт конфликта НЕ говорит, что текущее событие уже неактуально или что его обработка
     * не нужна: оно всё ещё должно быть учтено (например stop-критерий должен сработать, WAIT
     * должен резолвиться) над УЖЕ актуальным состоянием инстанса. Безусловный skip потерял бы
     * обработку этого события для данного инстанса. Каждая попытка перечитывает инстанс заново
     * (через {@code Long instanceId}, не закэшированный объект) внутри новой {@code REQUIRES_NEW}
     * транзакции — поэтому повторная попытка всегда видит актуальную (post-conflict) версию строки,
     * а не повторяет тот же устаревший update.
     *
     * <p>Если инстанс за время retry успел перейти в терминальный статус (COMPLETED/ABORTED) —
     * вызываемый {@code action} сам обязан это обнаружить (перечитывает инстанс по id и проверяет
     * статус) и no-op'нуться идемпотентно, не предполагая, что переданный {@code instanceId} ещё
     * активен. Это и предотвращает "двойной переход/двойной побочный эффект" под конкуренцией —
     * не сам retry-wrapper, а то, что каждая транзакция начинается с перечитывания актуального
     * состояния, а не с слепого повтора прежнего намерения.
     */
    private void withOptimisticRetry(Long instanceId, String operationName, Runnable action) {
        int attempt = 0;
        while (true) {
            try {
                action.run();
                return;
            } catch (ObjectOptimisticLockingFailureException e) {
                attempt++;
                if (attempt >= MAX_OPTIMISTIC_LOCK_RETRIES) {
                    log.error("Optimistic lock conflict on instance {} ({}) — giving up after {} attempts",
                            instanceId, operationName, attempt, e);
                    return;
                }
                log.debug("Optimistic lock conflict on instance {} ({}) — retry attempt {}/{}",
                        instanceId, operationName, attempt, MAX_OPTIMISTIC_LOCK_RETRIES);
            }
        }
    }

    /**
     * Для MESSAGE_RECEIVED сравниваем с текущим событием напрямую —
     * запрос к БД давал ложные срабатывания на старых сообщениях.
     *
     * <p>P1-7 (ADR-0002): {@code event.messageId()} прокидывается в {@link #startExecution}
     * как {@code triggeringMessageId} — дедуп-ключ против повторной доставки ЭТОГО ЖЕ
     * {@code NormalizedEvent} (republish-on-restart/retry Spring Modulith Event Publication
     * Registry). {@code messageId} может быть {@code null} для системных событий без
     * исходного сообщения (например {@code EventProcessorService#notifyFlightStageChange}) —
     * для них дедуп по {@code triggeringMessageId} неприменим (см. javadoc {@link #startExecution}).
     */
    private void checkStartCriteria(NormalizedEvent event) {
        List<Sequence> activeSequences = sequenceQuery.findAllByStatus(SequenceStatus.ACTIVE);

        for (Sequence sequence : activeSequences) {
            if (sequence.getStartCriteriaJson() == null || sequence.getStartCriteriaJson().isBlank()) {
                if (event.flightStage() == FlightStage.INIT) {
                    startExecution(sequence.getId(), event.aircraftId(), event.flightNumber(), event.messageId());
                }
            } else {
                boolean criterionMet = matchesStartCriteria(sequence.getStartCriteriaJson(), event);
                if (criterionMet) {
                    log.info("Start criteria met for sequence {} and aircraft {}", sequence.getId(), event.aircraftId());
                    startExecution(sequence.getId(), event.aircraftId(), event.flightNumber(), event.messageId());
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

    /**
     * P1-6: список кандидатов читается ОДНИМ read-only запросом (без захвата строк — как и
     * {@code findWaitingWithExpiredTimeout} в P1-5), а сама обработка каждого кандидата уходит
     * в {@link #checkStopCriterionTransactional} — собственная {@code REQUIRES_NEW}-транзакция
     * на инстанс, вызванная через {@link #self} (Spring AOP-прокси, без self-invocation — см.
     * javadoc поля {@link #self}). Конфликт версии на конкретном инстансе оборачивается
     * {@link #withOptimisticRetry}.
     */
    private void checkStopCriteria(NormalizedEvent event) {
        List<ExecutionInstance> activeInstances = executionRepository.findActiveByAircraftId(event.aircraftId());

        for (ExecutionInstance instance : activeInstances) {
            Long instanceId = instance.getId();
            withOptimisticRetry(instanceId, "checkStopCriteria",
                    () -> self.getObject().checkStopCriterionTransactional(instanceId, event));
        }
    }

    /**
     * P1-6: обрабатывает stop-критерий ОДНОГО инстанса в собственной транзакции. Перечитывает
     * инстанс по id (а не использует объект, переданный из {@link #checkStopCriteria}) — это
     * актуальное, управляемое ТЕКУЩЕЙ транзакцией состояние, в т.ч. актуальная версия для
     * оптимистической блокировки; устаревший detached-объект из внешнего read-only списка
     * привёл бы к гарантированному конфликту версии на первом же save(), а не к полезному retry.
     * Идемпотентен: если инстанс уже не активен (другой конкурентный путь его завершил раньше),
     * no-op.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void checkStopCriterionTransactional(Long instanceId, NormalizedEvent event) {
        ExecutionInstance instance = executionRepository.findById(instanceId).orElse(null);
        if (instance == null || (instance.getStatus() != ExecutionStatus.RUNNING
                && instance.getStatus() != ExecutionStatus.WAITING)) {
            return;
        }

        Sequence sequence = sequenceQuery.findById(instance.getSequenceId()).orElse(null);
        if (sequence == null) {
            log.warn("Sequence {} not found for instance {}", instance.getSequenceId(), instance.getId());
            return;
        }

        if (sequence.getStopCriteriaJson() == null || sequence.getStopCriteriaJson().isBlank()) {
            return;
        }

        ExecutionContext context = buildContext(event);
        boolean criterionMet = criterionEvaluator.evaluate(sequence.getStopCriteriaJson(), context, null);

        if (criterionMet) {
            log.info("Stop criteria met for instance {} of sequence {}", instance.getId(), sequence.getId());
            abortExecution(instance);
        }
    }

    /**
     * P1-6: то же разделение на инстанс-в-своей-транзакции, что и {@link #checkStopCriteria}.
     */
    private void processWaitingInstances(NormalizedEvent event) {
        // findActiveByAircraftId возвращает RUNNING+WAITING, фильтруем здесь —
        // один read-only запрос вместо двух
        List<ExecutionInstance> waitingInstances = executionRepository.findActiveByAircraftId(event.aircraftId())
                .stream()
                .filter(inst -> inst.getStatus() == ExecutionStatus.WAITING)
                .toList();

        for (ExecutionInstance instance : waitingInstances) {
            Long instanceId = instance.getId();
            withOptimisticRetry(instanceId, "tryResumeWaitingInstance",
                    () -> self.getObject().tryResumeWaitingInstanceTransactional(instanceId, event));
        }
    }

    /**
     * P1-6: обрабатывает один WAITING-инстанс в собственной {@code REQUIRES_NEW}-транзакции —
     * перечитывает инстанс по id (см. {@link #checkStopCriterionTransactional} про детачмент и
     * устаревшую версию). Идемпотентен: если инстанс к моменту обработки уже не WAITING (резолвлен
     * конкурентно другим событием/таймаутом раньше), no-op.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void tryResumeWaitingInstanceTransactional(Long instanceId, NormalizedEvent event) {
        ExecutionInstance instance = executionRepository.findById(instanceId).orElse(null);
        if (instance == null || instance.getStatus() != ExecutionStatus.WAITING) {
            return;
        }

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

    /**
     * P1-4: повторно прогоняет текущий шаг RUNNING-инстанса, найденного при старте приложения
     * незавершённым (см. {@code ExecutionResumeRunner}). RUNNING на этом шаге означает, что
     * процесс упал между "перейти на шаг N и сохранить указатель" (executeTransition/startExecution
     * делают save() ДО executeStep) и "обработать результат шага N" (advanceExecution) — то есть
     * шаг N либо не успел выполниться вовсе, либо выполнился, но его результат/переход не были
     * обработаны. Единственный детерминированный путь восстановления — повторно выполнить шаг N
     * через тот же {@code ecaRuleEngine.executeStep} и тот же {@code advanceExecution}, которые
     * использует штатный поток (executeTransition/startExecution) — не вводим отдельную ветку логики.
     *
     * <p><b>Идемпотентность сейчас (обновлено P1-7/ADR-0002):</b> для EVALUATE/WAIT повторный
     * прогон безопасен и побочных эффектов не имеет (чистая проверка критерия). Для ACTION с
     * эффектом, видимым извне (SEND_UPLINK/SEND_GROUND), повторный прогон ПОСЛЕ рестарта может
     * повторно отправить сообщение, если шаг успел физически уйти во внешний канал до краша, но
     * до того, как advanceExecution передвинул currentStepIndex. ADR-0002 (docs/adr/ADR-0002-
     * transactional-outbox-vs-direct-call.md, Decision п.2) фиксирует, что {@code ActionStepRule}
     * → {@code MessageOutputPort} остаётся ПРЯМЫМ синхронным вызовом (не Outbox-событием) —
     * поэтому Outbox/republish (часть 2b) НЕ закрывает этот конкретный пробел, как предполагал
     * более ранний комментарий здесь. Цена сейчас нулевая — {@code MessageOutputPort} реализован
     * заглушкой ({@code LogMessageAdapter}), реального внешнего эффекта нет. Дедуп-ключ
     * {@code instance.id + stepIndex} остаётся верным дизайном для будущего реального адаптера —
     * переоценить при замене заглушки (см. ADR-0002, "Спецификация для реализации", п.2 строка
     * про {@code NotificationEventListener}/будущий ACARS-адаптер, и п.5 "Что НЕ входит в объём").
     * Сейчас гарантия — "at-least-once и не потеряно", не "exactly-once".
     *
     * <p><b>Транзакционная изоляция (P1-4, фикс ревью):</b> {@code REQUIRES_NEW}, а не
     * {@code REQUIRED} класса по умолчанию. {@code ExecutionResumeRunner#run} обходит ВСЕ
     * найденные RUNNING-инстансы в цикле и сам больше не открывает общую транзакцию на весь
     * цикл (см. его комментарий) — без {@code REQUIRES_NEW} здесь резюм одного инстанса делил
     * бы Hibernate-сессию/транзакцию со следующими вызовами в рамках вызывающего кода, и сбой,
     * инвалидирующий сессию при flush (constraint violation, optimistic lock и т.п., а не просто
     * бизнес-исключение), оставил бы EntityManager в невалидном состоянии для соседних инстансов.
     * {@code REQUIRES_NEW} коммитит/роллбэкает каждый инстанс в собственной транзакции —
     * сбой одного физически не достижим из транзакции другого. Вызывается ИЗ {@code ExecutionResumeRunner}
     * через внедрённый Spring-прокси этого бина (DI, не {@code this.method()}) — самовызов
     * (self-invocation) здесь не возникает, проксирование {@code @Transactional} работает.
     *
     * <p><b>Почему перечитываем instance по id, а не используем переданный объект напрямую:</b>
     * {@code instance} приходит из {@code ExecutionResumeRunner}, который сам больше не держит
     * общую транзакцию — он получил {@code instance} из {@code findAllActive()} в УЖЕ закрытой
     * транзакции/Hibernate-сессии (другой вызов репозитория). Внутри новой {@code REQUIRES_NEW}
     * транзакции этот объект — detached-entity с закрытой сессией: обращение к ленивым связям
     * (например {@code stepHistory} в {@code advanceExecution}) бросает
     * {@code LazyInitializationException}, даже если все скалярные поля читаются нормально.
     * Перечитывание по id привязывает работу к свежему, управляемому текущей транзакцией
     * объекту. Фоллбэк на переданный {@code instance}, если перечитать не удалось (id ещё не
     * задан или запись не найдена), сохраняет поведение по умолчанию для existing unit-тестов
     * на mock {@code ExecutionRepositoryPort}, которые не настраивают {@code findById} явно.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void resumeRunningInstanceAfterRestart(ExecutionInstance instance) {
        ExecutionInstance managedInstance = instance.getId() != null
                ? executionRepository.findById(instance.getId()).orElse(instance)
                : instance;

        Sequence sequence = sequenceQuery.findById(managedInstance.getSequenceId()).orElse(null);
        if (sequence == null) {
            log.warn("Resume: sequence {} not found for instance {} — leaving as is",
                    managedInstance.getSequenceId(), managedInstance.getId());
            return;
        }

        Step currentStep = sequence.getSteps().stream()
                .filter(s -> s.getOrderIndex().equals(managedInstance.getCurrentStepIndex()))
                .findFirst()
                .orElse(null);

        if (currentStep == null) {
            log.warn("Resume: current step {} not found in sequence {} for instance {} — leaving as is",
                    managedInstance.getCurrentStepIndex(), sequence.getId(), managedInstance.getId());
            return;
        }

        log.info("Resume: re-executing step {} ({}) for RUNNING instance {} after restart",
                currentStep.getOrderIndex(), currentStep.getStepType(), managedInstance.getId());

        ExecutionContext context = buildDefaultContext(managedInstance.getAircraftId(), managedInstance.getFlightNumber());
        StepResult result = ecaRuleEngine.executeStep(currentStep, managedInstance, context);

        if (result != null) {
            advanceExecution(managedInstance, currentStep, result);
        } else {
            // шаг сам перевёл инстанс в WAITING (например WAIT-шаг ещё не получил критерий) —
            // executeStep уже выставил waitStartedAt/waitTimeoutAt на instance, сохраняем снапшот
            executionRepository.save(managedInstance);
        }
    }

    /**
     * P1-6: явная {@code @Transactional} (REQUIRED) — раньше транзакция была неявной (классовая
     * аннотация). Старт нового инстанса — это всегда INSERT новой строки (а не update существующей),
     * поэтому конфликта версии при создании быть не может по построению; явная аннотация здесь
     * нужна для того, чтобы каждый старт (в т.ч. при фан-ауте на несколько последовательностей в
     * {@code checkStartCriteria}) был ОТДЕЛЬНОЙ атомарной транзакцией, а не делил одну большую с
     * остальными кандидатами события.
     *
     * <p>P1-7 (ADR-0002): сохранён как отдельная перегрузка БЕЗ {@code triggeringMessageId} для
     * вызывающих, у которых нет исходного {@code NormalizedEvent} (ручной/программный старт,
     * существующие тесты на демо-сценарии P1-1..P1-6) — делегирует на
     * {@link #startExecution(Long, String, String, Long)} с {@code triggeringMessageId = null},
     * что означает "дедуп по сообщению не применяется" (см. javadoc там).
     */
    @Transactional
    public void startExecution(Long sequenceId, String aircraftId, String flightNumber) {
        startExecution(sequenceId, aircraftId, flightNumber, null);
    }

    /**
     * P1-7 (ADR-0002, "Спецификация для реализации", п.1): идемпотентный старт нового
     * инстанса. {@code triggeringMessageId} — {@code NormalizedEvent.messageId}, чьё совпадение
     * со старт-критерием вызвало этот старт ({@link #checkStartCriteria}).
     *
     * <p><b>Дедуп-проверка применяется ТОЛЬКО когда {@code triggeringMessageId != null}.</b>
     * Причина: at-least-once доставка Spring Modulith Event Publication Registry
     * (republish-outstanding-events-on-restart, retry на транзиентной ошибке listener'а) может
     * повторно доставить ОДИН И ТОТ ЖЕ {@code NormalizedEvent} — без проверки повторная доставка
     * создала бы дублирующийся {@code ExecutionInstance} на каждый повтор. Дедуп-ключ —
     * {@code (sequenceId, aircraftId, flightNumber, triggeringMessageId)}, НЕ просто факт "есть
     * активный инстанс этой sequence для этого ВС": один борт может легитимно иметь несколько
     * ПОСЛЕДОВАТЕЛЬНЫХ инстансов одной и той же sequence за разные рейсы — это не дубликаты.
     *
     * <p>Если {@code triggeringMessageId == null} (старт НЕ от конкретного сообщения — например
     * {@code EventProcessorService#notifyFlightStageChange}, смена стадии без исходного
     * {@code IncomingMessage}, или ручной/программный старт), дедуп-проверка по нему
     * принципиально неприменима: {@code NULL} не идентифицирует конкретное "то самое" событие
     * (в SQL {@code NULL <> NULL}), и среди записей до V23 / без события {@code triggering_message_id}
     * у всех {@code NULL} — проверка "уже существует с {@code triggeringMessageId = NULL}" дала
     * бы ложные совпадения с НЕСВЯЗАННЫМИ прошлыми стартами той же sequence/ВС/рейса. Риск
     * повторной доставки для таких событий вне объёма этого ADR (см. "Что НЕ входит в объём
     * части 2") — он не нов и не увеличен этим изменением: до P1-7 дедупа для старта не было
     * вообще ни для одного случая, теперь он закрыт именно для доминирующего и материально
     * рискового случая (message-triggered старт).
     */
    @Transactional
    public void startExecution(Long sequenceId, String aircraftId, String flightNumber, Long triggeringMessageId) {
        if (triggeringMessageId != null
                && executionRepository.existsByDedupKey(sequenceId, aircraftId, flightNumber, triggeringMessageId)) {
            log.info("Skipping duplicate startExecution: instance already exists for sequence={}, aircraft={}, "
                            + "flight={}, triggeringMessageId={} (at-least-once redelivery, no-op)",
                    sequenceId, aircraftId, flightNumber, triggeringMessageId);
            return;
        }

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
                .contextJson(instanceContextCodec.encode(InstanceContext.empty()))
                .triggeringMessageId(triggeringMessageId)
                .build();

        instance = executionRepository.save(instance);
        log.info("Started execution instance {} for sequence {} and aircraft {}", instance.getId(), sequenceId, aircraftId);

        writeTrackingEvent(sequence, instance, TrackingEventType.SEQUENCE_STARTED, null, null,
                "{\"status\":\"RUNNING\"}");

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
        // первый шаг нового инстанса — restoreFromThisPointReferenceIfNeeded здесь не нужен:
        // нет "предыдущего resolved шага", чья точка отсчёта могла бы быть запомнена в контексте
        ExecutionContext context = buildDefaultContext(aircraftId, flightNumber);
        StepResult result = ecaRuleEngine.executeStep(firstStep, instance, context);

        if (result != null) {
            advanceExecution(instance, firstStep, result);
        }
    }

    /**
     * P1-6: явная {@code @Transactional} (REQUIRED). Продакшен-вызовы этого метода происходят
     * либо изнутри {@link #startExecution} (тоже REQUIRED — присоединяется к той же транзакции),
     * либо из {@code REQUIRES_NEW}-методов на конкретный инстанс ({@link #tryResumeWaitingInstanceTransactional},
     * {@link #resumeRunningInstanceAfterRestart}, {@link #claimAndAdvanceTimeout}) — REQUIRED
     * просто присоединяется к уже открытой транзакции без новой границы. Явная аннотация — защита
     * на случай вызова из кода без уже открытой транзакции (даёт ту же атомарность "один инстанс =
     * одна транзакция", что и остальные per-instance методы).
     */
    @Transactional
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

        Sequence sequence = sequenceQuery.findById(instance.getSequenceId()).orElse(null);
        if (sequence != null) {
            String detailsJson = String.format(
                    "{\"stepType\":\"%s\",\"action\":\"%s\",\"target\":%s}",
                    step.getStepType(), action, transitionTarget == null ? "null" : transitionTarget);
            writeTrackingEvent(sequence, instance, TrackingEventType.STEP_COMPLETED,
                    step.getOrderIndex(), result, detailsJson);
        }

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
            // точку отсчёта "from this point only" этого WAIT-шага запоминаем в персистентный
            // контекст ДО очистки waitStartedAt — иначе следующий шаг по CONTINUE (например
            // EVALUATE с fromThisPointOnly, читающий instance.getWaitStartedAt()) увидит null
            // и потеряет точку отсчёта, хотя WAIT только что её установил. Контекст переживает
            // и эту очистку, и рестарт сервиса (хранится в context JSONB).
            if (instance.getWaitStartedAt() != null) {
                rememberFromThisPointReference(instance, step.getOrderIndex(), instance.getWaitStartedAt());
            }
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
                Integer resolvedStepIndex = instance.getCurrentStepIndex();
                int nextIndex = instance.getCurrentStepIndex() + 1;
                if (nextIndex <= sequence.getSteps().size()) {
                    Step nextStep = sequence.getSteps().stream()
                            .filter(s -> s.getOrderIndex().equals(nextIndex))
                            .findFirst()
                            .orElseThrow();

                    instance.setCurrentStepIndex(nextIndex);
                    instance.setStatus(ExecutionStatus.RUNNING);
                    restoreFromThisPointReferenceIfNeeded(instance, resolvedStepIndex, nextStep);
                    // один save() — консистентный снапшот (указатель шага + статус + контекст)
                    executionRepository.save(instance);

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
                Integer resolvedStepIndex = instance.getCurrentStepIndex();
                // невалидный GOTO → ABORT, не исключение:
                // последовательность не должна ронять весь поток событий для других ВС
                if (transitionTarget == null || transitionTarget < 1 || transitionTarget > sequence.getSteps().size()) {
                    log.error("Invalid GOTO target {} for instance {}", transitionTarget, instance.getId());
                    abortExecution(instance);
                    return;
                }

                Step targetStep = sequence.getSteps().stream()
                        .filter(s -> s.getOrderIndex().equals(transitionTarget))
                        .findFirst()
                        .orElseThrow();

                // GOTO поддерживает переход и назад (target < currentStepIndex), и вперёд
                // (target > currentStepIndex) — это не ограничивается, как в SITA Sequencer;
                // защита от бесконечного цикла — лимит transitionCount в advanceExecution.
                instance.setCurrentStepIndex(transitionTarget);
                instance.setStatus(ExecutionStatus.RUNNING);
                restoreFromThisPointReferenceIfNeeded(instance, resolvedStepIndex, targetStep);
                // один save() — консистентный снапшот (указатель шага + статус + контекст)
                executionRepository.save(instance);

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

        sequenceQuery.findById(instance.getSequenceId()).ifPresent(sequence ->
                writeTrackingEvent(sequence, instance, TrackingEventType.SEQUENCE_STOPPED, null, null,
                        "{\"status\":\"COMPLETED\",\"reason\":\"END\"}"));

        eventPublisher.publishEvent(new ExecutionCompletedEvent(instance.getId(), ExecutionStatus.COMPLETED));
    }

    private void abortExecution(ExecutionInstance instance) {
        instance.setStatus(ExecutionStatus.ABORTED);
        instance.setCompletedAt(LocalDateTime.now());
        executionRepository.save(instance);

        log.info("Execution instance {} aborted", instance.getId());

        sequenceQuery.findById(instance.getSequenceId()).ifPresent(sequence ->
                writeTrackingEvent(sequence, instance, TrackingEventType.SEQUENCE_ABORTED, null, null,
                        "{\"status\":\"ABORTED\",\"reason\":\"ABORT\"}"));

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
     * P1-5: перебирает просроченные WAIT-таймауты — durable single-fire через атомарный
     * claim в БД, не in-memory расписание. Сам опрос ({@code @Scheduled}, см.
     * {@link WaitTimeoutScheduler}) — это только триггер; вся гарантия "ровно один раз" живёт
     * в строке БД (см. {@code ExecutionJpaRepository#claimExpiredTimeout}), не в этом методе и
     * не во внутреннем состоянии планировщика.
     *
     * <p><b>Зачем нужен claim, если состояние и так персистентно (P1-3/P1-4):</b> сам факт, что
     * {@code wait_timeout_at} лежит в БД и переживает рестарт, ещё не гарантирует, что переход
     * по таймауту выполнится РОВНО один раз — без claim'а при нескольких репликах backend
     * (или просто при перекрытии двух тиков {@code @Scheduled} из-за долгой обработки) один и
     * тот же просроченный инстанс мог бы быть прочитан {@code findWaitingWithExpiredTimeout}
     * параллельно более чем одним потоком/процессом и обработан (advanceExecution) дважды —
     * двойной переход по false-ветке, двойная отправка/уведомление. Это намеренно НЕ зона
     * leader election (P6-1): корректность single-fire здесь обеспечивается на уровне СТРОКИ
     * БД (атомарный claim), а не на уровне "кто сейчас лидер опроса" — поэтому она верна
     * ОДИНАКОВО для одной реплики, для нескольких реплик и для самопересечения тиков одной
     * реплики, без какой-либо распределённой координации.
     *
     * <p>Каждый инстанс обрабатывается в собственной {@code REQUIRES_NEW} транзакции
     * ({@link #claimAndAdvanceTimeout}) — по той же причине, что и {@code resumeRunningInstanceAfterRestart}
     * (P1-4): отдельная транзакция на инстанс, минимальное время удержания блокировки строки,
     * сбой одного инстанса не портит сессию/транзакцию для остальных кандидатов в этом тике.
     *
     * <p>Метод сам НЕ {@code @Transactional} (читает кандидатов одним read-only запросом —
     * консистентность каждого отдельного claim'а гарантируется в БД, не транзакцией-обёрткой
     * над всем циклом). Каждый {@link #claimAndAdvanceTimeout} вызывается ЧЕРЕЗ
     * {@link #self} (Spring AOP-прокси этого же бина, см. javadoc поля {@link #self}) — без
     * этого self-invocation проигнорировал бы {@code @Transactional(REQUIRES_NEW)} на
     * целевом методе.
     */
    public void checkWaitTimeouts() {
        LocalDateTime now = LocalDateTime.now();
        // NOTE: нужен составной индекс по (status, wait_timeout_at) —
        // без него при сотнях активных экземпляров это full scan каждые 10 сек.
        // Это только список КАНДИДАТОВ — без захвата строк, конкурентные поллеры могут
        // увидеть один и тот же инстанс здесь; single-fire гарантирует claimAndAdvanceTimeout.
        List<ExecutionInstance> expiredInstances = executionRepository.findWaitingWithExpiredTimeout(now);

        for (ExecutionInstance instance : expiredInstances) {
            try {
                self.getObject().claimAndAdvanceTimeout(instance.getId(), instance.getWaitTimeoutAt());
            } catch (Exception e) {
                // один сбойный инстанс не должен останавливать обработку остальных кандидатов
                // в этом тике — следующий тик @Scheduled подхватит его повторно (таймаут
                // остаётся непогашенным, пока claim не подтверждён успешным коммитом)
                log.error("Failed to process timeout claim for instance {} — will retry on next tick",
                        instance.getId(), e);
            }
        }
    }

    /**
     * P1-5: пытается атомарно захватить (claim) просроченный таймаут конкретного инстанса и,
     * если удалось, выполняет бизнес-переход по false-ветке (FAILURE). Если claim не удался
     * (вернулось {@code false} — строка уже обработана другим конкурентным потоком/репликой,
     * либо таймаут уже не актуален, например из-за нового визита WAIT-шага через GOTO), метод
     * не делает ничего — переход уже выполнен (или будет выполнен) тем потоком, который
     * выиграл claim.
     *
     * <p>После успешного claim инстанс перечитывается из БД ({@code findById}), а не
     * переиспользуется объект, переданный аргументом: claim — это bulk JPQL UPDATE с
     * {@code clearAutomatically = true} (см. {@code ExecutionJpaRepository#claimExpiredTimeout}),
     * persistence context очищается, и переданный объект become detached с устаревшим
     * {@code waitTimeoutAt} (тем, что было ДО claim). Дальнейшая бизнес-логика
     * ({@code advanceExecution}) должна оперировать актуальным управляемым объектом.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void claimAndAdvanceTimeout(Long instanceId, LocalDateTime expectedTimeout) {
        boolean claimed = executionRepository.claimExpiredTimeout(instanceId, expectedTimeout);
        if (!claimed) {
            log.debug("Timeout claim for instance {} lost to a concurrent poller (or no longer applicable) — skipping",
                    instanceId);
            return;
        }

        ExecutionInstance instance = executionRepository.findById(instanceId).orElse(null);
        if (instance == null) {
            log.warn("Timeout claimed for instance {} but instance no longer found", instanceId);
            return;
        }

        log.info("Timeout expired for instance {} at {} (claimed)", instance.getId(), expectedTimeout);

        Sequence sequence = sequenceQuery.findById(instance.getSequenceId()).orElse(null);
        if (sequence == null) {
            return;
        }

        Step currentStep = sequence.getSteps().stream()
                .filter(s -> s.getOrderIndex().equals(instance.getCurrentStepIndex()))
                .findFirst()
                .orElse(null);

        if (currentStep != null) {
            advanceExecution(instance, currentStep, StepResult.FAILURE);
        }
    }

    /**
     * P1-8 (часть 2 — логика записи): пишет одну запись Event Log класса Tracking (SITA),
     * если у последовательности включён флаг {@link Sequence#isLoggingEnabled()}. Флаг читается
     * из {@code Sequence}, которая в каждой вызывающей точке уже получена через
     * {@link SequenceQueryPort} — публичный выходной порт модуля {@code execution}, реализованный
     * адаптером ({@code SequenceQueryAdapter}) поверх публичного {@code SequenceRepositoryPort}
     * модуля {@code sequence} ({@code sequence.domain}/{@code sequence.port.out} —
     * {@code @NamedInterface}). Это тот же канал, которым {@code ExecutionService} уже читает
     * определение последовательности/шаги во всех остальных местах (start/stop/advance/timeout) —
     * никакого нового межмодульного доступа не вводится, границы Modulith не нарушаются.
     *
     * <p><b>Транзакционность:</b> запись делается синхронно, БЕЗ {@code REQUIRES_NEW}, внутри
     * ТОЙ ЖЕ транзакции, что и сам бизнес-переход (вызывающие методы — {@code startExecution},
     * {@code advanceExecution}, {@code completeExecution}, {@code abortExecution} — все уже
     * {@code @Transactional} per-instance, см. javadoc класса). Сознательный выбор: Tracking
     * Event Log — это бизнес-журнал, отражающий факт перехода состояния инстанса, поэтому он
     * должен быть консистентен с этим переходом — если запись в журнал не удалась (например
     * сбой соединения с БД на flush), коммит самого перехода тоже не должен произойти: иначе
     * получим переход состояния БЕЗ соответствующей записи в журнале — для журнала, чьё
     * единственное назначение — быть достоверной историей переходов, это хуже, чем откат вместе
     * с переходом (откат не теряет данных — событие будет переобработано/повторено, частичный
     * лог с пробелом восстановить нечем). {@code TrackingEventLogPort.save} — простой INSERT без
     * внешних побочных эффектов (не сетевой вызов, не сообщение наружу), поэтому риск этого
     * решения ограничен тем же типом сбоев, что уже могут откатить save() самого instance.
     *
     * <p><b>Идемпотентность:</b> сам метод не делает дедуп-проверок — он полагается на то, что
     * ВСЕ вызывающие точки уже идемпотентны на уровне решения "выполнять переход или нет" (P1-6/
     * P1-7): {@code STEP_COMPLETED} пишется только из тела {@code advanceExecution}, которое
     * вызывается ТОЛЬКО когда {@code ecaRuleEngine.executeStep} вернул не-null результат реально
     * выполненного шага; повторная доставка события на уже терминальный/не-WAITING инстанс
     * (см. {@code checkStopCriterionTransactional}, {@code tryResumeWaitingInstanceTransactional})
     * возвращается до вызова {@code advanceExecution} — следовательно и до этой записи. Аналогично
     * {@code SEQUENCE_STARTED} пишется только после прохождения дедуп-проверки по
     * {@code triggeringMessageId} в {@code startExecution} (P1-7) — повторная доставка того же
     * {@code NormalizedEvent} не создаёт второй {@code ExecutionInstance} и, соответственно, не
     * пишет вторую запись {@code SEQUENCE_STARTED}.
     */
    private void writeTrackingEvent(Sequence sequence, ExecutionInstance instance, TrackingEventType eventType,
                                     Integer stepIndex, StepResult stepResult, String detailsJson) {
        if (!sequence.isLoggingEnabled()) {
            return;
        }

        TrackingEventLog event = TrackingEventLog.builder()
                .sequenceId(sequence.getId())
                .instanceId(instance.getId())
                .aircraftId(instance.getAircraftId())
                .flightNumber(instance.getFlightNumber())
                .eventType(eventType)
                .stepIndex(stepIndex)
                .stepResult(stepResult)
                .detailsJson(detailsJson)
                .correlationId(CorrelationContext.getCorrelationId())
                .build();

        trackingEventLogPort.save(event);
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

    /**
     * Запоминает точку отсчёта "from this point only" шага {@code stepIndex} в персистентном
     * контексте инстанса ({@code contextJson}). Не вызывает save() сам — пишет только в поле
     * на entity, реальная запись в БД происходит на ближайшем save() вызывающей стороны (один
     * консистентный снапшот стейта на переходе, не отдельная транзакция под контекст).
     */
    private void rememberFromThisPointReference(ExecutionInstance instance, int stepIndex, LocalDateTime referenceTime) {
        InstanceContext context = instanceContextCodec.decode(instance.getContextJson());
        InstanceContext updated = context.withFromThisPointReference(stepIndex, referenceTime);
        instance.setContextJson(instanceContextCodec.encode(updated));
    }

    /**
     * Перед выполнением следующего/целевого шага восстанавливает waitStartedAt из персистентного
     * контекста, если на entity он уже null (очищен при выходе из WAIT-шага resolvedStepIndex,
     * см. advanceExecution) и для resolvedStepIndex в контексте есть запомненная точка отсчёта
     * "from this point only". Покрывает WAIT->EVALUATE (CONTINUE/GOTO) переходы, где следующий
     * шаг тоже читает instance.getWaitStartedAt() для своего fromThisPointOnly и ожидает увидеть
     * точку отсчёта только что resolved WAIT-шага, а также восстановление после рестарта сервиса —
     * контекст лежит в БД, а не in-memory.
     *
     * <p>НЕ восстанавливаем, если nextStep сам является WAIT: новый визит WAIT-шага обязан
     * открывать новое окно ожидания (см. комментарий в advanceExecution про GOTO-назад на WAIT) —
     * подстановка старой точки отсчёта в waitStartedAt до первого прогона WaitStepRule привела бы
     * к тому, что fromThisPointOnly-критерий этого нового визита использует чужое старое окно.
     *
     * @param resolvedStepIndex индекс шага, который только что был resolved (т.е. instance.getCurrentStepIndex()
     *                          ДО перехода на следующий/целевой шаг) — именно под этим индексом
     *                          advanceExecution запоминает точку отсчёта при выходе из WAIT-шага.
     * @param nextStep          шаг, на который выполняется переход (CONTINUE/GOTO target).
     */
    private void restoreFromThisPointReferenceIfNeeded(ExecutionInstance instance, Integer resolvedStepIndex, Step nextStep) {
        if (instance.getWaitStartedAt() != null || resolvedStepIndex == null || nextStep.getStepType() == StepType.WAIT) {
            return;
        }
        InstanceContext context = instanceContextCodec.decode(instance.getContextJson());
        LocalDateTime reference = context.getFromThisPointReference(resolvedStepIndex);
        if (reference != null) {
            instance.setWaitStartedAt(reference);
        }
    }
}
