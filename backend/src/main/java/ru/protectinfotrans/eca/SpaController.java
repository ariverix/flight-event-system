package ru.protectinfotrans.eca;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

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
     */
    @RequestMapping(value = {
            "/sequences/**",
            "/executions/**",
            "/messages/**",
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
