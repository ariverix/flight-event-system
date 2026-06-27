package ru.protectinfotrans.eca;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * P6-2: конфигурация retention (удержание данных) для трёх высоконагруженных таблиц.
 *
 * <p>Пороги задаются через environment/application.yml без пересборки (12-factor).
 * Дефолты выбраны под промышленную эксплуатацию: tracking_event_log хранит журнал
 * по бортам 6 месяцев (достаточно для расследования инцидентов), messages — 90 дней
 * (входящий поток ACARS; оперативный диагностический горизонт), audit_log — 1 год
 * (требования ИБ ФГУП «ЗащитаИнфоТранс»).
 *
 * <p>Используется {@link RetentionService} (гейтирован {@code cluster.LeaderElection} →
 * в кластере чистит только реплика-лидер).
 */
@Component
@ConfigurationProperties(prefix = "app.retention")
@Getter
@Setter
public class RetentionProperties {

    /**
     * Сколько месяцев хранить именованные партиции tracking_event_log.
     * Партиции старше этого порога удаляются (DROP TABLE).
     * Дефолт: 6 месяцев.
     */
    private int trackingEventLogMonths = 6;

    /**
     * Сколько дней хранить записи в таблице messages.
     * Строки старше этого порога удаляются (DELETE by received_at).
     * Дефолт: 90 дней.
     */
    private int messagesDays = 90;

    /**
     * Сколько дней хранить записи в таблице audit_log.
     * Строки старше этого порога удаляются (DELETE by created_at).
     * Дефолт: 365 дней (1 год).
     */
    private int auditLogDays = 365;

    /**
     * На сколько месяцев вперёд создавать партиции tracking_event_log заблаговременно
     * (create-ahead). Обеспечивает существование нужной партиции до того, как в неё
     * начнут приходить данные. Дефолт: 3 месяца.
     */
    private int createAheadMonths = 3;
}
