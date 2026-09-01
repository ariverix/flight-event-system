/**
 * Локальный словарь строк (RU/EN) для P7-2.
 *
 * P7-5 заменит эту реализацию на react-i18next (или аналог):
 *  - `useEditorI18n()` станет оберткой над `useTranslation('editor')`
 *  - словари переедут в `public/locales/{lang}/editor.json`
 *  - типы будут сгенерированы из JSON-файлов
 *
 * До P7-5: импортируйте `useEditorI18n` и не хардкодьте строки напрямую.
 */

// Не используем `as const` — значения остаются string (не литеральные типы),
// что позволяет обеим локалям удовлетворять типу EditorI18n без ошибок.
const EDITOR_RU = {
  title: 'Редактор последовательности',
  save: 'Сохранить',
  saving: 'Сохранение…',
  saved: 'Сохранено',
  back: 'К списку',
  openEditor: 'Открыть в редакторе',
  unsavedBadge: 'Есть несохранённые изменения',

  startCriteria: 'Критерии запуска',
  stopCriteria: 'Критерии остановки',
  criteriaNotSet: 'Не заданы',
  editCriteria: 'Изменить',
  criteriaHint: 'Непрерывная оценка критериев старта/остановки последовательности',
  criteriaP73Note: 'Конструктор критериев — P7-3 (пока временный JSON-редактор)',

  steps: 'Шаги',
  addStep: 'Добавить шаг',
  noSteps: 'Шагов ещё нет — добавьте первый',
  dragHint: 'Перетащите для изменения порядка (GOTO пересчитается)',
  gotoRecalculated: 'GOTO-ссылки пересчитаны',
  editStep: 'Изменить',
  deleteStep: 'Удалить',
  deleteConfirm: 'Удалить этот шаг?',
  deleteWarning: 'GOTO-ссылки на этот шаг будут сброшены',
  stepLabel: 'Шаг',

  onSuccess: 'При успехе',
  onFailure: 'При ошибке',
  notifyLabel: '+ Уведомление',
  gotoPrefix: '→ Шаг',

  selectedStep: 'Выбранный шаг',
  clickNodeHint: 'Нажмите на узел графа для просмотра деталей',
  configJson: 'Конфиг (JSON)',

  loadError: 'Ошибка загрузки последовательности',
  saveError: 'Ошибка сохранения',
  saveSuccess: 'Сохранено успешно',

  stepTypeAction: 'Действие',
  stepTypeEvaluate: 'Оценка',
  stepTypeWait: 'Ожидание',

  decisionContinue: 'CONTINUE',
  decisionGoto: 'GOTO',
  decisionEnd: 'END',
  decisionAbort: 'ABORT',

  stepUpdated: 'Шаг обновлён',
  stepAdded: 'Шаг добавлен',
  stepSaveError: 'Ошибка сохранения шага',
  stepDeleted: 'Шаг удалён',
  editStepTitle: 'Редактировать шаг',
  addStepTitle: 'Добавить шаг',

  startStopTitle: 'Старт / Стоп',
  cancel: 'Отмена',
  criteriaSetFallback: '(задан)',

  confirmYes: 'Да',
  confirmNo: 'Нет',

  invalidJson: '(некорректный JSON)',
  notifyTooltip: 'Уведомить',
  centerGraph: 'Центрировать граф',
  autoLayout: 'Авто-расстановка',
  fullscreen: 'Полный экран',

  // ── P7-3: Форма шага ────────────────────────────────────────────────────────

  stepTypePick: 'Выберите тип шага',
  actionTypeLabel: 'Тип действия',
  actionTypePick: 'Выберите тип действия',
  conditionNameLabel: 'Имя условия',
  conditionNamePlaceholder: 'Например: WEATHER_ALERT',
  alertLevelLabel: 'Уровень алерта',
  alertLevelPick: 'Выберите уровень',
  templateLabel: 'Шаблон сообщения',
  templatePick: 'Выберите шаблон…',
  templateLoading: 'Загрузка шаблонов…',
  uplinkOriginLabel: 'Источник сообщения',
  originComputerGenerated: 'Компьютер (auto)',
  originExternalUser: 'Оператор (manual)',
  originTagAuto: 'АВТО',
  originTagUser: 'ПОЛЬЗ.',
  recipientsLabel: 'Получатели',
  recipientsPlaceholder: 'Введите получателя…',
  recipientsHelp: 'Enter — добавить. Удалить — крестик.',
  durationLabel: 'Длительность',
  durationUnitLabel: 'Единица',
  durationUnitSec: 'секунды',
  durationUnitMin: 'минуты',
  durationUnitHour: 'часы',
  timeoutSecondsLabel: 'Тайм-аут (сек, 0 — без лимита)',
  fromThisPointOnly: 'Только с этой точки (from this point only)',
  transitionsTitle: 'Переходы',
  decisionActionLabel: 'Действие',
  decisionGotoStepLabel: 'Номер шага',
  notifyOnSuccess: 'Уведомить при успехе',
  notifyOnFailure: 'Уведомить при ошибке',
  onSuccessTitle: 'При успехе (true)',
  onFailureTitle: 'При ошибке / false',
  submitAddStep: 'Добавить шаг',
  submitSaveStep: 'Сохранить шаг',

  // ── P7-3: Уровни алертов ────────────────────────────────────────────────────

  alertLevels: {
    NO:       'Нет',
    LOW:      'Низкий',
    MEDIUM:   'Средний',
    HIGH:     'Высокий',
    CRITICAL: 'Критический',
  } as Record<string, string>,

  // ── P7-3: Конструктор критериев ─────────────────────────────────────────────

  criteriaBuilderTitle: 'Конструктор критериев',
  groupTagLabel: 'ГРУППА',
  noCriteria: 'Критерий не задан',
  addCriterion: 'Добавить критерий',
  addGroup: 'Добавить группу AND/OR',
  removeCriterion: 'Убрать',
  criterionTypeLabel: 'Тип критерия',
  criterionTypePick: 'Выберите тип…',
  logicLabel: 'Логика объединения',
  logicAnd: 'AND (все условия)',
  logicOr: 'OR (хотя бы одно)',

  // Критерий: MESSAGE_RECEIVED
  msgDirectionLabel: 'Направление',
  msgDirectionPick: 'Выберите направление',
  msgTemplateNameLabel: 'Шаблон (необязательно)',
  msgTemplateNamePh: 'Оставьте пустым — любой шаблон',
  msgFromThisPointLabel: 'Только с этой точки',

  // Критерий: FLIGHT_STAGE
  stageOperatorLabel: 'Оператор',
  targetStageLabel: 'Фаза полёта',

  stageOperators: {
    EQUALS:           '= равно',
    NOT_EQUALS:       '≠ не равно',
    GREATER_THAN:     '> больше',
    LESS_THAN:        '< меньше',
    GREATER_OR_EQUAL: '≥ больше или равно',
    LESS_OR_EQUAL:    '≤ меньше или равно',
  } as Record<string, string>,

  flightStages: {
    INIT:    'INIT (инициализация)',
    OUT:     'OUT (руление от ворот)',
    OFF:     'OFF (взлёт)',
    ON:      'ON (посадка)',
    IN:      'IN (заруливание)',
    SUMMARY: 'SUMMARY (итог)',
  } as Record<string, string>,

  // Критерий: POSITION_REPORTED
  posStatusLabel: 'Статус позиции',
  posReported: 'Получена',
  posNotReported: 'Не получена',
  posInLastMinLabel: 'За последние (мин)',
  posInLastMinPh: 'Например: 30',
  posSourcesLabel: 'Источники позиции',

  positionSources: {
    ACARS: 'ACARS',
    RADAR: 'Радар',
    ADS_B: 'ADS-B',
  } as Record<string, string>,

  // Критерий: TIME_COMPARISON
  timeOperatorLabel: 'Сравнение',
  timeRefLabel: 'Опорное время',
  timeOffsetLabel: 'Смещение (±мин)',

  timeOperators: {
    BEFORE: 'До (before)',
    EQUAL:  'Равно (equal)',
    AFTER:  'После (after)',
  } as Record<string, string>,

  timeReferences: {
    ETD:  'ETD (вылет)',
    ETA:  'ETA (прилёт)',
    INIT: 'INIT',
    OUT:  'OUT',
    OFF:  'OFF',
    ON:   'ON',
    IN:   'IN',
  } as Record<string, string>,

  // Направления сообщений
  msgDirections: {
    DOWNLINK: 'DOWNLINK (борт → земля)',
    UPLINK:   'UPLINK (земля → борт)',
    GROUND:   'GROUND (наземная)',
  } as Record<string, string>,

  // ── P7-3: Ошибки валидации ──────────────────────────────────────────────────

  validationErrors: {
    errActionType:       'Выберите тип действия',
    errCriterionType:    'Выберите тип критерия',
    errConditionName:    'Введите имя условия',
    errAlertLevel:       'Выберите уровень алерта',
    errTemplate:         'Выберите шаблон',
    errOrigin:           'Выберите источник сообщения',
    errDuration:         'Введите длительность (> 0)',
    errDurationUnit:     'Выберите единицу измерения',
    errMessageDirection: 'Выберите направление сообщения',
    errFlightStage:      'Выберите фазу полёта',
    errFlightOperator:   'Выберите оператор сравнения',
    errGotoMissing:      'Укажите номер шага для GOTO',
    errGotoInvalid:      'Шаг с таким номером не существует',
    errEmptyGroup:       'Группа AND/OR не может быть пустой',
    errTimeReference:    'Выберите опорное время',
    errTimeOperator:     'Выберите оператор сравнения',
    errInLastMinutes:    'Введите количество минут (> 0)',
    errPositionStatus:   'Укажите статус позиции (получена / не получена)',
    errPositionSources:  'Указаны недопустимые источники позиции',
    errOffsetMinutes:    'Смещение должно быть числом',
    errTimeoutSeconds:   'Тайм-аут должен быть >= 0',
    errLogic:            'Выберите логику объединения (AND/OR)',
    errInvalidJson:      'Некорректный JSON',
    errStepType:         'Неизвестный тип шага',
    errTransitionAction: 'Неизвестное действие перехода',
  } as Record<string, string>,

  // ── P7-3: Свойства последовательности ───────────────────────────────────────

  seqPropertiesTitle: 'Свойства последовательности',
  seqPropertiesBtn: 'Свойства',
  seqStatusLabel: 'Статус',
  seqStatusDraft: 'Черновик',
  seqStatusActive: 'Активна',
  seqStatusInactive: 'Неактивна',
  seqFolderLabel: 'Папка',
  seqFolderNone: '(без папки)',
  seqFolderIdLabel: 'ID папки',
  seqFolderAssignBtn: 'Назначить папку',
  seqActivateBtn: 'Активировать',
  seqDeactivateBtn: 'Деактивировать',
  seqActivated: 'Последовательность активирована',
  seqDeactivated: 'Последовательность деактивирована',
  seqActivateError: 'Ошибка активации',
  seqDeactivateError: 'Ошибка деактивации',
  seqFolderAssigned: 'Папка назначена',
  seqFolderError: 'Ошибка назначения папки',

  // Метки типов конфигурации шага (полные — для SelectedStepPanel)
  configLabels: {
    WAIT_TIME:         'Пауза по времени',
    SEND_UPLINK:       'Отправка uplink',
    SEND_GROUND:       'Отправка ground',
    RAISE_CONDITION:   'Поднять алерт',
    CLOSE_CONDITION:   'Снять алерт',
    MESSAGE_RECEIVED:  'Получено сообщение',
    FLIGHT_STAGE:      'Фаза полёта',
    POSITION_REPORTED: 'Позиционный отчёт',
    TIME_COMPARISON:   'Сравнение времени',
    CONDITION_ACTIVE:  'Условие активно',
    COMPOUND:          'Составное условие',
  } as Record<string, string>,

  // Краткие метки типов конфигурации (для EditorStepList)
  configLabelsShort: {
    WAIT_TIME:         'Пауза',
    SEND_UPLINK:       'Uplink',
    SEND_GROUND:       'Ground',
    RAISE_CONDITION:   '↑ Алерт',
    CLOSE_CONDITION:   '↓ Алерт',
    MESSAGE_RECEIVED:  'Сообщение',
    FLIGHT_STAGE:      'Фаза полёта',
    POSITION_REPORTED: 'Позиция',
    TIME_COMPARISON:   'Время',
    CONDITION_ACTIVE:  'Условие',
    COMPOUND:          'Составное',
  } as Record<string, string>,

  // Метки типов критериев (для StartStopPanel)
  criterionLabels: {
    MESSAGE_RECEIVED:  'Получено сообщение',
    FLIGHT_STAGE:      'Фаза полёта',
    POSITION_REPORTED: 'Позиция',
    TIME_COMPARISON:   'Время',
    CONDITION_ACTIVE:  'Условие активно',
    COMPOUND:          'Составное',
  } as Record<string, string>,

  // ── P7-4: Dashboard реал-тайм статусы ───────────────────────────────────────

  dashboardTitle:    'Реал-тайм мониторинг',
  dashboardSubtitle: 'Инстансы последовательностей',
  colSequence:  'Последовательность',
  colAircraft:  'Борт',
  colFlight:    'Рейс',
  colStep:      'Текущий шаг',
  colStatus:    'Статус',
  colStarted:   'Начало',
  colActions:   'Действия',
  detailsBtn:   'Детали',
  closeBtn:     'Закрыть',
  noInstances:  'Нет активных инстансов',
  refreshBtn:   'Обновить',

  eventLogTitle: 'Журнал событий',
  noEvents:      'Нет событий для этого инстанса',
  eventStep:     'Шаг',
  correlationId: 'Correlation ID',

  wsConnected:    'WS подключён',
  wsDisconnected: 'WS отключён',
  wsConnecting:   'WS подключение...',

  instanceStatuses: {
    RUNNING:   'Выполняется',
    WAITING:   'Ожидание',
    COMPLETED: 'Завершено',
    ABORTED:   'Прервано',
  } as Record<string, string>,

  eventTypes: {
    SEQUENCE_STARTED:  'Запуск последовательности',
    STEP_COMPLETED:    'Завершение шага',
    SEQUENCE_STOPPED:  'Остановка последовательности',
    SEQUENCE_ABORTED:  'Прерывание последовательности',
  } as Record<string, string>,

  // ── P7-5: AppLayout — навигация, шапка ──────────────────────────────────────

  sysName:            'СИСТЕМА ЕСА',
  sysTagline:         'Авиационная система мониторинга событий',
  sysOnline:          'Система онлайн · v1.0.0',

  navDashboard:       'Панель управления',
  navSequences:       'Последовательности',
  navExecutions:      'Выполнения',
  navMonitoring:      'Мониторинг',
  navMessages:        'Журнал сообщений',
  navTimeline:        'Хронология',
  navSimulator:       'Симулятор',
  navDemo:            'Демонстрация',
  navAuditLog:        'Журнал аудита',
  navUsers:           'Пользователи',

  themeLight:         'Светлая тема',
  themeDark:          'Тёмная тема',

  notifTitle:         'Активные выполнения',
  notifNActive:       'активных',
  notifEmpty:         'Нет активных выполнений',
  notifViewAll:       'Открыть все выполнения →',
  notifAircraftLabel: 'ВС',
  notifFlightLabel:   'Рейс',

  headerProfileBtn:   'Профиль пользователя',
  headerLogoutBtn:    'Выйти из системы',

  expandDetails:      'Развернуть детали',
  collapseDetails:    'Свернуть детали',

  // ── Фаза 6: выбор борта (aircraft-bindings) ─────────────────────────────────
  aircraftPickerLabel:       'Борт (tail number)',
  aircraftPickerPlaceholder: 'Выберите или найдите борт…',
  aircraftPickerSearching:   'Поиск бортов…',
  aircraftPickerEmpty:       'Борта не найдены',
  aircraftPickerError:       'Не удалось загрузить список бортов',
  aircraftLastSeen:          'последний контакт',
  aircraftFlights:           'рейсов',

  errorBoundaryTitle:    'Что-то пошло не так',
  errorBoundarySubtitle: 'Произошла непредвиденная ошибка. Попробуйте перезагрузить страницу.',
  errorBoundaryReload:   'Перезагрузить страницу',

  // ── LoginPage ────────────────────────────────────────────────────────────────
  loginAppTitle:          'СИСТЕМА ЕСА',
  loginAppSubtitle:       'Управление последовательностями событий ВС',
  loginUsernamePlaceholder: 'Имя пользователя',
  loginUsernameRequired:  'Введите имя пользователя',
  loginPasswordPlaceholder: 'Пароль',
  loginPasswordRequired:  'Введите пароль',
  loginSubmitBtn:         'Войти в систему',
  loginSuccessTitle:      'Вход выполнен',
  loginSuccessDesc:       'Добро пожаловать в Систему ЕСА!',
  loginErrorTitle:        'Ошибка входа',
  loginErrorDefault:      'Неверное имя пользователя или пароль',
  loginFooter:            '© 2026 Система ЕСА · Event Control Automation',

  // ── ProfilePage ──────────────────────────────────────────────────────────────
  profileLoadError:        'Ошибка загрузки профиля',
  profileCredentialsCard:  'Учётные данные',
  profileUsernameLabel:    'Имя пользователя',
  profileFullNameLabel:    'Полное имя',
  profileRoleLabel:        'Роль в системе',
  profileRegisteredLabel:  'Зарегистрирован',
  profileStatusActive:     'Активен',
  profileStatusDisabled:   'Отключён',

  // ── Общие: таблицы/пагинация (переиспользуется во всех списках) ──────────────
  paginationOf: 'из',

  // ── UserManagement ───────────────────────────────────────────────────────────
  usersPageTitle:       'Управление пользователями',
  usersAddBtn:          'Добавить пользователя',
  usersColId:            'ID',
  usersLoginLabel:       'Логин',
  usersColRole:          'Роль',
  usersColStatus:        'Статус',
  usersColActions:       'Активность',
  usersEmptyText:        'Пользователей нет',
  usersSwitchOn:         'Вкл',
  usersSwitchOff:        'Выкл',
  usersSelfToggleTooltip: 'Нельзя отключить собственную учётную запись',
  usersStatTotal:        'Всего пользователей',
  usersStatActive:       'Активных',
  usersStatDisabled:     'Отключённых',
  usersStatAdmins:       'Администраторов',
  usersModalTitle:       'Регистрация нового пользователя',
  usersLoginRequired:    'Введите логин',
  usersPasswordMinLength: 'Пароль должен содержать не менее 6 символов',
  usersFullNameRequired: 'Введите полное имя',
  usersRoleRequired:     'Выберите роль',
  usersCreateBtn:        'Создать пользователя',
  usersCancelBtn:        'Отмена',
  usersLoadError:        'Ошибка загрузки пользователей',
  usersToggleSuccess:    'Статус пользователя обновлён',
  usersToggleError:      'Ошибка обновления статуса',
  usersCreateSuccess:    'Пользователь создан успешно',
  usersCreateError:      'Ошибка создания пользователя',

  // ── NodeDetailPanel ──────────────────────────────────────────────────────────
  nodeEmptyTitleLine1: 'Нажмите на узел',
  nodeEmptyTitleLine2: 'для просмотра деталей',
  nodeEmptyHintLine1:  'Тип шага, конфигурация',
  nodeEmptyHintLine2:  'и состояние выполнения',
  nodeStepTypeLabel:   'Тип шага',
  nodeOrderLabel:      'Порядковый номер',
  nodeStepPrefix:      'Шаг',
  nodeClickOtherHint:  'Нажмите на другой узел для просмотра',

  nodeStates: {
    idle:      'Ожидание',
    active:    'Выполняется',
    success:   'Завершено',
    failure:   'Ошибка',
    unreached: 'Не достигнут',
  } as Record<string, string>,

  roles: {
    ADMIN:    'Администратор',
    OPERATOR: 'Оператор',
  } as Record<string, string>,
};

const EDITOR_EN = {
  title: 'Sequence Editor',
  save: 'Save',
  saving: 'Saving…',
  saved: 'Saved',
  back: 'Back to List',
  openEditor: 'Open in Editor',
  unsavedBadge: 'Unsaved changes',

  startCriteria: 'Start Criteria',
  stopCriteria: 'Stop Criteria',
  criteriaNotSet: 'Not defined',
  editCriteria: 'Edit',
  criteriaHint: 'Start/stop criteria are evaluated continuously against the sequence',
  criteriaP73Note: 'Criteria builder — P7-3 (temporary JSON editor for now)',

  steps: 'Steps',
  addStep: 'Add Step',
  noSteps: 'No steps yet — add the first one',
  dragHint: 'Drag to reorder (GOTO will recalculate)',
  gotoRecalculated: 'GOTO targets recalculated',
  editStep: 'Edit',
  deleteStep: 'Delete',
  deleteConfirm: 'Delete this step?',
  deleteWarning: 'GOTO references to this step will be cleared',
  stepLabel: 'Step',

  onSuccess: 'On success',
  onFailure: 'On failure',
  notifyLabel: '+ Notify',
  gotoPrefix: '→ Step',

  selectedStep: 'Selected Step',
  clickNodeHint: 'Click a graph node to view details',
  configJson: 'Config (JSON)',

  loadError: 'Failed to load sequence',
  saveError: 'Failed to save',
  saveSuccess: 'Saved successfully',

  stepTypeAction: 'Action',
  stepTypeEvaluate: 'Evaluate',
  stepTypeWait: 'Wait',

  decisionContinue: 'CONTINUE',
  decisionGoto: 'GOTO',
  decisionEnd: 'END',
  decisionAbort: 'ABORT',

  stepUpdated: 'Step updated',
  stepAdded: 'Step added',
  stepSaveError: 'Failed to save step',
  stepDeleted: 'Step deleted',
  editStepTitle: 'Edit Step',
  addStepTitle: 'Add Step',

  startStopTitle: 'Start / Stop',
  cancel: 'Cancel',
  criteriaSetFallback: '(set)',

  confirmYes: 'Yes',
  confirmNo: 'No',

  invalidJson: '(invalid JSON)',
  notifyTooltip: 'Notify',
  centerGraph: 'Center graph',
  autoLayout: 'Auto-layout',
  fullscreen: 'Fullscreen',

  // ── P7-3: Step form ─────────────────────────────────────────────────────────

  stepTypePick: 'Select step type',
  actionTypeLabel: 'Action type',
  actionTypePick: 'Select action type',
  conditionNameLabel: 'Condition name',
  conditionNamePlaceholder: 'e.g. WEATHER_ALERT',
  alertLevelLabel: 'Alert level',
  alertLevelPick: 'Select level',
  templateLabel: 'Message template',
  templatePick: 'Select template…',
  templateLoading: 'Loading templates…',
  uplinkOriginLabel: 'Message origin',
  originComputerGenerated: 'Computer-generated (auto)',
  originExternalUser: 'External user (manual)',
  originTagAuto: 'AUTO',
  originTagUser: 'USER',
  recipientsLabel: 'Recipients',
  recipientsPlaceholder: 'Enter recipient…',
  recipientsHelp: 'Press Enter to add. Click × to remove.',
  durationLabel: 'Duration',
  durationUnitLabel: 'Unit',
  durationUnitSec: 'seconds',
  durationUnitMin: 'minutes',
  durationUnitHour: 'hours',
  timeoutSecondsLabel: 'Timeout (sec, 0 = no limit)',
  fromThisPointOnly: 'From this point only',
  transitionsTitle: 'Transitions',
  decisionActionLabel: 'Action',
  decisionGotoStepLabel: 'Step number',
  notifyOnSuccess: 'Notify on success',
  notifyOnFailure: 'Notify on failure',
  onSuccessTitle: 'On success (true)',
  onFailureTitle: 'On failure / false',
  submitAddStep: 'Add step',
  submitSaveStep: 'Save step',

  // ── P7-3: Alert levels ──────────────────────────────────────────────────────

  alertLevels: {
    NO:       'No',
    LOW:      'Low',
    MEDIUM:   'Medium',
    HIGH:     'High',
    CRITICAL: 'Critical',
  } as Record<string, string>,

  // ── P7-3: Criteria builder ──────────────────────────────────────────────────

  criteriaBuilderTitle: 'Criteria builder',
  groupTagLabel: 'GROUP',
  noCriteria: 'No criterion defined',
  addCriterion: 'Add criterion',
  addGroup: 'Add AND/OR group',
  removeCriterion: 'Remove',
  criterionTypeLabel: 'Criterion type',
  criterionTypePick: 'Select type…',
  logicLabel: 'Combining logic',
  logicAnd: 'AND (all conditions)',
  logicOr: 'OR (any condition)',

  // Criterion: MESSAGE_RECEIVED
  msgDirectionLabel: 'Direction',
  msgDirectionPick: 'Select direction',
  msgTemplateNameLabel: 'Template name (optional)',
  msgTemplateNamePh: 'Leave empty for any template',
  msgFromThisPointLabel: 'From this point only',

  // Criterion: FLIGHT_STAGE
  stageOperatorLabel: 'Operator',
  targetStageLabel: 'Flight stage',

  stageOperators: {
    EQUALS:           '= equals',
    NOT_EQUALS:       '≠ not equals',
    GREATER_THAN:     '> greater than',
    LESS_THAN:        '< less than',
    GREATER_OR_EQUAL: '≥ greater or equal',
    LESS_OR_EQUAL:    '≤ less or equal',
  } as Record<string, string>,

  flightStages: {
    INIT:    'INIT (initial)',
    OUT:     'OUT (push-back)',
    OFF:     'OFF (take-off)',
    ON:      'ON (landing)',
    IN:      'IN (parking)',
    SUMMARY: 'SUMMARY',
  } as Record<string, string>,

  // Criterion: POSITION_REPORTED
  posStatusLabel: 'Position status',
  posReported: 'Reported',
  posNotReported: 'Not reported',
  posInLastMinLabel: 'In last N minutes',
  posInLastMinPh: 'e.g. 30',
  posSourcesLabel: 'Position sources',

  positionSources: {
    ACARS: 'ACARS',
    RADAR: 'Radar',
    ADS_B: 'ADS-B',
  } as Record<string, string>,

  // Criterion: TIME_COMPARISON
  timeOperatorLabel: 'Comparison',
  timeRefLabel: 'Reference time',
  timeOffsetLabel: 'Offset (±min)',

  timeOperators: {
    BEFORE: 'Before',
    EQUAL:  'Equal',
    AFTER:  'After',
  } as Record<string, string>,

  timeReferences: {
    ETD:  'ETD (departure)',
    ETA:  'ETA (arrival)',
    INIT: 'INIT',
    OUT:  'OUT',
    OFF:  'OFF',
    ON:   'ON',
    IN:   'IN',
  } as Record<string, string>,

  // Message directions
  msgDirections: {
    DOWNLINK: 'DOWNLINK (aircraft → ground)',
    UPLINK:   'UPLINK (ground → aircraft)',
    GROUND:   'GROUND (ground-to-ground)',
  } as Record<string, string>,

  // ── P7-3: Validation errors ─────────────────────────────────────────────────

  validationErrors: {
    errActionType:       'Select action type',
    errCriterionType:    'Select criterion type',
    errConditionName:    'Enter condition name',
    errAlertLevel:       'Select alert level',
    errTemplate:         'Select template',
    errOrigin:           'Select message origin',
    errDuration:         'Enter duration (> 0)',
    errDurationUnit:     'Select time unit',
    errMessageDirection: 'Select message direction',
    errFlightStage:      'Select flight stage',
    errFlightOperator:   'Select comparison operator',
    errGotoMissing:      'Enter GOTO target step number',
    errGotoInvalid:      'Step with this number does not exist',
    errEmptyGroup:       'AND/OR group cannot be empty',
    errTimeReference:    'Select reference time',
    errTimeOperator:     'Select comparison operator',
    errInLastMinutes:    'Enter minutes value (> 0)',
    errPositionStatus:   'Set position status (reported / not reported)',
    errPositionSources:  'Invalid position sources',
    errOffsetMinutes:    'Offset must be a number',
    errTimeoutSeconds:   'Timeout must be >= 0',
    errLogic:            'Select combining logic (AND/OR)',
    errInvalidJson:      'Invalid JSON',
    errStepType:         'Unknown step type',
    errTransitionAction: 'Unknown transition action',
  } as Record<string, string>,

  // ── P7-3: Sequence properties ────────────────────────────────────────────────

  seqPropertiesTitle: 'Sequence Properties',
  seqPropertiesBtn: 'Properties',
  seqStatusLabel: 'Status',
  seqStatusDraft: 'Draft',
  seqStatusActive: 'Active',
  seqStatusInactive: 'Inactive',
  seqFolderLabel: 'Folder',
  seqFolderNone: '(no folder)',
  seqFolderIdLabel: 'Folder ID',
  seqFolderAssignBtn: 'Assign folder',
  seqActivateBtn: 'Activate',
  seqDeactivateBtn: 'Deactivate',
  seqActivated: 'Sequence activated',
  seqDeactivated: 'Sequence deactivated',
  seqActivateError: 'Activation error',
  seqDeactivateError: 'Deactivation error',
  seqFolderAssigned: 'Folder assigned',
  seqFolderError: 'Folder assignment error',

  configLabels: {
    WAIT_TIME:         'Wait time',
    SEND_UPLINK:       'Send uplink',
    SEND_GROUND:       'Send ground',
    RAISE_CONDITION:   'Raise condition',
    CLOSE_CONDITION:   'Close condition',
    MESSAGE_RECEIVED:  'Message received',
    FLIGHT_STAGE:      'Flight stage',
    POSITION_REPORTED: 'Position reported',
    TIME_COMPARISON:   'Time comparison',
    CONDITION_ACTIVE:  'Condition active',
    COMPOUND:          'Compound',
  } as Record<string, string>,

  configLabelsShort: {
    WAIT_TIME:         'Pause',
    SEND_UPLINK:       'Uplink',
    SEND_GROUND:       'Ground',
    RAISE_CONDITION:   '↑ Alert',
    CLOSE_CONDITION:   '↓ Alert',
    MESSAGE_RECEIVED:  'Message',
    FLIGHT_STAGE:      'Flight stage',
    POSITION_REPORTED: 'Position',
    TIME_COMPARISON:   'Time',
    CONDITION_ACTIVE:  'Condition',
    COMPOUND:          'Compound',
  } as Record<string, string>,

  criterionLabels: {
    MESSAGE_RECEIVED:  'Message received',
    FLIGHT_STAGE:      'Flight stage',
    POSITION_REPORTED: 'Position',
    TIME_COMPARISON:   'Time',
    CONDITION_ACTIVE:  'Condition active',
    COMPOUND:          'Compound',
  } as Record<string, string>,

  // ── P7-4: Dashboard real-time statuses ──────────────────────────────────────

  dashboardTitle:    'Real-time Monitoring',
  dashboardSubtitle: 'Sequence Instances',
  colSequence:  'Sequence',
  colAircraft:  'Aircraft',
  colFlight:    'Flight',
  colStep:      'Current Step',
  colStatus:    'Status',
  colStarted:   'Started',
  colActions:   'Actions',
  detailsBtn:   'Details',
  closeBtn:     'Close',
  noInstances:  'No active instances',
  refreshBtn:   'Refresh',

  eventLogTitle: 'Event Log',
  noEvents:      'No events for this instance',
  eventStep:     'Step',
  correlationId: 'Correlation ID',

  wsConnected:    'WS connected',
  wsDisconnected: 'WS disconnected',
  wsConnecting:   'WS connecting...',

  instanceStatuses: {
    RUNNING:   'Running',
    WAITING:   'Waiting',
    COMPLETED: 'Completed',
    ABORTED:   'Aborted',
  } as Record<string, string>,

  eventTypes: {
    SEQUENCE_STARTED:  'Sequence started',
    STEP_COMPLETED:    'Step completed',
    SEQUENCE_STOPPED:  'Sequence stopped',
    SEQUENCE_ABORTED:  'Sequence aborted',
  } as Record<string, string>,

  // ── P7-5: AppLayout — navigation, header ────────────────────────────────────

  sysName:            'ECA SYSTEM',
  sysTagline:         'Aviation Event Monitoring System',
  sysOnline:          'System online · v1.0.0',

  navDashboard:       'Dashboard',
  navSequences:       'Sequences',
  navExecutions:      'Executions',
  navMonitoring:      'Monitoring',
  navMessages:        'Message Log',
  navTimeline:        'Timeline',
  navSimulator:       'Simulator',
  navDemo:            'Demo',
  navAuditLog:        'Audit Log',
  navUsers:           'Users',

  themeLight:         'Light theme',
  themeDark:          'Dark theme',

  notifTitle:         'Active Executions',
  notifNActive:       'active',
  notifEmpty:         'No active executions',
  notifViewAll:       'View all executions →',
  notifAircraftLabel: 'Aircraft',
  notifFlightLabel:   'Flight',

  headerProfileBtn:   'User Profile',
  headerLogoutBtn:    'Log out',

  expandDetails:      'Expand details',
  collapseDetails:    'Collapse details',

  // ── Фаза 6: aircraft-bindings picker ────────────────────────────────────────
  aircraftPickerLabel:       'Aircraft (tail number)',
  aircraftPickerPlaceholder: 'Select or search aircraft…',
  aircraftPickerSearching:   'Searching aircraft…',
  aircraftPickerEmpty:       'No aircraft found',
  aircraftPickerError:       'Failed to load aircraft list',
  aircraftLastSeen:          'last seen',
  aircraftFlights:           'flights',

  errorBoundaryTitle:    'Something went wrong',
  errorBoundarySubtitle: 'An unexpected error occurred. Try reloading the page.',
  errorBoundaryReload:   'Reload page',

  // ── LoginPage ────────────────────────────────────────────────────────────────
  loginAppTitle:          'ECA SYSTEM',
  loginAppSubtitle:       'Aircraft event sequence management',
  loginUsernamePlaceholder: 'Username',
  loginUsernameRequired:  'Enter your username',
  loginPasswordPlaceholder: 'Password',
  loginPasswordRequired:  'Enter your password',
  loginSubmitBtn:         'Sign in',
  loginSuccessTitle:      'Signed in',
  loginSuccessDesc:       'Welcome to ECA System!',
  loginErrorTitle:        'Sign-in failed',
  loginErrorDefault:      'Invalid username or password',
  loginFooter:            '© 2026 ECA System · Event Control Automation',

  // ── ProfilePage ──────────────────────────────────────────────────────────────
  profileLoadError:        'Failed to load profile',
  profileCredentialsCard:  'Credentials',
  profileUsernameLabel:    'Username',
  profileFullNameLabel:    'Full name',
  profileRoleLabel:        'System role',
  profileRegisteredLabel:  'Registered',
  profileStatusActive:     'Active',
  profileStatusDisabled:   'Disabled',

  // ── Общие: таблицы/пагинация (переиспользуется во всех списках) ──────────────
  paginationOf: 'of',

  // ── UserManagement ───────────────────────────────────────────────────────────
  usersPageTitle:       'User management',
  usersAddBtn:          'Add user',
  usersColId:            'ID',
  usersLoginLabel:       'Login',
  usersColRole:          'Role',
  usersColStatus:        'Status',
  usersColActions:       'Activity',
  usersEmptyText:        'No users',
  usersSwitchOn:         'On',
  usersSwitchOff:        'Off',
  usersSelfToggleTooltip: 'You cannot disable your own account',
  usersStatTotal:        'Total users',
  usersStatActive:       'Active',
  usersStatDisabled:     'Disabled',
  usersStatAdmins:       'Administrators',
  usersModalTitle:       'Register new user',
  usersLoginRequired:    'Enter login',
  usersPasswordMinLength: 'Password must be at least 6 characters',
  usersFullNameRequired: 'Enter full name',
  usersRoleRequired:     'Select a role',
  usersCreateBtn:        'Create user',
  usersCancelBtn:        'Cancel',
  usersLoadError:        'Failed to load users',
  usersToggleSuccess:    'User status updated',
  usersToggleError:      'Failed to update status',
  usersCreateSuccess:    'User created successfully',
  usersCreateError:      'Failed to create user',

  // ── NodeDetailPanel ──────────────────────────────────────────────────────────
  nodeEmptyTitleLine1: 'Click a node',
  nodeEmptyTitleLine2: 'to view details',
  nodeEmptyHintLine1:  'Step type, configuration',
  nodeEmptyHintLine2:  'and execution state',
  nodeStepTypeLabel:   'Step type',
  nodeOrderLabel:      'Order number',
  nodeStepPrefix:      'Step',
  nodeClickOtherHint:  'Click another node to view',

  nodeStates: {
    idle:      'Pending',
    active:    'Running',
    success:   'Completed',
    failure:   'Failed',
    unreached: 'Not reached',
  } as Record<string, string>,

  roles: {
    ADMIN:    'Administrator',
    OPERATOR: 'Operator',
  } as Record<string, string>,
};

export type Lang = 'ru' | 'en';
export type EditorI18n = typeof EDITOR_RU;

export const EDITOR_DICTS: Record<Lang, EditorI18n> = {
  ru: EDITOR_RU,
  en: EDITOR_EN,
};
