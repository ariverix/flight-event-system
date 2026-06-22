package ru.protectinfotrans.eca.customfields.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.protectinfotrans.eca.FlightStage;
import ru.protectinfotrans.eca.MessageType;
import ru.protectinfotrans.eca.customfields.domain.CustomFieldRule;
import ru.protectinfotrans.eca.customfields.domain.CustomFieldValue;
import ru.protectinfotrans.eca.customfields.domain.ExtractionSource;
import ru.protectinfotrans.eca.customfields.port.in.CustomFieldExtractionUseCase;
import ru.protectinfotrans.eca.customfields.port.in.FlightContextLifecycleUseCase;
import ru.protectinfotrans.eca.customfields.port.out.CustomFieldRuleRepositoryPort;
import ru.protectinfotrans.eca.customfields.port.out.CustomFieldValueRepositoryPort;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Движок извлечения custom fields из входящих сообщений + закрытие per-flight контекста при
 * завершении рейса — паритет с SITA Sequencer (см. {@code package-info} модуля).
 *
 * <p><b>Детерминированность (CLAUDE.md, "рендеринг детерминированный и тестируемый"):</b>
 * {@link #extract} — чистая функция от {@code (rule.pattern, content/metadata)}: один и тот же
 * входной текст/набор метаданных с одним и тем же набором активных правил ВСЕГДА извлекает одно и
 * то же значение. Источник недетерминированности есть только в МОМЕНТЕ записи ({@code
 * extractedAt = now()}), не в САМОМ извлечённом значении.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class CustomFieldExtractionService implements CustomFieldExtractionUseCase, FlightContextLifecycleUseCase {

    private final CustomFieldRuleRepositoryPort ruleRepository;
    private final CustomFieldValueRepositoryPort valueRepository;

    @Override
    public void extract(
            Long sourceMessageId,
            MessageType messageType,
            String templateName,
            String aircraftId,
            String flightNumber,
            String content,
            Map<String, Object> metadata
    ) {
        if (aircraftId == null) {
            // нет рейса, к которому привязать per-flight значение — экстракция неприменима
            // (см. CustomFieldExtractionUseCase#extract javadoc)
            return;
        }

        List<CustomFieldRule> applicableRules = ruleRepository.findActiveApplicableRules(messageType, templateName);
        if (applicableRules.isEmpty()) {
            return;
        }

        for (CustomFieldRule rule : applicableRules) {
            extractOneRule(rule, sourceMessageId, aircraftId, flightNumber, content, metadata);
        }
    }

    private void extractOneRule(CustomFieldRule rule, Long sourceMessageId, String aircraftId,
                                 String flightNumber, String content, Map<String, Object> metadata) {
        String extractedValue = rule.getExtractionSource() == ExtractionSource.CONTENT
                ? extractFromContent(rule.getPattern(), content)
                : extractFromMetadata(rule.getPattern(), metadata);

        if (extractedValue == null) {
            // паттерн не совпал/ключ отсутствует — штатная ситуация (не каждое сообщение несёт
            // каждое поле), не перезаписываем существующее значение мусором/null
            return;
        }

        CustomFieldValue value = CustomFieldValue.builder()
                .fieldName(rule.getName())
                .aircraftId(aircraftId)
                .flightNumber(flightNumber)
                .value(extractedValue)
                .sourceMessageId(sourceMessageId)
                .extractedAt(LocalDateTime.now())
                .build();

        valueRepository.upsert(value);
        log.debug("Custom field '{}' extracted for aircraft={}/flight={} from message={}",
                rule.getName(), aircraftId, flightNumber, sourceMessageId);
    }

    /**
     * Regex с ровно одной capturing-группой (инвариант — {@code CustomFieldRuleValidator},
     * проверен на этапе CRUD, не здесь повторно — горячий путь экстракции доверяет уже
     * сохранённому правилу). {@code null}, если паттерн не совпал или {@code content} отсутствует.
     */
    private String extractFromContent(String regexPattern, String content) {
        if (content == null) {
            return null;
        }
        try {
            Matcher matcher = Pattern.compile(regexPattern).matcher(content);
            return matcher.find() ? matcher.group(1) : null;
        } catch (Exception e) {
            // некорректный regex на сохранённом правиле (теоретически невозможно при прохождении
            // CustomFieldRuleValidator на CRUD, но defense in depth — не должно ронять обработку
            // ВСЕГО входящего сообщения из-за одного сломанного правила)
            log.warn("Custom field CONTENT extraction failed for pattern '{}': {}", regexPattern, e.toString());
            return null;
        }
    }

    /** {@code null}, если ключ отсутствует в metadata или сама metadata отсутствует. */
    private String extractFromMetadata(String metadataKey, Map<String, Object> metadata) {
        if (metadata == null || !metadata.containsKey(metadataKey)) {
            return null;
        }
        Object value = metadata.get(metadataKey);
        return value == null ? null : value.toString();
    }

    /**
     * Закрытие per-flight контекста — паритет SITA "контекст закрывается при завершении рейса".
     * Терминальные стадии — {@link FlightStage#IN} (рейс приземлился и завершил руление до
     * стоянки) и {@link FlightStage#SUMMARY} (финальная стадия после завершения рейса, см.
     * {@code FlightStage} javadoc) — ЛЮБАЯ из двух закрывает контекст: SITA-паритетные сценарии
     * иногда завершаются на IN без отдельного перехода в SUMMARY (упрощённые/демо-сценарии без
     * явного summary-шага), а где SUMMARY используется — IN уже закрыл контекст раньше, и
     * повторный вызов на SUMMARY идемпотентно не находит открытых строк (0 закрытых, не ошибка).
     */
    @Override
    public void onFlightStageChanged(String aircraftId, String flightNumber, FlightStage stage) {
        if (stage != FlightStage.IN && stage != FlightStage.SUMMARY) {
            return;
        }
        if (aircraftId == null || flightNumber == null) {
            // без номера рейса невозможно сузить закрытие до конкретного рейса этого борта
            // (на одном борту может выполняться следующий рейс с другим flightNumber) —
            // пропускаем закрытие, чем закрыть ПО ОШИБКЕ контекст другого рейса того же борта
            log.warn("Cannot close custom field context: aircraftId/flightNumber missing (aircraft={}, flight={}, stage={})",
                    aircraftId, flightNumber, stage);
            return;
        }

        int closed = valueRepository.closeAllOpenForFlight(aircraftId, flightNumber, LocalDateTime.now());
        log.info("Custom field context closed for aircraft={}/flight={} (stage={}, closedFields={})",
                aircraftId, flightNumber, stage, closed);
    }
}
