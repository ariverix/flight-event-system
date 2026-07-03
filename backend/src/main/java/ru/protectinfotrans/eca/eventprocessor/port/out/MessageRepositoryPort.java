package ru.protectinfotrans.eca.eventprocessor.port.out;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import ru.protectinfotrans.eca.MessageType;
import ru.protectinfotrans.eca.eventprocessor.domain.IncomingMessage;
import ru.protectinfotrans.eca.eventprocessor.dto.AircraftSummaryResponse;
import ru.protectinfotrans.eca.sequence.domain.PositionSource;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Выходной порт для хранения входящих сообщений.
 *
 */
public interface MessageRepositoryPort {

    IncomingMessage save(IncomingMessage message);

    Optional<IncomingMessage> findById(Long id);

    /**
     * Найти ранее сохранённое сообщение по идентификатору внешней системы —
     * ключ идемпотентности шлюза (P2-1). Используется ДО save() новой записи: при совпадении
     * повторная доставка не создаёт дубликат и не публикует событие повторно.
     *
     * @param externalMessageId идентификатор сообщения от внешней ACARS-системы
     * @return ранее сохранённое сообщение с этим идентификатором, если оно есть
     */
    Optional<IncomingMessage> findByExternalMessageId(String externalMessageId);

    /**
     * Сохранить новое сообщение и НЕМЕДЛЕННО сделать flush — фикс TOCTOU-гонки P2-1 (ревью).
     * Между find-проверкой дедупа и save() в исходной реализации не было блокировки: два
     * конкурентных вызова с ОДНИМ externalMessageId оба проходили find (пусто) и оба пытались
     * save() — второй ловил {@code DataIntegrityViolationException} от partial unique index
     * (V25, idx_messages_external_message_id_unique), но эта exception не перехватывалась и
     * летела наружу как 500.
     * <p>
     * Явный flush (а не "просто save", откладывающий INSERT до конца транзакции) принципиален:
     * constraint violation должен материализоваться СЕЙЧАС, внутри вызывающего транзакционного
     * метода ({@code MessagePersistenceTransaction#persistAndPublish}), чтобы ВЕСЬ этот метод
     * откатился целиком и штатно (Spring перехватывает исключение на границе {@code @Transactional}-
     * прокси, откатывает транзакцию и перевыбрасывает) — recovery-read выполняется ОДНИМ уровнем
     * выше, СНАРУЖИ этой транзакционной границы, когда неудачная транзакция уже полностью
     * завершена (см. {@link #findByExternalMessageIdInNewTransaction} и javadoc
     * {@code EventProcessorService#receiveMessage}).
     *
     * @param message новое сообщение для сохранения
     * @return сохранённое сообщение с присвоенным id
     * @throws org.springframework.dao.DataIntegrityViolationException если конкурентный вызов
     *         успел вставить запись с тем же externalMessageId первым (нарушение unique index)
     */
    IncomingMessage saveAndFlush(IncomingMessage message);

    /**
     * Recovery-read после перехваченного {@code DataIntegrityViolationException} из
     * {@link #saveAndFlush} — фикс TOCTOU-гонки P2-1 (ревью). Выполняется в собственной новой
     * транзакции (REQUIRES_NEW): вызывается уже ПОСЛЕ того, как неудачная транзакция, в которой
     * произошёл constraint violation, полностью откатилась и завершилась — старую Hibernate
     * Session использовать в принципе нельзя (она закрыта вместе с транзакцией), поэтому нужна
     * заведомо новая. Победившая конкурентная транзакция к этому моменту уже закоммичена (READ
     * COMMITTED видит её результат), так что recovery-read детерминированно находит запись.
     *
     * @param externalMessageId идентификатор сообщения от внешней ACARS-системы
     * @return ранее сохранённое (победившим конкурентом) сообщение с этим идентификатором
     */
    Optional<IncomingMessage> findByExternalMessageIdInNewTransaction(String externalMessageId);

    /**
     * Получить список сообщений с фильтрами и пагинацией.
     *
     * @param aircraftId фильтр по ВС (null = без фильтра)
     * @param messageType фильтр по типу сообщения (null = без фильтра)
     * @param pageable параметры пагинации
     * @return страница сообщений
     */
    Page<IncomingMessage> findAllWithFilters(String aircraftId, MessageType messageType, Pageable pageable);

    /**
     * Проверка получения сообщения определённого типа и шаблона для ВС.
     * Для WAIT-шагов с fromThisPointOnly=true: проверяет только сообщения с receivedAt > waitStartedAt.
     *
     * @param aircraftId идентификатор ВС
     * @param messageType тип сообщения (DOWNLINK/UPLINK/GROUND)
     * @param templateName имя шаблона сообщения
     * @param afterTime учитывать только сообщения после этого времени (null = все сообщения)
     * @return true если такое сообщение найдено
     */
    boolean existsByAircraftAndTypeAndTemplate(
            String aircraftId,
            MessageType messageType,
            String templateName,
            LocalDateTime afterTime
    );

    /**
     * Проверка получения ФАКТИЧЕСКОГО (не estimated) позиционного отчёта за последние N минут —
     * паритет с SITA Sequencer: оценочные позиции игнорируются POSITION-критерием.
     *
     * Позиционный отчёт — сообщение с непустым positionSource (ACARS/RADAR/ADS_B).
     *
     * @param aircraftId идентификатор ВС
     * @param sinceTime нижняя граница окна (now - x минут)
     * @param source источник отчёта (null = любой источник)
     * @param afterTime для "from this point only": учитывать только отчёты после этого времени
     *                  (null = учитывать всю историю в пределах окна sinceTime)
     * @return true если фактический позиционный отчёт найден
     */
    boolean existsActualPositionReportSince(
            String aircraftId,
            LocalDateTime sinceTime,
            PositionSource source,
            LocalDateTime afterTime
    );

    /**
     * Момент последнего ФАКТИЧЕСКОГО позиционного отчёта по ВС (для диагностики/"not reported").
     *
     * @param aircraftId идентификатор ВС
     * @param source источник отчёта (null = любой источник)
     * @return время последнего фактического отчёта, либо empty если отчётов не было вовсе
     */
    Optional<LocalDateTime> findLastActualPositionReportTime(String aircraftId, PositionSource source);

    /**
     * Фаза 5: сводка по бортам (проекция GROUP BY aircraft_id над журналом сообщений) для UI
     * aircraft-bindings. Борт = различный {@code aircraft_id} (tail number); отдельной
     * таблицы-реестра бортов и типа ВС в системе нет.
     *
     * @param search подстрочный фильтр по tail number (case-insensitive; null/blank = все борта)
     * @param pageable страница/размер (сортировка фиксирована в запросе — последний контакт сверху)
     * @return страница сводок по бортам
     */
    Page<AircraftSummaryResponse> findAircraftSummaries(String search, Pageable pageable);
}
