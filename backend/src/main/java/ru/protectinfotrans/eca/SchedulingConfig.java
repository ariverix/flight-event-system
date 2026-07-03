package ru.protectinfotrans.eca;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * P3 (прогон апгрейда, стабилизация фазы 2): включение {@code @Scheduled}-планировщиков вынесено
 * из {@link EcaApplication} в отдельную конфигурацию, гейтированную свойством
 * {@code app.scheduling.enabled} (по умолчанию — включено).
 *
 * <p><b>Зачем:</b> в интеграционных тестах Flyway между тест-классами делает {@code clean}
 * (DROP SCHEMA) для изоляции. Если в этот момент фоновый {@code @Scheduled}-поллер
 * ({@code OutboundMessageDeliveryScheduler.scheduledPoll} и др.) успел открыть транзакцию и взять
 * блокировку на таблице (напр. {@code UPDATE outbound_messages SET status='SENT'}), то Flyway
 * {@code DROP TABLE} встаёт в ожидание этой блокировки — без таймаута, и весь прогон подвисает
 * (наблюдалось: конкурентный тик поллера ↔ inter-test Flyway-clean → взаимная блокировка).
 * Пред-проверка готовности {@link ApplicationReadiness} (фаза 1) закрывает только СТАРТ приложения
 * (в проде Flyway {@code migrate} проходит ДО открытия ready-гейта — там гонки нет), но НЕ
 * повторяющийся межтестовый {@code clean}, которого в проде не бывает.
 *
 * <p><b>Решение:</b> в тестах авто-тик {@code @Scheduled} выключается
 * ({@code app.scheduling.enabled=false} задаётся surefire в pom.xml для всех форков) — тесты и так
 * вызывают методы опроса напрямую (ungated {@code pollPendingMessages},
 * {@code executionService.checkWaitTimeouts}), поэтому покрытие логики поллеров не страдает, а
 * гонка с Flyway-clean устраняется в корне. В проде свойство не задано → {@code matchIfMissing=true}
 * → планирование включено штатно.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
