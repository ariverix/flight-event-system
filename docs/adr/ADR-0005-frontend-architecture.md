# ADR-0005: Архитектура фронтенда flight-event-system

| Поле         | Значение                            |
|--------------|-------------------------------------|
| Статус       | Accepted                            |
| Дата         | 2026-06-27                          |
| Автор        | frontend-architect (P7-1)           |
| Затрагивает  | Весь frontend/ (React 18 / TS 5)    |

---

## Контекст

Frontend существует (~8 200 строк) и содержит рабочие страницы, но лишён:
- стор-библиотеки (только React Context для темы);
- генерации типов из OpenAPI — все типы ручные в `src/types/`;
- WebSocket-слоя для реал-тайм данных (P7-4);
- ESLint-конфига (lint baseline был сломан);
- чёткого разграничения слоёв.

Задача P7-1 — зафиксировать и внедрить промышленный каркас без перезаписи
существующих страниц, сохранив зелёный билд.

---

## Решения

### 1. Слоистая структура

```
src/
  api/              # транспорт + кодогенерация
    axiosConfig.ts  # singleton axios + интерсепторы (JWT, dev-logging, 401→/login)
    *Api.ts         # API-модули (один файл = один ресурс бэкенда)
    generated/
      openapi.ts    # AUTO-GENERATED из docs/openapi/openapi.json (не редактировать)
      schema.ts     # удобные псевдонимы над сгенерированными типами
    ws/
      WsClient.ts   # WebSocket-клиент (reconnect, ping/pong, multiplexed)
      types.ts      # типы WS-конвертов (каналы instance-status, event-log)
      useSubscription.ts  # React-хук для подписки
  store/            # клиентское состояние (Zustand)
    authStore.ts    # auth singleton
    uiStore.ts      # тема, sider collapsed
  hooks/            # React-хуки (обёртки над store/api)
    useAuth.ts      # тонкий shim над authStore — совместим по сигнатуре
  context/          # оставляется для ThemeContext (legacy); мигрирует в uiStore в P7-5
  components/       # UI-компоненты по доменам (layout, sequence, execution …)
  pages/            # страницы верхнего уровня (TimelinePage)
  theme/            # Ant Design токены (DARK_THEME / LIGHT_THEME)
  types/            # УСТАРЕВШИЙ источник типов — вытесняется schema.ts (см. п. 3)
  utils/            # утилиты (criteriaUtils, flowUtils, graphLayout)
```

**Правила зависимостей:**
- `components/` и `pages/` → `hooks/` и `api/*Api.ts` (не импортировать store напрямую в компонентах без нужды)
- `hooks/` → `store/` и `api/`
- `store/` → `api/axiosConfig` (запрещено; иначе цикл)
- `api/*Api.ts` → `api/axiosConfig` + `api/generated/schema` (не `types/`)
- Никаких бизнес-решений движка на фронте — только отображение и конфигурация

### 2. Стор: Zustand 5.x с persist-middleware

**Выбор:** Zustand вместо Redux Toolkit / MobX / Context.

**Обоснование:**
- RTK избыточен для проекта: нет сложных side-effect-графов, достаточно простого
  key-value состояния (пользователь, UI-настройки, фильтры таблиц в будущем).
- Zustand: нет boilerplate (actions/reducers/selectors), TypeScript-дружественный,
  поддерживает persist-middleware из коробки, bundle size ~8 KB.
- React Context с `useReducer` отклонён: нет инструментов DevTools, трудно
  масштабировать вширь (много несвязанных стейтов = много Provider-ов).

**Реализованные срезы (P7-1):**
- `authStore` — пользователь/JWT; persist под ключом `eca-auth` в localStorage.
- `uiStore` — тема, состояние сайдбара; persist под ключом `eca-ui`.

**Правила:**
- `authStore.user.token` — источник истины JWT. Axios-интерсептор дополнительно
  читает `localStorage.jwt` напрямую (нет циклической зависимости store→axios→store).
- `useAuth()` — тонкий shim над `authStore`; компоненты не меняются.
- `ThemeContext` сохранён для обратной совместимости; в P7-5 заменяется `uiStore`.

**Дальнейшие срезы (P7-2..P7-5):**
- Выбранные последовательности / фильтры таблиц / WS-соединение — в отдельных
  Zustand-сторах по мере надобности; не в одном God-store.

### 3. OpenAPI как единственный источник типов

**Принцип:** контракт API только из `docs/openapi/openapi.json` (springdoc P0-3).
Ручные типы в `src/types/` — УСТАРЕВШИЙ слой, вытесняется инкрементально.

**Инструмент:** `openapi-typescript` (devDependency, `npm run gen:api`).

**Команда регенерации:**
```sh
npm run gen:api
# = openapi-typescript ../docs/openapi/openapi.json -o src/api/generated/openapi.ts
```

**Файлы:**
- `src/api/generated/openapi.ts` — AUTO-GENERATED, commit'ится в репо (нет CI-зависимости от запуска)
- `src/api/generated/schema.ts` — псевдонимы `Api*` поверх `components['schemas'][...]`

**Образец миграции на сгенерированные типы:** `src/api/templatesApi.ts`,
`src/api/dlqApi.ts`, `src/api/conditionsApi.ts` — новые API-модули, написанные
целиком на `schema.ts` без обращения к `src/types/`.

**План вытеснения ручных типов из `src/types/`:**
| Файл | Когда мигрировать | Заметки |
|------|------------------|---------|
| `types/sequence.ts` | P7-2 (редактор) | При создании компонентов шагов |
| `types/execution.ts` | P7-4 (реал-тайм) | При WS-интеграции |
| `types/message.ts` | P7-3 (формы) | При формах критериев |
| `types/auth.ts` | P7-5 | LoginResponse не в OpenAPI-схеме (→ `object`) |

**Правило:** при создании нового API-модуля — только `schema.ts`. Ручные типы
трогать только при явной миграции; не вводить новые вручную.

### 4. WebSocket-слой

**Реализация:** `src/api/ws/` (WsClient, types, useSubscription).

**URL:** переменная окружения `VITE_WS_URL` (`.env.local`). Если не задана —
клиент работает в нет-оп режиме: соединение не открывается, приложение не падает.

**Каналы (P7-4):**
- `instance-status` → `InstanceStatusPayload` (статус инстанса в реал-тайм)
- `event-log` → `EventLogPayload` (Event Log класса Tracking)

**Reconnect:** экспоненциальный backoff 1 → 2 → 4 → 8 → 16 → 32 с.

**Ping/pong:** каждые 30 с для детекции «тихих» разрывов.

**Бэкенд WS-эндпоинт** реализуется в P7-4 (бэкенд). Этот слой готов к подключению
без изменений API.

**Безопасность аутентификации WS (ОБЯЗАТЕЛЬНО в P7-4):**
Текущая реализация передаёт JWT в query-параметре URL (`?token=...`). Это ВРЕМЕННАЯ
заглушка до P7-4. Токен в URL попадает в сервер-логи и историю браузера.
В P7-4 необходимо:
1. Подключаться БЕЗ токена в URL
2. После `onopen` отправлять первое сообщение `{ channel: "auth", payload: { token } }`
3. Бэкенд проверяет токен и только после этого принимает подписки
4. Удалить строку с `?token=` из `WsClient.connect()` (помечена `TODO P7-4(security)`)

**Хук использования (P7-4):**
```ts
useSubscription('instance-status', useCallback((payload) => {
  dispatch(updateInstance(payload));
}, [dispatch]));
```

### 5. i18n (RU/EN)

**Статус P7-1:** инфраструктура i18n НЕ внедрена — вынесено в P7-5.

**Обоснование:** все текущие страницы написаны с захардкоженными RU-строками.
Внедрение `react-i18next` в P7-1 потребовало бы обхода всех 8 200 строк и
заморозило бы другие задачи (P7-2..P7-4).

**Правило до P7-5:** новые компоненты (P7-2, P7-3, P7-4) не добавляют новых
захардкоженных строк — используют заглушки-константы в отдельном файле
`src/i18n/ru.ts` (создаётся в P7-5).

**Технологический выбор (зафиксировать в P7-5):** `react-i18next` + `i18next` +
`i18next-browser-languagedetector`. Ключи — flat JSON в `public/locales/{ru,en}/`.
Переключение языка — в `uiStore`.

### 6. ESLint

**Файл:** `eslint.config.js` (ESLint 9 flat config).

**Правила:**
- `@typescript-eslint/no-explicit-any` — `warn` (P7-1); повышается до `error` в P7-5
  после полной очистки существующего кода.
- `@typescript-eslint/no-unused-vars` — `error` (argsIgnorePattern `^_`).
- `react-hooks/exhaustive-deps` — `warn` (существующие компоненты имеют ~20 нарушений).
- Сгенерированная папка `src/api/generated/` исключена из lint.

**Baseline (P7-1):** 0 ошибок, 68 предупреждений (все в существующих компонентах).

### 7. Доступность (a11y) и производительность

- Код-сплиттинг: все страницы уже через `React.lazy()` в App.tsx (существующее).
- Тяжёлый редактор React Flow (FlowWrapper ~300 KB gzip 100 KB) — уже lazy.
  В P7-2 добавить `React.memo` на кастомные ноды.
- a11y-аудит и роль-зависимый UI — P7-5.

---

## Последствия

**Положительные:**
- Единый источник правды для auth-состояния (Zustand singleton).
- Типы API из OpenAPI — устраняет рассинхрон фронт/бэк.
- WS-слой готов к P7-4 без изменений API.
- ESLint-конфиг фиксирует правила и позволяет инкрементально ужесточать.

**Компромиссы:**
- `src/types/` существует параллельно со `schema.ts` до полной миграции (риск дублирования). 
  Митигация: план миграции выше + правило «новый код = только schema.ts».
- JWT хранится в двух местах (`localStorage.jwt` + `authStore`).
  Митигация: унификация в P7-5 (axios interceptor читает из store напрямую).
- i18n отложена до P7-5.

---

## Связанные ADR

- ADR-0001: модульный монолит vs микросервисы (бэкенд)
- ADR-0002: Transactional Outbox (бэкенд)
- ADR-0003: JWT access/refresh tokens (бэкенд)
- ADR-0004: Leader Election (бэкенд)
