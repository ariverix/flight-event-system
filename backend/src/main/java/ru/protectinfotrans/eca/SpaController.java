package ru.protectinfotrans.eca;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Контроллер для поддержки клиентской маршрутизации React Router (SPA Fallback).
 * Все запросы, не соответствующие API и статическим ресурсам, перенаправляются
 * на index.html, чтобы React Router обработал маршрут на стороне клиента.
 *
 */
@Controller
public class SpaController {

    /**
     * Перехватить все маршруты, не обработанные Spring MVC,
     * и вернуть index.html для обработки React Router.
     *
     * <p>P4-4: только GET — это навигация SPA (отдаём оболочку index.html). Ограничение метода
     * закрывает находку FindSecBugs SPRING_CSRF_UNRESTRICTED_REQUEST_MAPPING (неограниченный
     * по методу {@code @RequestMapping} принимал бы и POST/PUT/DELETE).
     */
    @GetMapping(value = {
            "/sequences/**",
            "/executions/**",
            "/monitoring/**",
            "/messages/**",
            "/timeline/**",
            "/simulator/**",
            "/users/**",
            "/audit-log",
            "/profile",
            "/demo",
            "/login",
            "/dashboard"
    })
    public String forward() {
        return "forward:/index.html";
    }
}
