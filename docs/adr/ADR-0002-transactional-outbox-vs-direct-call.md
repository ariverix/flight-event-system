# ADR-0002: Transactional Outbox (Spring Modulith Event Publication Registry) против прямого синхронного вызова между модулями

**Status:** Accepted

**Date:** 2026-06-20

**Authors:** architect

## Context

Межмодульное взаимодействие в `flight-event-system` (`eventprocessor` → `execution` →
`integration`, плюс `sequence` → `execution`) сейчас уже реализовано через
доменные события (`NormalizedEvent`, `ExecutionStartedEvent`, `StepTransitionEvent`,
`StepNotificationEvent`, `ExecutionCompletedEvent`, `SequenceActivatedEvent`/
`SequenceDeactivatedEvent`), публикуемые через `ApplicationEventPublisher`/
`EventPublisherPort` и принимаемые через `@org.springframework.modulith.events.ApplicationModuleListener`.

Факты текущего состояния (проверено в коде, не предположение):

- Таблица `event_publication` существует с миграции V10
  (`backend/src/main/resources/db/migration/V10__create_event_publication_table.sql`)
  — это стандартная схема **Spring Modulith Event Publication Registry**
  (модуль `spring-modulith-starter-jpa`, см. `backend/pom.xml`, версия 1.3.1).
- `@ApplicationModuleListener` уже используется в трёх местах:
  `ExecutionService.processEvent` (eventprocessor → execution),
  `NotificationEventListener.onStepNotification` (execution → integration),
  и неявно в инфраструктуре резюма (`ExecutionResumeRunner`, P1-4).
  Подтверждённое в P1-6 поведение: вызов асинхронный, событие фиксируется в
  `event_publication` в ОДНОЙ локальной транзакции с изменением состояния
  публикующей стороны (запись в `messages`/`execution_instances` и INSERT в
  Outbox коммитятся или откатываются вместе — атомарность гарантирована JPA/
  Hibernate в рамках одной транзакции БД, без участия брокера).
- Часть взаимодействий НЕ идёт через события, а вызывается напрямую,
  синхронно, внутри транзакции ECA-движка: `ActionStepRule` (правило Easy
  Rules уровня ACTION) вызывает `MessageOutputPort.sendUplink/sendGround/
  raiseCondition/closeCondition` напрямую — это вызов порта `execution` →
  `integration` через интерфейс, не через `Externalized`/Modulith Event.
  Это осознанно: ACTION-шаг — часть синхронного перехода состояния
  (`advanceExecution`), а не fan-out на независимых потребителей; SITA-паритет
  требует, чтобы решение CONTINUE/GOTO/END/ABORT принималось по результату
  SUCCESS/FAILURE этого же действия в той же логической операции, то есть это
  не тот случай, где Outbox даёт что-то сверх прямого вызова — действие и
  его результат неразделимы по семантике движка.
- Флаг `spring.modulith.events.republish-outstanding-events-on-restart`
  (или эквивалент) **не задан** ни в `application.yml`, ни в
  `application-dev.yml` — то есть событие, чья публикация не была
  подтверждена (`completion_date IS NULL`) до краша/рестарта процесса,
  по умолчанию НЕ переигрывается автоматически при старте. Это пробел, не
  решение — Modulith просто работает на дефолтных настройках.
- Ни один из трёх существующих `@ApplicationModuleListener` не имеет
  выделенного дедуп-механизма по идентификатору события. Фактическая
  идемпотентность каждого — **естественная**, через проверку состояния перед
  действием (а не через явный "processed event" реестр):
  - `ExecutionService.processEvent` → `checkStopCriterionTransactional`,
    `tryResumeWaitingInstanceTransactional` перечитывают инстанс по id и
    делают no-op, если статус уже не подходящий (`RUNNING`/`WAITING`
    проверяется перед действием) — комментарии в коде (P1-6) это явно
    фиксируют как намеренное свойство, а не побочный эффект.
  - `checkStartCriteria`/`startExecution` — слабое место: повторная доставка
    того же `NormalizedEvent` после рестарта НЕ идемпотентна. Критерий старта
    не проверяет "не уже ли запущен инстанс по этому конкретному событию" —
    он создаёт новый `ExecutionInstance` (`INSERT`) при каждом совпадении
    критерия. Повторная доставка `NormalizedEvent` → второй вызов
    `checkStartCriteria` → второй `startExecution` → дублирующийся инстанс.
  - `NotificationEventListener.onStepNotification` — не идемпотентен по
    построению: `notificationPort.notifyStepResult` не имеет дедуп-ключа,
    повторная доставка `StepNotificationEvent` даст повторное уведомление.
    Текущая цена невелика (`LogNotificationAdapter` — заглушка, лог), но
    контракт порта не несёт информации, позволяющей вызывающему дедупить.
  - Сам комментарий в `ExecutionService.resumeRunningInstanceAfterRestart`
    (P1-4) прямо указывает на этот ADR: *"повторный прогон ПОСЛЕ рестарта
    может повторно отправить сообщение... Это осознанный пробел, закрываемый
    Outbox (P1-7): там отправка становится идемпотентной операцией с
    собственным дедуп-ключом (instance.id + stepIndex)"*.
- `MessageOutputPort` сейчас реализован `LogMessageAdapter` — заглушкой
  (`TODO: реальная отправка`). Реального внешнего ACARS-канала с
  материальной ценой дублирования (двойная uplink-команда на борт) пока нет
  — но контракт порта и точка вызова уже зафиксированы, и именно их меняет
  будущий реальный адаптер. Дизайн идемпотентности нужно закладывать сейчас,
  до того как заглушка будет заменена, а не постфактум.
- `ApplicationModules.of(EcaApplication.class).verify()`
  (`backend/src/test/java/ru/protectinfotrans/eca/ModularityTests.java`) —
  зелёный, межмодульные обращения уже идут через публичные API/события, не
  через внутренние пакеты. Любое решение этого ADR не должно его нарушить.

Развилка: либо оставить/расширить межмодульное взаимодействие на прямых
синхронных вызовах портов (как уже сделано для `ActionStepRule` →
`MessageOutputPort`), либо использовать Transactional Outbox (Spring
Modulith Event Publication Registry) как канал по умолчанию для
межмодульных доменных событий, дополнив его недостающими промышленными
свойствами (republish on restart, идемпотентный приём).

## Decision

Мы используем **Transactional Outbox через Spring Modulith Event
Publication Registry** (`event_publication`, `@ApplicationModuleListener`,
`Externalized` для внешних публикаций) как канал по умолчанию для
межмодульных доменных событий между `eventprocessor`, `execution`,
`sequence` и `integration` — **вместо** превращения этих границ в прямые
синхронные вызовы портов.

Конкретно:

1. **Правило по умолчанию**: если модуль A должен уведомить модуль B о
   факте, произошедшем в его собственной транзакции (нормализация
   сообщения, переход шага, завершение/прерывание инстанса, активация/
   деактивация последовательности), — это доменное событие через
   `ApplicationEventPublisher`/`EventPublisherPort`, принимаемое через
   `@ApplicationModuleListener`. Так уже сделано для всех текущих
   межмодульных уведомлений (см. Context) — решение фиксирует существующую
   практику как обязательную, не вводит новую.
2. **Исключение, тоже фиксируется явно**: вызов через прямой синхронный
   порт (`MessageOutputPort`, `ConditionQueryPort`, `NotificationPort` —
   текущий способ вызова `ActionStepRule`/`ExecutionService` в
   `integration`) остаётся допустимым ТОЛЬКО когда вызывающая сторона
   обязана знать результат (SUCCESS/FAILURE) синхронно в рамках ТЕКУЩЕГО
   перехода состояния ECA-движка (ACTION-шаг → его результат → решение
   CONTINUE/GOTO/END/ABORT — три слоя ECA не разделяются временным разрывом
   очереди). Если в будущем понадобится сделать сам `sendUplink` неблокирующим
   (например ACARS-шлюз асинхронный и не возвращает результат немедленно) —
   это смена контракта порта (доп. ADR), не повод тащить событие туда, где
   нужен синхронный ответ.
3. **Промышленные свойства Outbox, которых сейчас не хватает** (детали — в
   разделе «Оценка текущего состояния» и в спецификации ниже), обязательны
   для статуса Accepted этого ADR:
   - Включить `republish-outstanding-events-on-restart` (или актуальный
     эквивалент флага Spring Modulith 1.3.1) — без этого Outbox не
     отличается от "fire and forget" с логом в БД на случай постмортема:
     ключевое свойство Transactional Outbox — переживание рестарта с
     гарантированным повтором незавершённых публикаций, а не просто
     атомарная запись.
   - Сделать идемпотентными ВСЕ существующие и новые
     `@ApplicationModuleListener`: at-least-once — это контракт Spring
     Modulith Event Publication Registry, и потребитель обязан быть готов к
     повторной доставке того же события (после republish, после retry на
     транзиентной ошибке листенера). Это не аспект "на будущее" — это
     прямое следствие включения п. предыдущего пункта.
4. **Границы модулей не меняются.** Это решение не вводит новых
   `@ApplicationModule`/публичных API — оно фиксирует ПРАВИЛО для уже
   существующих границ (`eventprocessor`/`execution`/`sequence` публикуют
   события, `execution`/`integration` их слушают) и закрывает выявленный
   пробел (надёжность повтора + идемпотентность), не трогая сами контракты
   событий (`NormalizedEvent`, `StepTransitionEvent` и т.д. остаются как
   есть по форме).

Связь с ADR-0001: Transactional Outbox — это явно зафиксированный там enabler
будущего вычленения `integration` (или другого модуля) в отдельный процесс
(ADR-0001, Decision п.3 и Consequences). Если `integration` станет отдельным
сервисом, события, которые сейчас идут через `event_publication` в общей БД,
естественно превращаются в события через внешний транспорт (брокер или HTTP
webhook с тем же at-least-once+idempotent-consumer контрактом) — структура
"продюсер коммитит факт публикации в своей транзакции, потребитель
идемпотентен" не меняется при смене транспорта. Прямые синхронные вызовы
портов, наоборот, при вычленении сервиса немедленно требуют либо
распределённой транзакции, либо саги — то решение, которое ADR-0001 уже
отверг для текущего масштаба внедрения.

## Consequences

**Положительные:**

- Атомарность "изменение состояния + публикация события" гарантируется
  локальной транзакцией БД без участия внешнего брокера — нет окна между
  `save()` и публикацией, в котором краш приводит к потере события (что было
  бы возможно при паттерне "сохранить, потом вызвать `publishEvent` без
  Outbox" — обычное in-memory `ApplicationEventPublisher` без Modulith JPA
  registry эту гарантию не даёт).
- At-least-once доставка переживает рестарт процесса (после включения
  republish) — критично для длительно работающего ECA-движка, где `sequence
  instance` сам обязан переживать рестарт (см. CLAUDE.md, P1-3/P1-4); без
  republish Outbox даёт надёжность только "в рамках одного запуска
  процесса", что слабее заявленного инварианта.
- Развязка модулей по времени и по отказам: `eventprocessor` коммитит
  `NormalizedEvent` и не блокируется на доступности/скорости `execution`;
  сбой обработчика в `execution` не откатывает уже зафиксированный приём
  сообщения в `eventprocessor`.
- Путь к будущему вынесению `integration` (или `execution`) в отдельный
  процесс не требует пересмотра паттерна надёжности — см. связь с ADR-0001.
- Не вводит новую тяжёлую зависимость: `spring-modulith-starter-jpa` уже в
  `pom.xml`, used since V10 — это включение уже оплаченной возможности, не
  новая инфраструктура.

**Отрицательные / принятые риски:**

- At-least-once требует дисциплины идемпотентности у КАЖДОГО нового
  `@ApplicationModuleListener` навсегда — это правило, которое нужно
  соблюдать каждой следующей фиче (sequence-engine-dev, integration-dev),
  не разовая работа. Принятый риск — нарушение дисциплины даст тихий баг
  (дублирующийся побочный эффект), не ошибку компиляции/деплоя. Митигируется
  тестом-доказательством ("повторная доставка → эффект ровно один раз") как
  обязательным паттерном для каждого листенера, а не только для текущих
  трёх.
- Видимая задержка обработки (асинхронность `@ApplicationModuleListener`)
  по сравнению с синхронным вызовом — для ECA-движка это уже принято и
  подтверждено ревью P1-6 (приемлемо при текущих SLA, не миллисекундная
  биржевая обработка).
- `event_publication` растёт без TTL-очистки в текущем коде — отдельный
  эксплуатационный вопрос (Spring Modulith поддерживает
  `completion-mode: DELETE`/`UPDATE` и периодическую очистку), не блокирует
  Accepted здесь, но должен быть учтён в ТЗ ниже как минимум явным указанием
  режима.

**Что это требует от команды дальше:**

- sequence-engine-dev реализует п.3 Decision (флаги + идемпотентность
  листенеров) по спецификации в разделе «Спецификация для реализации» этого
  ADR.
- db-dev привлекается ТОЛЬКО если по итогу проектирования идемпотентности
  потребуется новая таблица/индекс (см. вывод в спецификации — в данном ADR
  явно указано: миграция нужна/не нужна и почему).
- Любой новый межмодульный факт по умолчанию оформляется событием +
  `@ApplicationModuleListener`, идемпотентным с первого коммита — не
  "сделаем неидемпотентным, потом поправим".

## Оценка текущего состояния

| Свойство промышленного Outbox | Есть сейчас | Источник в коде |
|---|---|---|
| Атомарная запись события в транзакции продюсера | Да | `event_publication` (V10) + Spring Modulith JPA registry — встроенное поведение `ApplicationEventPublisher` под Modulith |
| Асинхронная доставка потребителю | Да | `@ApplicationModuleListener` — подтверждено ревью P1-6 |
| At-least-once с повтором при сбое листенера (без рестарта) | Да (дефолт Modulith — retry до подтверждения completion) | Поведение библиотеки, не специфичный код проекта |
| Переживание рестарта (republish outstanding) | **Нет** | Флаг не задан в `application.yml`/`application-dev.yml` |
| Идемпотентный приём — `ExecutionService.processEvent` (stop/waiting на существующем инстансе) | Да, естественно | Проверка статуса перед действием (`RUNNING`/`WAITING`), задокументировано в P1-6 |
| Идемпотентный приём — старт нового инстанса (`checkStartCriteria`→`startExecution`) | **Нет** | `startExecution` всегда `INSERT`, нет проверки "уже стартован по этому событию" |
| Идемпотентный приём — `NotificationEventListener.onStepNotification` | **Нет** (цена сейчас низкая — заглушка-лог) | `notifyStepResult` без дедуп-ключа |
| Идемпотентность будущего реального ACARS-вывода (`sendUplink`/`sendGround` через `MessageOutputPort`) | Не применимо сейчас (`LogMessageAdapter` — заглушка), но контракт порта это НЕ предусматривает | `ActionStepRule` вызывает порт напрямую, без дедуп-ключа в сигнатуре |
| Идемпотентный приём ACARS-сообщения НА ВХОДЕ (`POST /api/v1/messages/incoming`) | **Нет**, и это отдельная задача, не предмет этого ADR | `IncomingMessage` не имеет внешнего message id/uniqueness; `EventProcessorService.receiveMessage` не дедупит. Это идемпотентность ВХОДНОГО REST-приёма от ACARS-шлюза (другая граница системы — внешний мир → `eventprocessor`), а не межмодульного Outbox-события внутри монолита. Требует отдельной проработки при реализации реального ACARS-адаптера (см. ADR-0001 References, future work) — здесь зафиксировано только как явно НЕ входящее в объём P1-7. |

Вывод: фундамент (атомарная запись + async доставка) уже работает "из
коробки" Spring Modulith. Промышленного уровня не хватает по двум осям:
(1) конфигурация — republish on restart не включён; (2) дисциплина
потребителя — два из трёх listener'ов не идемпотентны при повторной
доставке, и публикуемый комментарий в `ExecutionService` (P1-4) прямо
называет это пробелом, закрываемым этим ADR.

## Спецификация для реализации (P1-7, часть 2)

Адресовано: sequence-engine-dev (основная реализация), db-dev (только если
миграция понадобится — см. явный вывод ниже).

### 1. Флаги `application.yml`

Добавить в `backend/src/main/resources/application.yml` (секция
`spring.modulith.events`, рядом с уже существующей `jdbc.schema-initialization`):

```yaml
spring:
  modulith:
    events:
      jdbc:
        schema-initialization:
          enabled: true
      republish-outstanding-events-on-restart: true
      completion-mode: update   # явно зафиксировать: оставлять завершённые записи (completion_date)
                                  # для аудита/Event Log класса Tracking (CLAUDE.md), не DELETE —
                                  # см. ниже про рост таблицы
```

Проверить точное имя свойства под Spring Modulith 1.3.1 (могло измениться
между минорными версиями — в 1.1.x было
`spring.modulith.events.republish-outstanding-events-on-restart`, нужно
сверить с актуальной документацией/исходниками `spring-modulith-events-core`
1.3.1 при реализации, тест №3 ниже это докажет, если имя неверное —
свойство не сработает молча). Если `completion-mode: update` создаёт
конфликт с аудитом (Event Log должен видеть факт публикации) — допустимо
оставить `DELETE` для уже подтверждённых публикаций при условии, что Event
Log класса Tracking (CLAUDE.md) не зависит от `event_publication` как
источника данных (он не должен — это внутренний реестр доставки Modulith,
не бизнес-журнал событий; проверить, что `eventprocessor`/`execution` не
читают `event_publication` напрямую где-либо в текущем коде — на момент
написания этого ADR такого чтения не найдено).

### 2. Нужна ли миграция V23 — явный вывод: **НЕТ, для базового объёма P1-7 не нужна**

Обоснование: идемпотентность всех ТРЁХ текущих listener'ов достижима через
**естественную идемпотентность проверкой состояния перед действием** —
паттерн, уже применённый и задокументированный в P1-6
(`checkStopCriterionTransactional`, `tryResumeWaitingInstanceTransactional`).
Отдельная таблица `processed_events`/dedup нужна только если бизнес-операция
сама по себе не несёт состояния, по которому можно определить "уже
сделано" — это не тот случай ни для одного из трёх listener'ов:

- **`ExecutionService.processEvent` → `checkStartCriteria` → `startExecution`
  (НЕ идемпотентен сейчас — единственный реальный пробел среди существующих
  listener'ов).** Дедуп-ключ — естественный, без новой таблицы: запрос
  "существует ли уже `ExecutionInstance` с `(sequenceId, aircraftId,
  flightNumber)` и статусом, означающим, что данный конкретный запуск уже
  обработан для ЭТОГО конкретного триггер-сообщения" недостаточен буквально
  (один борт может легитимно иметь НЕСКОЛЬКО последовательных инстансов
  одной и той же sequence за разные рейсы — повторный старт другого рейса не
  дубликат). Правильный дедуп-ключ — это **`messageId` исходного
  `NormalizedEvent`**, не факт существования инстанса вообще. Решение: при
  старте записывать `triggeringMessageId` (поле `NormalizedEvent.messageId`,
  уже существует) на `ExecutionInstance` (новая nullable-колонка
  `triggering_message_id BIGINT` — это ДОБАВЛЕНИЕ КОЛОНКИ, требует миграции,
  см. пересмотр вывода ниже) и проверять перед `INSERT`, нет ли уже
  инстанса с тем же `(sequenceId, aircraftId, flightNumber,
  triggeringMessageId)`.

  **Пересмотр вывода**: если естественной идемпотентности для
  `startExecution` без новой колонки добиться нельзя (а анализ выше
  показывает, что нельзя — `messageId` сейчас не сохраняется на
  `ExecutionInstance` вообще), то **миграция V23 нужна**, но минимальная:
  одна nullable-колонка, не отдельная dedup-таблица.

  **Альтернатива без миграции** (предпочесть, если sequence-engine-dev
  подтвердит достаточность на практике): дедуп через сам Spring Modulith
  Event Publication Registry — НЕ создавать второй `ExecutionInstance` для
  УЖЕ обработанного `(listenerId, NormalizedEvent)`, опираясь на то, что при
  повторной доставке ОДНОГО И ТОГО ЖЕ `event_publication.id` Modulith не
  вызовет другой listener дважды параллельно для разных id — но это не
  снимает риск повторного `startExecution`, если republish переигрывает то
  же `event_publication.id` (тот же event), потому что `processEvent`
  сам ничем не помечает, что для конкретного `NormalizedEvent.messageId` уже
  выполнил фан-аут старта. Поэтому колонка `triggering_message_id`
  предпочтительнее: дешёвая, локальная, не требует трогать реестр
  Modulith.

  **Итоговая рекомендация архитектора**: миграция V23 нужна, минимальный
  объём — `ALTER TABLE execution_instances ADD COLUMN triggering_message_id
  BIGINT NULL` + (опционально) индекс
  `(sequence_id, aircraft_id, flight_number, triggering_message_id)` если
  частотность проверки оправдывает индекс (решает db-dev по факту нагрузки,
  не обязательное требование этого ADR). НЕ создавать отдельную таблицу
  `processed_events`/generic dedup registry — это была бы абстракция "на
  будущее" сверх того, что нужно для решения конкретного пробела (запрет
  CLAUDE.md: "минимум абстракций на будущее").

- **`checkStopCriterionTransactional`, `tryResumeWaitingInstanceTransactional`**:
  уже идемпотентны (P1-6), миграция не нужна, дополнительно — только тест-
  доказательство (см. ниже), не код.

- **`NotificationEventListener.onStepNotification`**: миграция не нужна.
  Естественная идемпотентность здесь невозможна без состояния (уведомление
  — это не переход состояния, а факт "сказать оператору"), НО цена дубликата
  сейчас равна нулю (заглушка-лог) — поэтому дедуп здесь откладывается
  до момента реализации реального канала уведомлений (WebSocket/прочее,
  отдельная задача, не P1-7). Если к моменту реализации реального канала
  потребуется дедуп — тогда уместен дешёвый вариант: естественный ключ
  `(executionId, stepIndex, result)` уже есть в самом событии и достаточен
  для дедупа на стороне будущего реального адаптера (например через
  `INSERT ... ON CONFLICT DO NOTHING` в таблицу отправленных уведомлений) —
  явно зафиксировать как TODO в коде с ссылкой на этот ADR, не реализовывать
  сейчас.

### 3. Какие listener'ы сделать идемпотентными и как (сводка для реализации)

| Listener | Метод | Идемпотентность | Действие в части 2 |
|---|---|---|---|
| `ExecutionService` | `processEvent` → `checkStartCriteria`/`startExecution` | Нет | Добавить проверку "инстанс с таким `(sequenceId, aircraftId, flightNumber, triggeringMessageId)` уже существует → skip" перед `INSERT`; добавить `triggeringMessageId` на `ExecutionInstance`/миграция V23 |
| `ExecutionService` | `checkStopCriterionTransactional` | Да (P1-6) | Без изменений кода; добавить тест-доказательство повторной доставки |
| `ExecutionService` | `tryResumeWaitingInstanceTransactional` | Да (P1-6) | Без изменений кода; добавить тест-доказательство |
| `ExecutionResumeRunner`/`resumeRunningInstanceAfterRestart` | — (не listener, scheduled/startup runner) | Частично — идемпотентен для EVALUATE/WAIT, НЕ идемпотентен для ACTION с реальным внешним эффектом (см. javadoc в коде, P1-4) | Вне объёма (нет реального внешнего эффекта пока — `LogMessageAdapter`); зафиксировать как известный риск, переоценить при замене заглушки на реальный ACARS-адаптер |
| `NotificationEventListener` | `onStepNotification` | Нет, цена дубликата = 0 сейчас | Не менять сейчас; TODO с ссылкой на ADR-0002 |

### 4. Тесты-доказательства (обязательны для PASS ревью)

1. **Republish on restart переигрывает незавершённую публикацию.**
   Интеграционный тест: вставить запись в `event_publication` с
   `completion_date IS NULL` и валидным `serialized_event` для
   `NormalizedEvent`, поднять контекст приложения (или вызвать механизм
   повтора Modulith, который срабатывает on startup), убедиться, что
   соответствующий listener вызван. Подтверждает, что флаг из п.1 реально
   включён и работает, а не просто присутствует в YAML.
2. **Повторная доставка `NormalizedEvent`, ведущая к старту, не создаёт
   дублирующийся `ExecutionInstance`.** Unit/integration тест:
   подготовить активную `Sequence` без start-критерия (или с ним),
   опубликовать/вызвать `processEvent` ОДИНАКОВЫМ `NormalizedEvent` (тот же
   `messageId`) дважды подряд (эмулируя повтор после рестарта/retry) →
   убедиться, что создан РОВНО ОДИН `ExecutionInstance` с данным
   `triggeringMessageId`. Это ключевой тест, доказывающий закрытие главного
   пробела этого ADR.
3. **Повторная доставка `NormalizedEvent` на WAITING/RUNNING инстанс не
   производит двойной переход.** Вызвать `checkStopCriterionTransactional`/
   `tryResumeWaitingInstanceTransactional` дважды с одним и тем же событием
   на один и тот же инстанс → убедиться, что второй вызов no-op
   (инстанс уже не в подходящем статусе) — формализует существующее
   поведение P1-6 явным тестом-регрессией (а не только комментарием).
4. **Атомарность записи в Outbox.** (Если такого теста ещё нет — проверить
   в части 2.) Транзакция, которая делает `save()` доменного состояния и
   `publishEvent`, при искусственном откате транзакции НЕ должна оставить
   запись в `event_publication` — подтверждает, что Outbox-запись и
   бизнес-изменение в одной транзакции, не в двух разных.

### 5. Что НЕ входит в объём части 2 (явно отложено)

- Идемпотентность входного ACARS REST-приёма (`POST
  /api/v1/messages/incoming`) — отдельная задача, другая граница системы
  (внешний мир → `eventprocessor`, не межмодульный Outbox).
- Дедуп реального `MessageOutputPort` (`sendUplink`/`sendGround`) — пока
  `LogMessageAdapter` заглушка, материальной цены дублирования нет;
  переоценить при реализации реального ACARS-адаптера.
- TTL/очистка `event_publication` (управление ростом таблицы) —
  эксплуатационная задача, не блокирует Accepted этого ADR, но должна быть
  заведена отдельным тикетом до прод-нагрузки.
- Generic `processed_events`/dedup-таблица "на будущее" — отклонено как
  избыточная абстракция (см. вывод в п.2).

## Alternatives considered

| Альтернатива | Почему не выбрана |
|---|---|
| **Прямой синхронный вызов порта/сервиса другого модуля вместо события** (например `execution` напрямую вызывает метод `IntegrationService`/`SequenceService` без `ApplicationEventPublisher`) | Теряет атомарность "изменение состояния + факт уведомления" — если вызов происходит ПОСЛЕ `save()` в той же транзакции, крах между ними теряет уведомление безвозвратно; если ДО `save()` — нет гарантии, что состояние вообще будет зафиксировано. Жёстко связывает модули по доступности (сбой/задержка `integration` блокирует `execution`) и по версии контракта (любое изменение сигнатуры — кросс-модульный breaking change без промежуточного слоя событий). Не даёт durable-повтора при сбое потребителя без отдельного руками написанного retry-механизма — то, что Outbox даёт "из коробки". Уже частично используется (см. Decision п.2) ТОЛЬКО там, где синхронный ответ обязателен по бизнес-логике (ACTION-шаг) — не как общий паттерн межмодульного взаимодействия. |
| **Полноценный event bus / брокер сообщений (Kafka, RabbitMQ) вместо Modulith Event Publication Registry** | Избыточно для масштаба внедрения (см. ADR-0001 — единая БД, единый процесс, десятки-сотни сообщений/сек). Добавляет аттестуемый компонент инфраструктуры (противоречит CLAUDE.md — импортозамещение, закрытый контур, минимум сетевых периметров) и тяжёлую зависимость без обоснования сверх того, что уже даёт `event_publication` в той же PostgreSQL. Нарушило бы правило CLAUDE.md "никаких новых тяжёлых зависимостей без ADR" — здесь обоснования для именно брокера нет, выгода не покрывает цену при текущем масштабе. |
| **Generic dedup-таблица `processed_events` (event_id, listener_id, processed_at) для ВСЕХ listener'ов сразу** | Отклонено для текущего объёма: естественная идемпотентность через проверку состояния (уже применённый в P1-6 паттерн) дешевле и достаточна для двух из трёх listener'ов без изменений схемы; для третьего (`startExecution`) достаточно одной целевой колонки, не общего реестра. Общая dedup-таблица — абстракция "на будущее" сверх конкретной потребности (запрещено CLAUDE.md). Может быть пересмотрено, если появится четвёртый+ listener с тем же паттерном "нет естественного состояния для проверки" — тогда выделение общего механизма станет оправданным рефакторингом, не преждевременным. |
| **Оставить `republish-outstanding-events-on-restart` выключенным, компенсировать через `ExecutionResumeRunner`-подобный ручной resume для каждого потока событий** | Технически работает для `execution` (уже есть `ExecutionResumeRunner` для RUNNING-инстансов, P1-4), но не закрывает общий случай для ЛЮБОГО будущего listener'а в любом модуле — каждый новый потребитель событий заново изобретал бы свой resume-механизм вместо использования встроенной возможности Spring Modulith, за которую уже заплачена интеграция (`spring-modulith-starter-jpa` в pom.xml с V10). Включение флага — это использование уже оплаченной инфраструктуры, не новая сложность. |

## References

- `CLAUDE.md` — Transactional Outbox vs event bus (раздел "Ключевые
  архитектурные инварианты"), правило P1-7 в RUN_PLAN.md.
- `docs/adr/ADR-0001-modular-monolith-vs-microservices.md` — модульный
  монолит, Outbox как enabler будущего вычленения сервисов.
- `backend/src/main/resources/db/migration/V10__create_event_publication_table.sql`
  — таблица Spring Modulith Event Publication Registry.
- `backend/src/main/resources/application.yml` — текущая конфигурация
  `spring.modulith.events` (republish-флаг отсутствует на момент написания).
- `backend/src/main/java/ru/protectinfotrans/eca/execution/application/ExecutionService.java`
  — `processEvent`, `checkStartCriteria`/`startExecution` (пробел
  идемпотентности), `checkStopCriterionTransactional`,
  `tryResumeWaitingInstanceTransactional` (естественная идемпотентность,
  P1-6), `resumeRunningInstanceAfterRestart` (javadoc P1-4, прямая ссылка на
  этот ADR как "P1-7").
- `backend/src/main/java/ru/protectinfotrans/eca/integration/application/NotificationEventListener.java`
  — третий `@ApplicationModuleListener`, не идемпотентен, цена дубликата
  сейчас нулевая (заглушка).
- `backend/src/main/java/ru/protectinfotrans/eca/execution/rules/ActionStepRule.java`,
  `backend/src/main/java/ru/protectinfotrans/eca/integration/adapter/out/LogMessageAdapter.java`
  — прямой синхронный путь (Decision п.2, исключение из общего правила),
  заглушка без реального внешнего эффекта.
- `backend/src/test/java/ru/protectinfotrans/eca/ModularityTests.java` —
  `ApplicationModules.verify()`, должен остаться зелёным.
- `RUN_PLAN.md`, задача P1-7.
