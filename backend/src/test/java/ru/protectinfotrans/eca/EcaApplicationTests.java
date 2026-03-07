package ru.protectinfotrans.eca;

import org.junit.jupiter.api.Test;

/**
 * Базовый тест загрузки контекста приложения с реальным PostgreSQL (Testcontainers).
 * H2 не поддерживает JSONB, поэтому используется Testcontainers.
 *
 * См. диплом: Глава 3 (Тестирование)
 */
class EcaApplicationTests extends BaseIntegrationTest {

    @Test
    void contextLoads() {
        // Проверка что Spring контекст загружается без ошибок
    }

}
