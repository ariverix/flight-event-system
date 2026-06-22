package ru.protectinfotrans.eca.customfields.port.out;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import ru.protectinfotrans.eca.MessageType;
import ru.protectinfotrans.eca.customfields.domain.CustomFieldRule;

import java.util.List;
import java.util.Optional;

/** Выходной порт персистентности правил извлечения — реализуется JPA-адаптером в {@code adapter.out}. */
public interface CustomFieldRuleRepositoryPort {

    CustomFieldRule save(CustomFieldRule rule);

    Optional<CustomFieldRule> findById(Long id);

    boolean existsByName(String name);

    Page<CustomFieldRule> findAll(MessageType messageType, Boolean active, Pageable pageable);

    void deleteById(Long id);

    /**
     * Активные правила, применимые к сообщению данного типа (опционально шаблона) — горячий путь
     * извлечения ({@code CustomFieldExtractionService#extract}), вызывается на КАЖДОЕ входящее
     * сообщение.
     *
     * @param messageType тип входящего сообщения
     * @param templateName имя шаблона сообщения (может быть {@code null}) — правило подходит, если
     *                      его {@code CustomFieldRule#templateName} либо {@code null} (применяется
     *                      к любому шаблону этого типа), либо совпадает с этим значением
     */
    List<CustomFieldRule> findActiveApplicableRules(MessageType messageType, String templateName);
}
