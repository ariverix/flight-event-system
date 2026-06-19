package ru.protectinfotrans.eca.execution.domain;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Типизированная обёртка над персистентным контекстом инстанса ({@link ExecutionInstance#getContextJson()}).
 * Это НЕ {@code ExecutionContext} (dto-пакет) — тот собирается заново на каждый вызов для оценки
 * критериев из текущего {@code NormalizedEvent} и не сохраняется. {@code InstanceContext} — то,
 * что реально переживает save/reload/рестарт сервиса вместе со status/currentStepIndex.
 *
 * <p>Назначение (P1-3): хранить точки отсчёта "from this point only" для WAIT/EVALUATE-критериев
 * по индексу шага. {@link ExecutionInstance#getWaitStartedAt()} — основной носитель точки отсчёта
 * на время активного ожидания, но он ОЧИЩАЕТСЯ в {@code ExecutionService#advanceExecution} сразу
 * после resolve WAIT-шага (см. комментарий там же) — то есть к моменту, когда следующий шаг
 * (например EVALUATE, идущий через CONTINUE сразу за WAIT) читает {@code instance.getWaitStartedAt()}
 * для своего собственного fromThisPointOnly, поле уже {@code null}. {@code InstanceContext} хранит
 * последнюю known точку отсчёта по индексу шага, чтобы она была доступна и после очистки полей,
 * и после полного перезапуска сервиса (контекст лежит в БД в колонке {@code context} JSONB).
 */
public final class InstanceContext {

    private static final String FROM_THIS_POINT_REFERENCES_KEY = "fromThisPointReferences";

    /** stepIndex (как строка-ключ JSON) -> момент активации точки отсчёта для этого шага. */
    private final Map<String, LocalDateTime> fromThisPointReferences;

    private InstanceContext(Map<String, LocalDateTime> fromThisPointReferences) {
        this.fromThisPointReferences = fromThisPointReferences;
    }

    public static InstanceContext empty() {
        return new InstanceContext(new LinkedHashMap<>());
    }

    /**
     * Конструирует обёртку из распарсенной карты. Публичный, но предназначен для использования
     * исключительно из {@code InstanceContextCodec} (модуль execution, пакет application) —
     * не плодим вторую точку сериализации в сервисе.
     */
    public static InstanceContext of(Map<String, LocalDateTime> fromThisPointReferences) {
        return new InstanceContext(new LinkedHashMap<>(fromThisPointReferences));
    }

    public static String referencesKey() {
        return FROM_THIS_POINT_REFERENCES_KEY;
    }

    /** Запомнить точку отсчёта "from this point only" для шага {@code stepIndex}. */
    public InstanceContext withFromThisPointReference(int stepIndex, LocalDateTime referenceTime) {
        Map<String, LocalDateTime> copy = new LinkedHashMap<>(fromThisPointReferences);
        copy.put(String.valueOf(stepIndex), referenceTime);
        return new InstanceContext(copy);
    }

    /** Точка отсчёта "from this point only", запомненная для шага {@code stepIndex}, если есть. */
    public LocalDateTime getFromThisPointReference(int stepIndex) {
        return fromThisPointReferences.get(String.valueOf(stepIndex));
    }

    public Map<String, LocalDateTime> rawReferences() {
        return fromThisPointReferences;
    }
}
