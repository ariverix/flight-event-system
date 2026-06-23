package ru.protectinfotrans.eca.eventprocessor.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.protectinfotrans.eca.FlightStage;
import ru.protectinfotrans.eca.MessageType;
import ru.protectinfotrans.eca.conditions.port.in.FlightConditionLifecycleUseCase;
import ru.protectinfotrans.eca.customfields.port.in.CustomFieldExtractionUseCase;
import ru.protectinfotrans.eca.customfields.port.in.FlightContextLifecycleUseCase;
import ru.protectinfotrans.eca.eventprocessor.domain.FlightStageEvent;
import ru.protectinfotrans.eca.eventprocessor.domain.IncomingMessage;
import ru.protectinfotrans.eca.eventprocessor.event.NormalizedEvent;
import ru.protectinfotrans.eca.eventprocessor.port.out.EventPublisherPort;
import ru.protectinfotrans.eca.eventprocessor.port.out.FlightStageEventRepositoryPort;
import ru.protectinfotrans.eca.eventprocessor.port.out.MessageRepositoryPort;
import ru.protectinfotrans.eca.sequence.domain.PositionSource;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

/**
 * Транзакционная граница для дедуп-проверки + persist + publish одного входящего сообщения —
 * вынесена в ОТДЕЛЬНЫЙ бин из {@link EventProcessorService} (фикс ревью, TOCTOU-гонка P2-1).
 *
 * <p><b>Почему отдельный бин, а не метод {@code EventProcessorService}:</b> {@code receiveMessage}
 * в {@code EventProcessorService} перехватывает {@code DataIntegrityViolationException} СНАРУЖИ
 * транзакционной границы (после того как Spring полностью откатил и завершил неудачную
 * транзакцию) — иначе recovery-read после constraint violation выполнялся бы в уже невалидной
 * (rollback-only/закрытой) Hibernate Session. Перехват СНАРУЖИ требует, чтобы вызов
 * {@code persistAndPublish} физически проходил через Spring AOP-прокси как вызов ИЗ ДРУГОГО
 * бина — вызов {@code this.persistAndPublish(...)} внутри одного класса был бы self-invocation,
 * и {@code @Transactional} не сработал бы вовсе (та же причина, по которой
 * {@code WaitTimeoutScheduler} в модуле execution — отдельный {@code @Component}).
 *
 * <p><b>Транзакционность:</b> {@code REQUIRED} (по умолчанию, не REQUIRES_NEW) — присоединяется
 * к транзакции вызывающего, если она уже открыта (как в исходной реализации), иначе открывает
 * свою. Сохраняет ИСХОДНУЮ атомарность save()+publish() с транзакцией вызывающего (P1-7).
 */
@Component
@RequiredArgsConstructor
@Slf4j
class MessagePersistenceTransaction {

    private final MessageRepositoryPort messageRepository;
    private final EventPublisherPort eventPublisher;
    private final FlightStageEventRepositoryPort flightStageEventRepository;
    private final ObjectMapper objectMapper;
    private final CustomFieldExtractionUseCase customFieldExtractionUseCase;
    private final FlightContextLifecycleUseCase flightContextLifecycleUseCase;
    private final FlightConditionLifecycleUseCase flightConditionLifecycleUseCase;

    /**
     * Идемпотентность шлюза (P2-1): persist раньше обработки, но ПЕРЕД persist — проверка
     * дедуп-ключа. Повторная доставка с тем же externalMessageId (at-least-once редоставка от
     * внешней ACARS-системы/ретрай) не создаёт вторую запись в messages и не публикует
     * NormalizedEvent повторно — возвращается id уже сохранённого сообщения. Применимо только
     * когда внешняя система прислала идентификатор; без него (легаси-источники без надёжного
     * message reference) дедуп на уровне шлюза невозможен — остаётся только дедуп потребителя
     * по messages.id (P1-7, triggering_message_id в execution_instances).
     * <p>
     * TOCTOU-гонка (ревью P2-1): между find-проверкой выше и save() ниже нет блокировки — под
     * READ COMMITTED два конкурентных вызова с ОДНИМ externalMessageId оба проходят find (пусто)
     * и оба пытаются сохранить запись. {@code saveAndFlush} принудительно отправляет INSERT в БД
     * немедленно (не дожидаясь конца транзакции) — партиционный unique index по
     * external_message_id (V25) проверяется СЕЙЧАС ЖЕ, чтобы проигравший вызов мог поймать
     * {@code DataIntegrityViolationException} в {@link EventProcessorService#receiveMessage},
     * СНАРУЖИ этой транзакционной границы (см. javadoc класса), и восстановиться без 500-ки.
     *
     * @throws org.springframework.dao.DataIntegrityViolationException если конкурентный вызов
     *         успел вставить запись с тем же externalMessageId первым (нарушение unique index)
     */
    @Transactional
    Long persistAndPublish(
            MessageType messageType,
            String templateName,
            String aircraftId,
            String flightNumber,
            String content,
            Map<String, Object> metadata,
            String externalMessageId
    ) {
        log.info("Receiving message: type={}, template={}, aircraft={}, flight={}",
                messageType, templateName, aircraftId, flightNumber);

        if (externalMessageId != null) {
            Optional<IncomingMessage> existing = messageRepository.findByExternalMessageId(externalMessageId);
            if (existing.isPresent()) {
                log.info("Duplicate delivery detected (externalMessageId={}) — message already persisted as id={}, "
                        + "skipping re-save and re-publish", externalMessageId, existing.get().getId());
                return existing.get().getId();
            }
        }

        IncomingMessage message = IncomingMessage.builder()
                .messageType(messageType)
                .templateName(templateName)
                .aircraftId(aircraftId)
                .flightNumber(flightNumber)
                .content(content)
                .metadataJson(serializeMetadata(metadata))
                .receivedAt(LocalDateTime.now())
                .positionSource(extractPositionSource(metadata))
                .estimatedPosition(extractEstimatedFlag(metadata))
                .externalMessageId(externalMessageId)
                .build();

        message = messageRepository.saveAndFlush(message);
        log.debug("Message saved with ID: {}", message.getId());

        // P3-2: извлечение custom fields ДО публикации NormalizedEvent — паритет с SITA: значения,
        // извлечённые из ЭТОГО сообщения, обязаны быть видимы (per-flight, см. CustomFieldValue)
        // уже тем шагам ECA-движка, которые обработают NormalizedEvent сразу после publish() ниже
        // (в той же транзакции — REQUIRED, см. javadoc класса, atомарно с save()/publish()).
        customFieldExtractionUseCase.extract(
                message.getId(), messageType, templateName, aircraftId, flightNumber, content, metadata);

        FlightStage flightStage = extractFlightStage(metadata);
        recordFlightStageEvent(aircraftId, flightNumber, flightStage, message.getReceivedAt());

        NormalizedEvent event = new NormalizedEvent(
                message.getId(),
                messageType,
                templateName,
                aircraftId,
                flightNumber,
                flightStage,
                message.getReceivedAt()
        );

        eventPublisher.publish(event);
        log.info("NormalizedEvent published for message ID: {}", message.getId());

        return message.getId();
    }

    /**
     * Записать факт смены стадии полёта в durable журнал (V29) — паритет с SITA Sequencer:
     * POSITION-критерий "not reported" использует Off-таймстамп как точку отсчёта окна
     * (см. {@code CriterionEvaluator#evaluatePosition}/{@code ExecutionService#buildContext}),
     * а до этой миграции момент Off нигде не сохранялся при доставке OOOI-метки ВНУТРИ обычного
     * входящего сообщения (например ARINC 618 с OUT/OFF/ON/IN-метками, см. {@code Arinc618Parser}) —
     * только при отдельном явном вызове {@code notifyFlightStageChange} (см. там же).
     * Не пишет ничего, если сообщение не несёт стадию (обычное большинство сообщений).
     *
     * <p>P3-2: тот же момент — единственное место, где OOOI-метка ВНУТРИ обычного входящего
     * сообщения становится известна движку (в отличие от {@code notifyFlightStageChange} —
     * отдельный системный канал) — поэтому здесь же, ПОСЛЕ записи самого факта смены стадии,
     * уведомляем {@code FlightContextLifecycleUseCase} (закрытие custom fields рейса на
     * IN/SUMMARY, см. её javadoc). Порядок принципиален: extract() для ЭТОГО сообщения уже
     * выполнен ВЫШЕ по стеку вызовов (см. {@code persistAndPublish}), так что закрытие здесь
     * не теряет значение, извлечённое из самого терминального сообщения.
     *
     * <p>P3-3: точно тот же канал/порядок используется для авто-закрытия активных custom
     * conditions рейса ({@code FlightConditionLifecycleUseCase}, паритет SITA "активные условия
     * закрываются автоматически при завершении рейса") — независимый вызов ПОСЛЕ
     * {@code flightContextLifecycleUseCase}, оба идемпотентны и не зависят друг от друга.
     */
    private void recordFlightStageEvent(String aircraftId, String flightNumber,
                                         FlightStage flightStage, LocalDateTime occurredAt) {
        if (flightStage == null || aircraftId == null) {
            return;
        }

        flightStageEventRepository.save(FlightStageEvent.builder()
                .aircraftId(aircraftId)
                .flightNumber(flightNumber)
                .stage(flightStage)
                .occurredAt(occurredAt)
                .build());

        flightContextLifecycleUseCase.onFlightStageChanged(aircraftId, flightNumber, flightStage);
        flightConditionLifecycleUseCase.onFlightStageChanged(aircraftId, flightNumber, flightStage);
    }

    private FlightStage extractFlightStage(Map<String, Object> metadata) {
        if (metadata == null || !metadata.containsKey("flightStage")) {
            return null;
        }

        try {
            String stageName = (String) metadata.get("flightStage");
            return FlightStage.valueOf(stageName);
        } catch (Exception e) {
            log.warn("Failed to extract flight stage from metadata", e);
            return null;
        }
    }

    /**
     * Источник позиционного отчёта (ACARS/RADAR/ADS_B) — паритет с SITA Sequencer.
     * Null для немпозиционных сообщений (отсутствие ключа в metadata И отсутствие
     * признаков позиционных данных).
     * <p>
     * Защитный fallback: если клиент (UI/внешняя система) прислал координаты
     * (latitude/longitude) в metadata, но явно не указал positionSource, сообщение
     * всё равно является позиционным отчётом по сути — проставляем дефолтный ACARS
     * и предупреждаем в лог, чтобы не терять позиционные данные молча
     * (POSITION_REPORTED-критерий требует positionSource IS NOT NULL — P1-1).
     */
    private PositionSource extractPositionSource(Map<String, Object> metadata) {
        if (metadata == null) {
            return null;
        }

        if (metadata.containsKey("positionSource")) {
            try {
                String sourceName = (String) metadata.get("positionSource");
                return PositionSource.valueOf(sourceName);
            } catch (Exception e) {
                log.warn("Failed to extract position source from metadata", e);
                return null;
            }
        }

        if (metadata.containsKey("latitude") || metadata.containsKey("longitude")) {
            log.warn("Position report metadata contains latitude/longitude but no explicit "
                    + "positionSource — defaulting to {} so the POSITION_REPORTED criterion "
                    + "(requires position_source IS NOT NULL) does not silently ignore this message",
                    PositionSource.ACARS);
            return PositionSource.ACARS;
        }

        return null;
    }

    /**
     * Признак оценочной (estimated) позиции. По умолчанию false (фактическая) —
     * консервативный дефолт, чтобы не терять фактические позиции без явной пометки.
     */
    private boolean extractEstimatedFlag(Map<String, Object> metadata) {
        if (metadata == null || !metadata.containsKey("estimatedPosition")) {
            return false;
        }

        Object value = metadata.get("estimatedPosition");
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private String serializeMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }

        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize metadata", e);
            return null;
        }
    }
}
