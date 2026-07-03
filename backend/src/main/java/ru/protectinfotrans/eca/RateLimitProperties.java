package ru.protectinfotrans.eca;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Фаза 3 (прогон апгрейда): конфигурация rate limiting (token bucket, bucket4j).
 *
 * <p>Два защищаемых класса путей (per client IP):
 * <ul>
 *   <li><b>auth</b> — {@code /api/v1/auth/**}: защита от брутфорса логина. Дефолт жёсткий
 *       (10 попыток / 60 c на IP).</li>
 *   <li><b>messages</b> — открытый ACARS-ингест {@code /api/v1/messages/**}: защита от флуда.
 *       Дефолт-потолок ВЫСОКИЙ (2000 req/s на IP) — намного выше наблюдаемого легитимного пика
 *       (~375 msg/s, docs/perf/P6-3-acceptance.md), чтобы не резать штатный высокочастотный поток
 *       от шлюза, но обрубать явный runaway-флуд. Тюнится под прод-железо/топологию через env.</li>
 * </ul>
 *
 * <p>Пороги — через {@code application.yml}/env без пересборки (12-factor), по профилям.
 * {@link #enabled} по умолчанию {@code true}; в тестах surefire задаёт {@code false}, чтобы
 * лимитер не резал высоконагруженные интеграционные тесты ингеста/логина (фокусные тесты лимитера
 * — юнит-уровня на {@link RateLimitFilter}). Прод: свойство не задано → включено штатно.
 */
@Component
@ConfigurationProperties(prefix = "app.ratelimit")
@Getter
@Setter
public class RateLimitProperties {

    /** Глобальный тумблер лимитера. Прод: не задано → true. Тесты: surefire задаёт false. */
    private boolean enabled = true;

    /** Лимит для {@code /api/v1/auth/**} (брутфорс логина). */
    private Limit auth = new Limit(10, 60);

    /** Лимит для открытого ACARS-ингеста {@code /api/v1/messages/**} (флуд). */
    private Limit messages = new Limit(2000, 1);

    /**
     * Доверенные reverse-proxy (IP или CIDR, напр. {@code 10.0.0.0/8}, {@code ::1}). Заголовку
     * {@code X-Forwarded-For} доверяем ТОЛЬКО если непосредственный отправитель ({@code remoteAddr})
     * входит в этот список — иначе клиент мог бы подставить произвольный XFF и обнулить лимит
     * (spoofing) или раздуть карты бакетов уникальными ключами (memory-DoS). <b>Дефолт пуст</b> =
     * XFF не доверяется, ключ = TCP-peer ({@code remoteAddr}) — безопасно для прямого запуска
     * (docker-compose, dev). В проде за ingress/LB — задать сеть прокси через
     * {@code APP_RATELIMIT_TRUSTED_PROXIES}.
     */
    private List<String> trustedProxies = new ArrayList<>();

    /**
     * Верхняя граница числа отслеживаемых ключей (IP) на один scope — защита от неограниченного
     * роста карт бакетов (даже при доверенном XFF число клиентов конечно, но ставим потолок с
     * LRU-эвикцией как страховку). Дефолт 100k (≈ незначительная память).
     */
    private int maxTrackedKeys = 100_000;

    /**
     * Один порог токен-бакета: {@link #capacity} токенов, пополняется на {@link #capacity} токенов
     * каждые {@link #refillPeriodSeconds} секунд (greedy refill — токены поступают равномерно).
     */
    @Getter
    @Setter
    public static class Limit {
        private int capacity;
        private int refillPeriodSeconds;

        public Limit() {
        }

        public Limit(int capacity, int refillPeriodSeconds) {
            this.capacity = capacity;
            this.refillPeriodSeconds = refillPeriodSeconds;
        }
    }
}
