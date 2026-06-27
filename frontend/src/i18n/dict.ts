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
};

export type Lang = 'ru' | 'en';
export type EditorI18n = typeof EDITOR_RU;

export const EDITOR_DICTS: Record<Lang, EditorI18n> = {
  ru: EDITOR_RU,
  en: EDITOR_EN,
};
