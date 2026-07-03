package ru.protectinfotrans.eca;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Главный класс ECA-системы.
 * Event-Condition-Action система для обработки авиационных сообщений.
 * Аналог модуля Sequencer из AIRCOM ServerPlatform (SITA).
 *
 * <p>{@code @EnableScheduling} вынесено в {@link SchedulingConfig} (гейт по свойству
 * {@code app.scheduling.enabled}, по умолчанию включено) — чтобы в интеграционных тестах авто-тик
 * {@code @Scheduled}-поллеров не конфликтовал с межтестовым Flyway-clean (см. javadoc SchedulingConfig).
 *
 * @author ФГУП «ЗащитаИнфоТранс»
 */
@SpringBootApplication
public class EcaApplication {

    public static void main(String[] args) {
        SpringApplication.run(EcaApplication.class, args);
    }

}
