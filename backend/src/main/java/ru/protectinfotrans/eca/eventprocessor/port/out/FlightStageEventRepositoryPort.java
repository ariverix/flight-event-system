package ru.protectinfotrans.eca.eventprocessor.port.out;

import ru.protectinfotrans.eca.FlightStage;
import ru.protectinfotrans.eca.eventprocessor.domain.FlightStageEvent;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Выходной порт для журнала смен стадии полёта (V29) — durable источник Off/On/Out/In-таймстампов
 * по борту. Используется POSITION-критерием (модуль {@code execution}, см.
 * {@code CriterionEvaluator}/{@code ExecutionService.buildContext}) как точка отсчёта для
 * "position not reported in last {x} min" — паритет с SITA Sequencer.
 */
public interface FlightStageEventRepositoryPort {

    FlightStageEvent save(FlightStageEvent event);

    /**
     * Момент последней смены ВС на стадию {@code stage} (например {@code FlightStage.OFF}).
     * Если борт проходил эту стадию несколько раз (несколько рейсов подряд за день — реальный
     * сценарий разворота "туда-обратно" на одном ВС), возвращается САМЫЙ ПОЗДНИЙ момент —
     * актуальный для текущего полёта.
     *
     * @param aircraftId идентификатор ВС
     * @param stage      искомая стадия
     * @return момент последнего перехода в эту стадию, либо empty если такого перехода не было
     */
    Optional<LocalDateTime> findLastStageTimestamp(String aircraftId, FlightStage stage);
}
