package ru.protectinfotrans.eca.integration.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.protectinfotrans.eca.integration.domain.CallsignMatchingRule;

import java.time.LocalDate;
import java.util.List;

public interface CallsignMatchingJpaRepository extends JpaRepository<CallsignMatchingRule, Long> {

    /**
     * P2-4 (часть 1 — схема): кандидаты на матчинг по перевозчику, активные и действующие на
     * указанную дату (период {@code [valid_from, valid_to]}, оба края nullable). Использует
     * индексы {@code idx_callsign_matching_carrier}/{@code idx_callsign_matching_carrier_period}
     * (V28). Дальнейшая фильтрация (день недели, номер рейса, аэропорты) и выбор по
     * specificity — часть 2 (integration-dev).
     */
    @Query("SELECT r FROM CallsignMatchingRule r WHERE r.icaoCarrierCode = :carrierCode "
            + "AND r.active = true "
            + "AND (r.validFrom IS NULL OR r.validFrom <= :onDate) "
            + "AND (r.validTo IS NULL OR r.validTo >= :onDate)")
    List<CallsignMatchingRule> findCandidates(@Param("carrierCode") String carrierCode,
                                               @Param("onDate") LocalDate onDate);
}
