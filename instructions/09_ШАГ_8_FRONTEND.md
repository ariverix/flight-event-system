# ШАГ 8: FRONTEND (React + TypeScript + Ant Design)

## Что сделать:

Создать полноценный SPA для операторов и администраторов.

### Структура файлов:

```
src/
├── api/
│   ├── axiosConfig.ts          # Axios instance + JWT interceptor
│   ├── sequenceApi.ts          # API для последовательностей
│   ├── executionApi.ts         # API для экземпляров выполнения
│   ├── messageApi.ts           # API для сообщений
│   └── authApi.ts              # API для аутентификации
├── components/
│   ├── layout/
│   │   ├── AppLayout.tsx       # Ant Design Layout + Sider с меню навигации
│   │   └── ProtectedRoute.tsx  # Редирект на /login если нет JWT
│   ├── sequence/
│   │   ├── SequenceList.tsx    # Таблица с фильтрами по status и пагинацией
│   │   ├── SequenceForm.tsx    # Форма создания/редактирования последовательности
│   │   ├── StepForm.tsx        # Динамические поля в зависимости от StepType
│   │   ├── SequenceFlow.tsx    # React Flow READ-ONLY визуализация графа шагов
│   │   └── CriteriaEditor.tsx  # Редактор критериев start/stop (JSON-based)
│   ├── execution/
│   │   ├── ExecutionList.tsx   # Список экземпляров с фильтрами
│   │   ├── ExecutionDetail.tsx # Детали экземпляра + таблица истории шагов
│   │   └── ExecutionFlow.tsx   # React Flow с подсветкой текущего шага
│   ├── message/
│   │   ├── MessageSimulator.tsx # Форма отправки тестовых сообщений и stage-change
│   │   └── MessageLog.tsx      # Журнал всех сообщений с фильтрами
│   └── user/
│       ├── LoginPage.tsx       # Форма входа
│       └── UserManagement.tsx  # Управление пользователями (только ADMIN)
├── hooks/
│   ├── useAuth.ts             # Хук авторизации (хранение JWT в localStorage, роль)
│   └── usePolling.ts          # Хук для периодического обновления данных (каждые 5 сек)
├── types/
│   ├── sequence.ts            # TypeScript типы для последовательностей и шагов
│   ├── execution.ts           # Типы для экземпляров выполнения
│   ├── message.ts             # Типы для сообщений
│   └── auth.ts                # Типы для аутентификации
├── utils/
│   ├── flowUtils.ts           # Конвертация шагов в узлы/рёбра React Flow
│   └── criteriaUtils.ts       # Утилиты для работы с JSON-критериями
├── App.tsx                    # Routing (react-router-dom)
└── main.tsx                   # Точка входа
```

### 9 страниц/экранов:

| # | Экран | UC | Описание |
|---|---|---|---|
| 1 | Login | — | Форма входа (Ant Design Form, username + password) |
| 2 | Dashboard | UC-05 | Обзор: кол-во активных последовательностей, работающих экземпляров, последние события. Карточки со статистикой (Ant Design Card, Statistic). |
| 3 | Sequences | UC-01 | Таблица Ant Design с фильтрами по status, пагинацией, кнопками CRUD |
| 4 | Sequence Detail | UC-01,02,03 | Информация о последовательности + список шагов + React Flow визуализация + формы для добавления/редактирования шагов + кнопка Activate/Deactivate |
| 5 | Executions | UC-05 | Таблица экземпляров с фильтрами по status, aircraftId, sequenceId. Пагинация. |
| 6 | Execution Detail | UC-05 | React Flow с подсветкой текущего шага (зелёный=пройден, жёлтый=текущий, серый=не достигнут) + таблица истории шагов с timestamps и результатами |
| 7 | Message Simulator | UC-06 | Форма отправки: выбор типа (Incoming Message / Flight Stage Change), поля. Кнопка "Send". Лог отправленных сообщений. |
| 8 | Message Log | — | Журнал всех сообщений с фильтрацией по aircraftId, messageType, dateRange |
| 9 | User Management | UC-09 | Таблица пользователей, форма регистрации (только ADMIN), кнопка toggle enabled |

### React Flow — визуализация (READ-ONLY!):

> **ВАЖНО:** React Flow используется ТОЛЬКО для визуализации, НЕ для редактирования. Drag-and-drop ОТКЛЮЧЁН. Шаги редактируются через Ant Design формы.

**flowUtils.ts — конвертация шагов в граф:**
- Каждый шаг → узел (node) с типом и цветом:
  - ACTION → синий (#1890ff)
  - EVALUATE → оранжевый (#faad14)
  - WAIT → фиолетовый (#722ed1)
- Переходы → рёбра (edges):
  - onSuccessAction → зелёная стрелка (solid line)
  - onFailureAction → красная стрелка (dashed line)
  - GOTO → стрелка к указанному шагу
  - END → к финальному узлу "END" (зелёный)
  - ABORT → к финальному узлу "ABORT" (красный)
- Автоматическое расположение через dagre (вертикальное, сверху вниз)

**ExecutionFlow.tsx — подсветка текущего шага:**
- Пройденные шаги (в stepHistory) → зелёная обводка
- Текущий шаг (currentStepIndex) → жёлтая обводка, пульсация
- Не достигнутые шаги → серая обводка
- Polling каждые 5 секунд для обновления статуса (хук usePolling)

### Ant Design компоненты:

- `Layout`, `Sider`, `Menu` — навигация
- `Table` — списки с пагинацией (серверной)
- `Form`, `Input`, `Select`, `InputNumber`, `Switch`, `Checkbox` — формы
- `Card`, `Statistic`, `Tag`, `Badge` — информация
- `Button`, `Space`, `Divider` — действия
- `message`, `notification` — уведомления о результатах
- `Modal` — подтверждения (удаление, активация)
- `Descriptions` — детали записи

### axiosConfig.ts:

```typescript
const api = axios.create({ baseURL: 'http://localhost:8080/api/v1' });

api.interceptors.request.use(config => {
    const token = localStorage.getItem('jwt');
    if (token) config.headers.Authorization = `Bearer ${token}`;
    return config;
});

api.interceptors.response.use(
    response => response,
    error => {
        if (error.response?.status === 401) {
            localStorage.removeItem('jwt');
            window.location.href = '/login';
        }
        return Promise.reject(error);
    }
);
```

### StepForm.tsx — динамические поля:

В зависимости от выбранного StepType показывай разные поля:
- **ACTION:** Select ActionType → показать поля для выбранного типа действия (templateName, parameters для SEND_UPLINK; conditionName, alertLevel для RAISE_CONDITION; durationSeconds для WAIT_TIME)
- **EVALUATE:** Select CriterionType → показать поля для выбранного критерия
- **WAIT:** То же что EVALUATE + обязательное поле timeoutSeconds

Для каждого типа — переходы: onSuccessAction (Select), onSuccessGotoStep (InputNumber, если GOTO), onSuccessNotify (Checkbox), аналогично для Failure.

## Критерий завершения:
Все 9 экранов работают, данные загружаются с backend, формы отправляют запросы, React Flow визуализирует последовательности, JWT авторизация работает (после логина все запросы с Bearer token).

**Коммит:** `"Step 8: React frontend — 9 screens, React Flow visualization, Ant Design UI"`
