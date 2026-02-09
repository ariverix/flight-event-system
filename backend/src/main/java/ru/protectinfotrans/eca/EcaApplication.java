package ru.protectinfotrans.eca;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Главный класс ECA-системы.
 * Event-Condition-Action система для обработки авиационных сообщений.
 * Аналог модуля Sequencer из AIRCOM ServerPlatform (SITA).
 *
 * См. диплом: раздел 1.2.2 (Sequencer), раздел 1.3.3 (модель ECA)
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
