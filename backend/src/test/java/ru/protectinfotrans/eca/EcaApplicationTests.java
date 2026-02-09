package ru.protectinfotrans.eca;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Базовый тест загрузки контекста приложения.
 */
@SpringBootTest
@ActiveProfiles("test")
class EcaApplicationTests {

    @Test
    void contextLoads() {
        // Проверка что Spring контекст загружается без ошибок
    }

}
