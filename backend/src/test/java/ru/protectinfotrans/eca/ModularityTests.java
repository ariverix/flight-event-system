package ru.protectinfotrans.eca;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/**
 * Проверка архитектурных границ модулей.
 * См. диплом: раздел 1.4.2, таблица 1.5
 */
class ModularityTests {

    @Test
    void verifyModularity() {
        ApplicationModules.of(EcaApplication.class).verify();
    }
}
