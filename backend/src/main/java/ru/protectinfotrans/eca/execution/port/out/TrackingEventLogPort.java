package ru.protectinfotrans.eca.execution.port.out;

import ru.protectinfotrans.eca.execution.domain.TrackingEventLog;

/**
 * P1-8 (часть 1 — схема, V24): выходной порт для записи в Tracking Event Log.
 *
 * <p>Это ТОЧКА, которую должен использовать observability-agent (часть 2) для записи
 * событий старта/стопа последовательности и завершения шага. Сама логика "когда и что
 * писать" (проверка {@code Sequence#isLoggingEnabled()}, формирование {@link TrackingEventLog})
 * реализуется там — этот порт только сохраняет уже собранную запись.
 *
 * <p>Не содержит query-методов: чтение журнала оператором — отдельный use case (REST API),
 * который появится вместе с реализацией части 2 (отдельный query-репозиторий по аналогии с
 * {@code AuditLogQueryRepository}/{@code ExecutionJpaRepository#findByFilters}, с пагинацией
 * и фильтрами по {@code sequence_id}/{@code aircraftId+flightNumber}/{@code instanceId}/
 * {@code created_at} — под индексы, созданные миграцией V24).
 */
public interface TrackingEventLogPort {

    TrackingEventLog save(TrackingEventLog event);
}
