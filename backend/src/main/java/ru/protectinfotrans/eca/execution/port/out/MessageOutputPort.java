package ru.protectinfotrans.eca.execution.port.out;

import ru.protectinfotrans.eca.sequence.domain.UplinkOrigin;

import java.util.List;
import java.util.Map;

/**
 * Выходной порт для отправки исходящих сообщений (uplink/ground) — реализуется адаптерами в
 * integration модуле.
 *
 * <p><b>P3-3:</b> raise/close condition больше НЕ часть этого порта — условия/алерты переехали в
 * отдельный модуль {@code conditions} (см. {@code ConditionManagementUseCase}), поскольку у них
 * нет материального "отправить во внешнюю систему" эффекта (в отличие от send uplink/ground) —
 * это внутреннее понятие Sequencer-движка (см. {@code conditions.package-info} для полного
 * обоснования).
 */
public interface MessageOutputPort {

    /**
     * Отправить сообщение на борт (uplink).
     *
     * @param aircraftId идентификатор ВС
     * @param templateName имя шаблона сообщения
     * @param params параметры для заполнения шаблона
     * @return true если отправка успешна
     */
    boolean sendUplink(String aircraftId, String templateName, Map<String, Object> params);

    /**
     * Отправить сообщение на борт (uplink) с явным указанием происхождения шаблона —
     * паритет с SITA Sequencer: computer-generated | from external user.
     * Дефолтная реализация делегирует в 3-аргументный метод (COMPUTER_GENERATED) —
     * обратная совместимость для адаптеров, ещё не различающих происхождение шаблона.
     *
     * @param aircraftId идентификатор ВС
     * @param templateName имя шаблона сообщения
     * @param params параметры для заполнения шаблона
     * @param origin происхождение шаблона (computer-generated | external-user)
     * @return true если отправка успешна
     */
    default boolean sendUplink(String aircraftId, String templateName, Map<String, Object> params, UplinkOrigin origin) {
        return sendUplink(aircraftId, templateName, params);
    }

    /**
     * Отправить сообщение на борт (uplink) В КОНТЕКСТЕ конкретного шага ECA-движка —
     * фикс регрессии идемпотентности (cross-feature P1-4 × P2-3, см. ADR-0002, "Спецификация
     * для реализации", п.5, и javadoc {@code ExecutionService#resumeRunningInstanceAfterRestart}).
     *
     * <p>{@code executionInstanceId}/{@code stepOrderIndex} — естественный дедуп-ключ постановки
     * в durable-очередь (по аналогии с {@code triggering_message_id}, P1-7): после замены
     * заглушки {@code LogMessageAdapter} на реальный durable-шлюз ({@code OutboundMessageGatewayAdapter},
     * P2-3) повторный прогон ACTION-шага RUNNING-инстанса при resume после рестарта
     * ({@code resumeRunningInstanceAfterRestart}) больше не является операцией с нулевой ценой —
     * без дедуп-ключа он создавал бы ВТОРОЙ {@code OutboundMessage} для уже поставленного шага
     * (материальный эффект — повторная uplink-команда на борт). Этот метод позволяет вызывающему
     * ({@code ActionStepRule}, которому instance/step уже доступны как Easy Rules facts) передать
     * контекст шага вниз к адаптеру, реализующему дедуп.
     *
     * <p>Дефолтная реализация делегирует в 4-аргументный метод, ИГНОРИРУЯ контекст шага —
     * обратная совместимость для вызывающих БЕЗ контекста инстанса/шага (например
     * {@code IntegrationService} — ручной/программный вызов отправки вне ECA-перехода) и для
     * адаптеров, не реализующих дедуп (например {@code LogMessageAdapter} — заглушка без
     * материального эффекта дублирования).
     *
     * @param aircraftId идентификатор ВС
     * @param templateName имя шаблона сообщения
     * @param params параметры для заполнения шаблона
     * @param origin происхождение шаблона (computer-generated | external-user)
     * @param executionInstanceId идентификатор {@code ExecutionInstance}, в рамках которого
     *                            выполняется ACTION-шаг (часть дедуп-ключа); {@code null}, если
     *                            вызов происходит вне контекста инстанса/шага
     * @param stepOrderIndex индекс ACTION-шага в последовательности (часть дедуп-ключа);
     *                       {@code null}, если вызов происходит вне контекста инстанса/шага
     * @return true если отправка (постановка в durable-очередь) успешна, в том числе если запись
     *         для данного {@code (executionInstanceId, stepOrderIndex)} уже существовала и была
     *         идемпотентно пропущена (повторный прогон при resume)
     */
    default boolean sendUplink(String aircraftId, String templateName, Map<String, Object> params,
                                UplinkOrigin origin, Long executionInstanceId, Integer stepOrderIndex) {
        return sendUplink(aircraftId, templateName, params, origin);
    }

    /**
     * Отправить наземное сообщение (ground).
     *
     * @param recipients список получателей
     * @param templateName имя шаблона сообщения
     * @param params параметры для заполнения шаблона
     * @return true если отправка успешна
     */
    boolean sendGround(List<String> recipients, String templateName, Map<String, Object> params);

    /**
     * Отправить наземное сообщение (ground) В КОНТЕКСТЕ конкретного шага ECA-движка — см.
     * javadoc {@link #sendUplink(String, String, Map, UplinkOrigin, Long, Integer)}, тот же
     * дедуп-ключ {@code (executionInstanceId, stepOrderIndex)} и те же причины существования
     * этой перегрузки.
     *
     * <p>Дефолтная реализация делегирует в 3-аргументный метод, игнорируя контекст шага —
     * обратная совместимость, как и у {@code sendUplink}.
     *
     * @param recipients список получателей
     * @param templateName имя шаблона сообщения
     * @param params параметры для заполнения шаблона
     * @param executionInstanceId идентификатор {@code ExecutionInstance} (часть дедуп-ключа);
     *                            {@code null}, если вызов происходит вне контекста инстанса/шага
     * @param stepOrderIndex индекс ACTION-шага (часть дедуп-ключа); {@code null}, если вызов
     *                       происходит вне контекста инстанса/шага
     * @return true если отправка (постановка в durable-очередь) успешна, в том числе при
     *         идемпотентном пропуске уже существующей записи для этого шага
     */
    default boolean sendGround(List<String> recipients, String templateName, Map<String, Object> params,
                                Long executionInstanceId, Integer stepOrderIndex) {
        return sendGround(recipients, templateName, params);
    }
}