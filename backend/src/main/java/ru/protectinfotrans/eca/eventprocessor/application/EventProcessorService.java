package ru.protectinfotrans.eca.eventprocessor.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.protectinfotrans.eca.FlightStage;
import ru.protectinfotrans.eca.MessageType;
import ru.protectinfotrans.eca.eventprocessor.domain.IncomingMessage;
import ru.protectinfotrans.eca.eventprocessor.event.NormalizedEvent;
import ru.protectinfotrans.eca.eventprocessor.port.in.MessageInputPort;
import ru.protectinfotrans.eca.eventprocessor.port.out.EventPublisherPort;
import ru.protectinfotrans.eca.eventprocessor.port.out.MessageRepositoryPort;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Принимает сообщения от внешних систем, сохраняет в БД
 * и публикует NormalizedEvent для остальных модулей.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EventProcessorService implements MessageInputPort {

    private final MessageRepositoryPort messageRepository;
    private final EventPublisherPort eventPublisher;
    private final MessagePersistenceTransaction messagePersistenceTransaction;

    /**
     * <b>Транзакционность (фикс ревью, TOCTOU-гонка P2-1):</b> этот метод сам НЕ {@code @Transactional} —
     * вся бизнес-логика (дедуп-проверка + save + publish) выполняется в
     * {@link MessagePersistenceTransaction#persistAndPublish}, который помечен {@code @Transactional}
     * (REQUIRED — присоединяется к транзакции вызывающего, если она есть, как и раньше; иначе открывает
     * свою). Это сохраняет ИСХОДНУЮ атомарность: save() в {@code messages} и publish() в Outbox
     * commitятся/rollback'атся как единое целое с транзакцией вызывающего (P1-7, см.
     * {@code rollbackLeavesNoMessageAndNoEventPublication}/{@code commitLeavesBothMessageAndEventPublication}).
     * Логика вынесена в ОТДЕЛЬНЫЙ бин (а не private/package-private метод этого же класса) умышленно —
     * вызов {@code this.method()} внутри одного класса был бы self-invocation и обошёл бы Spring AOP-прокси,
     * из-за чего {@code @Transactional} на нём не сработал бы вовсе (та же причина, по которой
     * {@code WaitTimeoutScheduler} в модуле execution — отдельный {@code @Component}, см. его javadoc).
     * <p>
     * Раздельные слои принципиальны для гонки: если бы перехват {@code DataIntegrityViolationException}
     * был ВНУТРИ {@code @Transactional}-метода, транзакция к этому моменту уже помечена rollback-only
     * (Spring) и Hibernate Session уже невалидна после constraint violation на flush — дальнейший
     * recovery-read в ТОЙ ЖЕ транзакции либо запрещён, либо обречён откатиться вместе с ней. Перехват
     * здесь, СНАРУЖИ {@code @Transactional}-границы, происходит уже ПОСЛЕ того, как неудачная
     * транзакция полностью откатилась и завершилась (Spring откатывает и перевыбрасывает при выходе
     * из advised-метода) — это безопасная точка для recovery-read в собственной свежей транзакции.
     */
    @Override
    public Long receiveMessage(
            MessageType messageType,
            String templateName,
            String aircraftId,
            String flightNumber,
            String content,
            Map<String, Object> metadata
    ) {
        String externalMessageId = extractExternalMessageId(metadata);

        try {
            return messagePersistenceTransaction.persistAndPublish(
                    messageType, templateName, aircraftId, flightNumber, content, metadata, externalMessageId);
        } catch (DataIntegrityViolationException duplicateExternalMessageId) {
            // TOCTOU-гонка (ревью P2-1): между find-проверкой и save() внутри persistAndPublish нет
            // блокировки — под READ COMMITTED два конкурентных вызова с ОДНИМ externalMessageId оба
            // проходят find (пусто) и оба пытаются сохранить запись; проигравший ловит этот constraint
            // violation от partial unique index (V25) на flush/commit. К этому моменту его собственная
            // транзакция уже полностью откатилась (см. javadoc метода) — recovery-read ниже выполняется
            // в гарантированно свежей транзакции и детерминированно находит запись победителя (она уже
            // закоммичена и видна под READ COMMITTED).
            if (externalMessageId == null) {
                // конфликт без externalMessageId логически невозможен (партиционный unique index
                // не действует на NULL) — рефлекторно перевыбрасываем, не маскируя иную причину.
                throw duplicateExternalMessageId;
            }
            log.info("Concurrent duplicate delivery detected (externalMessageId={}) — recovering "
                            + "previously persisted message instead of failing the request",
                    externalMessageId);
            IncomingMessage winner = messageRepository.findByExternalMessageIdInNewTransaction(externalMessageId)
                    .orElseThrow(() -> duplicateExternalMessageId);
            log.info("Skipping re-publish for message ID: {} (lost the race, winner already published)",
                    winner.getId());
            return winner.getId();
        }
    }

    /**
     * <b>{@code @Transactional} принципиален здесь</b> (регрессия, найденная при фиксе TOCTOU-гонки
     * P2-1): до фикса класс был {@code @Transactional} целиком, поэтому этот метод неявно получал
     * транзакцию; когда класс-уровневая аннотация была снята (см. javadoc {@link #receiveMessage}),
     * вызов без активной транзакции ломал доставку {@code NormalizedEvent} в
     * {@code @ApplicationModuleListener} (Spring Modulith транзакционно привязывает диспетчеризацию
     * к публикации события — без транзакции в момент publish слушатель не вызывается). Метод не
     * пишет в {@code messages}, но Outbox-механизму Spring Modulith транзакция нужна независимо
     * от того, есть ли бизнес-данные для записи.
     */
    @Override
    @Transactional
    public void notifyFlightStageChange(String aircraftId, String flightNumber, FlightStage stage) {
        log.info("Flight stage change: aircraft={}, flight={}, stage={}", aircraftId, flightNumber, stage);

        // смена стадии — системное событие, в таблицу messages не пишем
        NormalizedEvent event = new NormalizedEvent(
                null,
                null,
                null,
                aircraftId,
                flightNumber,
                stage,
                LocalDateTime.now()
        );

        eventPublisher.publish(event);
        log.info("Flight stage change event published: {}", stage);
    }

    /**
     * Идентификатор сообщения от внешней ACARS-системы — ключ идемпотентности шлюза (P2-1).
     * Передаётся через ту же metadata-карту, что и positionSource/flightStage (см.
     * {@code ru.protectinfotrans.eca.eventprocessor.adapter.in.MessageController}, ключ
     * {@code "externalMessageId"}), без расширения сигнатуры
     * {@link ru.protectinfotrans.eca.eventprocessor.port.in.MessageInputPort#receiveMessage}.
     * Пустая строка считается отсутствием идентификатора (не дедуплицируем по пустоте).
     * <p>
     * Извлекается здесь (а не внутри {@link MessagePersistenceTransaction}), потому что нужен
     * ДО вызова транзакционного слоя — для решения, можно ли восстановиться после
     * {@code DataIntegrityViolationException} (см. {@link #receiveMessage}).
     */
    private String extractExternalMessageId(Map<String, Object> metadata) {
        if (metadata == null || !metadata.containsKey("externalMessageId")) {
            return null;
        }

        Object value = metadata.get("externalMessageId");
        if (value == null) {
            return null;
        }

        String stringValue = String.valueOf(value).trim();
        return stringValue.isEmpty() ? null : stringValue;
    }
}
