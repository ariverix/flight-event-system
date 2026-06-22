package ru.protectinfotrans.eca.templates.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.protectinfotrans.eca.templates.domain.Template;
import ru.protectinfotrans.eca.templates.port.in.MissingTemplateVariableException;
import ru.protectinfotrans.eca.templates.port.in.TemplateRenderUseCase;
import ru.protectinfotrans.eca.templates.port.out.TemplateRepositoryPort;

import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * Реализация {@link TemplateRenderUseCase} — главная точка входа для execution/integration.
 * Не проверяет {@code active}: выключенный шаблон ВСЁ ЕЩЁ можно рендерить по существующей
 * ссылке (мягкое выключение запрещает только НОВЫЙ выбор шаблона в UI при создании ACTION-шага,
 * см. {@code Template#active} javadoc) — иначе уже сохранённые ACTION-конфиги старых
 * последовательностей начали бы давать FAILURE сразу после деактивации шаблона администратором,
 * что является более разрушительным последствием, чем сама деактивация была задумана дать.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class TemplateRenderService implements TemplateRenderUseCase {

    private final TemplateRepositoryPort repository;
    private final TemplateRenderer renderer;

    @Override
    public String render(String templateName, Map<String, Object> variables) {
        Template template = repository.findByName(templateName)
                .orElseThrow(() -> new NoSuchElementException("Template not found: " + templateName));
        return renderer.render(template.getBody(), variables);
    }

    /**
     * {@code REQUIRES_NEW} — намеренно ОТДЕЛЬНАЯ транзакция/JDBC-соединение от вызывающего.
     * Без этого сбой SELECT в {@code templates} (например, недоступность БД) переводит
     * connection/транзакцию вызывающего в состояние Postgres "current transaction is aborted"
     * — и даже после Java-уровня try/catch ЗДЕСЬ последующие операции вызывающего в ЕГО
     * транзакции (например, {@code OutboundMessageDeliveryScheduler#deliverOne} → {@code
     * repository.markSent(...)} в той же {@code REQUIRES_NEW}-транзакции доставки) тоже падают
     * — Postgres отказывается выполнять что-либо до конца транзакции после первой ошибки,
     * это не лечится перехватом исключения в том же соединении. REQUIRES_NEW изолирует это:
     * лукап шаблона коммитится/роллбэчится сам по себе, не затрагивая транзакцию вызывающего.
     *
     * <p><b>{@link MissingTemplateVariableException} — НЕ глушится, пробрасывается наружу:</b>
     * в отличие от "шаблон не найден"/"БД недоступна" (легитимный {@code Optional.empty()} —
     * обратная совместимость, см. javadoc {@link TemplateRenderUseCase#tryRender}), отсутствие
     * значения переменной для НАЙДЕННОГО шаблона — это ошибка конфигурации ACTION-шага, а не
     * "шаблон неизвестен реестру". Глушить её до {@code Optional.empty()} означало бы, что
     * вызывающий (durable outbound-доставка) не отличит "шаблон не зарегистрирован" (legit
     * fallback на templateName, см. javadoc вызывающего) от "шаблон сломан/неполные параметры" —
     * и во ВТОРОМ случае тихо отправит ИМЯ ШАБЛОНА в канал ПОД ВИДОМ успешной доставки (P3-1
     * production-дефект). Вызывающая сторона обязана трактовать это исключение как сбой
     * доставки (markFailed/retry), а не как "шаблон не разрешился".
     */
    @Override
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public Optional<String> tryRender(String templateName, Map<String, Object> variables) {
        try {
            return repository.findByName(templateName)
                    .map(template -> renderer.render(template.getBody(), variables));
        } catch (MissingTemplateVariableException e) {
            // намеренно пробрасываем дальше — см. javadoc выше; НЕ попадает в общий catch
            // RuntimeException ниже, который глушит лукап/доступность БД до Optional.empty()
            throw e;
        } catch (RuntimeException e) {
            // Намеренно широкий catch: для "не нашли шаблон"/инфраструктурных сбоев лукапа
            // tryRender — контракт "best-effort, никогда не бросает наружу" для вызывающих из
            // других модулей (integration-доставка см.
            // OutboundMessageDeliveryScheduler#renderTemplate). Сюда попадают, например,
            // ошибки доступа к БД (DataAccessException при недоступности/несовместимости
            // схемы templates) — для durable outbound-конвейера сбой ИМЕННО справочника
            // шаблонов не должен превращаться в retry/circuit-breaker событие канала
            // доставки: это два разных по природе отказа, которые не нужно путать.
            log.warn("Template '{}' lookup/render failed, falling back to caller default: {}",
                    templateName, e.toString());
            return Optional.empty();
        }
    }
}
