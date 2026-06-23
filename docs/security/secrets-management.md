# Управление секретами (P4-3)

Статус: принято, 2026-06-23. Ответственные: security-agent + devops-agent.

## Принцип

Секретов в коде и в репозитории нет. Все секреты приходят из окружения (12-factor);
значения по умолчанию в `application.yml`/`docker-compose.yml` — **только dev-заглушки**
для локального запуска и тестов, и в проде ОБЯЗАТЕЛЬНО переопределяются.

## Секреты системы

| Секрет | Переменная | Где читается | Дефолт (dev-only) |
|---|---|---|---|
| Пароль БД | `DB_PASSWORD` | `spring.datasource.password` | `eca_password` |
| Пользователь БД | `DB_USERNAME` | `spring.datasource.username` | `eca_user` |
| URL БД | `DB_URL` | `spring.datasource.url` | localhost/eca_db |
| Ключ подписи JWT | `JWT_SECRET` | `app.jwt.secret` | dev-строка |

Пароли пользователей — BCrypt-хэш в `users.password_hash` (не секрет конфигурации).
Refresh-токены — SHA-256-хэш в `refresh_tokens.token_hash` (см. ADR-0003).

## Локально / dev

`application.yml` и `docker-compose.yml` содержат `${VAR:-dev-default}` — без env всё
поднимается с dev-значениями. Для переопределения — `.env` (gitignored, см. `.env.example`).
Интеграционные тесты используют отдельную БД `eca_test` через `@DynamicPropertySource`
(`BaseIntegrationTest`), на прод-конфиг не влияют.

## Прод-контур

1. **Никаких `.env` с реальными секретами в проде.** Источник — secret-manager:
   - Kubernetes: `Secret` → env/projected volume (целевой деплой — Helm, P8-1);
   - совместимые с Реестром российского ПО менеджеры секретов (HashiCorp Vault OSS /
     отечественные аналоги) — без жёсткой привязки к зарубежным облакам (импортозамещение).
2. `JWT_SECRET` — случайные ≥256 бит (`openssl rand -base64 48`), уникальный на контур.
   Ротация ключа инвалидирует все access-токены (refresh переживёт, т.к. opaque и проверяется
   по БД) — допустимое поведение при инциденте.
3. Доступ к секретам — по принципу наименьших привилегий; в логи/ответы API/Git не попадают.

## Гигиена логов (P4-3)

- Аудит кода: значения паролей/токенов/секретов НЕ логируются (логируются только
  `username`/`userId`/`role`, факты «токен истёк/невалиден» без самого токена).
- `/actuator/info` env-экспозиция выключена (`management.info.env.enabled=false`);
  весь `/actuator/**` за RBAC `SYSTEM_ADMIN` (P4-1).
- Структурные ECS-JSON-логи не сериализуют сущности с секретами целиком.

## Дальнейшее

- OWASP/секрет-сканер в CI (gitleaks/detect-secrets) — P4-4.
- Путь к ГОСТ TLS — `docs/security/gost-tls-path.md`.
