package ru.protectinfotrans.eca;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Проверяет, что {@link SpaController} перенаправляет на index.html ВСЕ клиентские маршруты
 * React Router (frontend/src/App.tsx) — иначе прямой переход/обновление страницы на таком
 * маршруте падает {@code NoResourceFoundException} (см. GlobalExceptionHandler), которую
 * глобальный обработчик превращает в 500 вместо отдачи SPA-оболочки.
 *
 * <p>Найдено вручную (браузер): прямой переход на /monitoring отдавал 500 — путь отсутствовал
 * в {@code @GetMapping} SpaController, хотя роут `monitoring` есть в App.tsx. Список ниже
 * держать синхронным со списком {@code <Route path="..." >} в App.tsx.
 */
class SpaControllerTest {

    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new SpaController()).build();

    @ParameterizedTest
    @ValueSource(strings = {
            "/sequences", "/sequences/5", "/sequences/5/edit", "/sequences/5/editor",
            "/executions", "/executions/5",
            "/monitoring",
            "/messages",
            "/timeline",
            "/simulator",
            "/demo",
            "/profile",
            "/audit-log",
            "/users",
            "/login",
    })
    void forwardsClientRouteToIndexHtml(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));
    }
}
