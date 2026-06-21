package ru.protectinfotrans.eca.integration.port.out;

import ru.protectinfotrans.eca.integration.domain.CallsignMatchingRule;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * P2-4 (часть 1 — схема): выходной порт хранения правил соответствия позывных flight id (FI).
 * Внутренний порт модуля {@code integration} — НЕ выставляется как named-interface наружу (тот
 * же принцип, что {@link OutboundMessageRepositoryPort}, P2-3).
 *
 * <p><b>Заглушка под часть 2</b> (integration-dev, разбор позывных и сам алгоритм матчинга):
 * здесь только CRUD-доступ к таблице правил, БЕЗ логики выбора лучшего совпадения. Часть 2
 * ожидаемо реализует поверх {@link #findCandidates} выбор кандидата с максимальной
 * {@code specificity} среди прошедших дополнительную проверку по дню недели/номеру рейса.
 */
public interface CallsignMatchingRepositoryPort {

    CallsignMatchingRule save(CallsignMatchingRule rule);

    Optional<CallsignMatchingRule> findById(Long id);

    /**
     * Кандидаты на матчинг по ICAO-коду перевозчика, активные и действующие на указанную дату
     * (период {@code [valid_from, valid_to]}, оба края nullable = без ограничения). Дальнейшая
     * фильтрация по дню недели ({@link CallsignMatchingRule#getDaysOfWeek()}), номеру рейса и
     * аэропортам вылета/прилёта, а также выбор кандидата с максимальной
     * {@link CallsignMatchingRule#getSpecificity()} — часть 2 (integration-dev).
     *
     * @param icaoCarrierCode ICAO-код перевозчика, разобранный из позывного
     * @param onDate          дата полёта/сообщения, для которой ищется действующее правило
     * @return активные правила-кандидаты для данного перевозчика на эту дату, без сортировки
     *         по specificity (сортировка — на стороне вызывающего кода части 2)
     */
    List<CallsignMatchingRule> findCandidates(String icaoCarrierCode, LocalDate onDate);
}
