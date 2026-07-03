package ru.protectinfotrans.eca;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Фаза 2 (стабилизация): {@link SchedulingConfig} включается по свойству
 * {@code app.scheduling.enabled} (по умолчанию — да; в тестах surefire задаёт false).
 */
@DisplayName("SchedulingConfig — гейт @Scheduled по app.scheduling.enabled")
class SchedulingConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(SchedulingConfig.class);

    @Test
    @DisplayName("свойство не задано → планирование включено (matchIfMissing, прод-дефолт)")
    void enabledByDefaultWhenPropertyMissing() {
        // surefire выставляет app.scheduling.enabled=false как system property на весь тестовый
        // JVM (см. pom.xml) — здесь его нужно СНЯТЬ, чтобы воспроизвести прод-условие «свойство
        // отсутствует» и проверить matchIfMissing=true. withSystemProperties("<key>") без '='
        // очищает системное свойство на время прогона и восстанавливает после.
        runner.withSystemProperties("app.scheduling.enabled")
                .run(ctx -> assertThat(ctx).hasSingleBean(SchedulingConfig.class));
    }

    @Test
    @DisplayName("app.scheduling.enabled=true → планирование включено")
    void enabledWhenPropertyTrue() {
        runner.withPropertyValues("app.scheduling.enabled=true")
                .run(ctx -> assertThat(ctx).hasSingleBean(SchedulingConfig.class));
    }

    @Test
    @DisplayName("app.scheduling.enabled=false (как в тестах) → планирование выключено")
    void disabledWhenPropertyFalse() {
        runner.withPropertyValues("app.scheduling.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(SchedulingConfig.class));
    }
}
