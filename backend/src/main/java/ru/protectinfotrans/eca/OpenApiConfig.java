package ru.protectinfotrans.eca;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

/**
 * Метаданные OpenAPI-спецификации (springdoc).
 *
 * Описывает контракт REST API системы ECA (аналог SITA AIRCOM Sequencer) и
 * настраивает схему авторизации Bearer JWT для Swagger UI — большинство
 * эндпоинтов защищены через {@code SecurityConfig} и требуют заголовок
 * {@code Authorization: Bearer <token>}. Исключение — приёмный эндпоинт
 * ACARS ({@code /api/v1/messages/**}, {@code /api/v1/flights/**}), он открыт
 * по дизайну (см. SecurityConfig) и защищён на уровне сети.
 *
 * Класс лежит в корневом пакете приложения (infra-уровень), а не внутри
 * бизнес-модуля, чтобы не создавать межмодульную зависимость в Spring Modulith.
 */
@Configuration
@OpenAPIDefinition(
        info = @Info(
                title = "flight-event-system API",
                version = "1.0.0",
                description = """
                        Система обработки авиационных событий ECA (Event-Condition-Action) —
                        отечественная промышленная замена модуля SITA AIRCOM Sequencer.
                        Обрабатывает сообщения «борт-земля» (ACARS) и управляет последовательностями,
                        шагами, критериями и алертами.

                        Большинство эндпоинтов требуют JWT (Bearer). Эндпоинт приёма входящих
                        сообщений /api/v1/messages/** и /api/v1/flights/** открыт без аутентификации
                        для внешних ACARS-систем — это осознанное решение, защита на уровне сети.
                        """,
                contact = @Contact(name = "ФГУП «ЗащитаИнфоТранс»")
        ),
        security = @SecurityRequirement(name = "bearerAuth")
)
@SecurityScheme(
        name = "bearerAuth",
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT",
        description = "JWT-токен, полученный через POST /api/v1/auth/login"
)
public class OpenApiConfig {
}
