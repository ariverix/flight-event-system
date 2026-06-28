package ru.protectinfotrans.eca.execution.adapter.in.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import ru.protectinfotrans.eca.TokenValidatorPort;

/**
 * Конфигурация WebSocket эндпоинта /ws/eca (P7-4).
 *
 * <p>Использует raw WebSocket (не STOMP/SockJS), соответствующий клиентскому
 * {@code WsClient.ts}, который тоже работает с plain WebSocket API.
 *
 * <p>Аутентификация — первым сообщением после подключения (ADR-0005 п.4):
 * JWT не передаётся в URL (не оседает в логах/истории браузера).
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class EcaWebSocketConfig implements WebSocketConfigurer {

    private final WsSessionRegistry  registry;
    private final TokenValidatorPort  tokenValidator;
    private final ObjectMapper        objectMapper;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry webSocketHandlerRegistry) {
        webSocketHandlerRegistry
                .addHandler(ecaWebSocketHandler(), "/ws/eca")
                // разрешаем подключение с dev-сервера фронта (CORS для WS)
                .setAllowedOrigins("http://localhost:5173", "http://localhost:3000", "*");
    }

    @Bean
    public EcaWebSocketHandler ecaWebSocketHandler() {
        return new EcaWebSocketHandler(registry, tokenValidator, objectMapper);
    }
}
