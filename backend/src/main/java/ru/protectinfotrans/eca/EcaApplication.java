package ru.protectinfotrans.eca;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Главный класс ECA-системы.
 * Event-Condition-Action система для обработки авиационных сообщений.
 * Аналог модуля Sequencer из AIRCOM ServerPlatform (SITA).
 *
 *
 * @author ФГУП «ЗащитаИнфоТранс»
 */
@SpringBootApplication
@EnableScheduling
public class EcaApplication {

    public static void main(String[] args) {
        SpringApplication.run(EcaApplication.class, args);
    }

}
