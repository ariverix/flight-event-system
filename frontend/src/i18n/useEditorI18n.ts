/**
 * Хук доступа к словарю редактора.
 *
 * Возвращает типизированный словарь для текущего языка.
 * P7-5 заменит тело хука на `useTranslation('editor')` из react-i18next.
 *
 * Использование:
 *   const d = useEditorI18n();
 *   <Button>{d.save}</Button>
 */
import { EDITOR_DICTS, type EditorI18n } from './dict';

/**
 * В P7-5 язык будет читаться из uiStore (с переключателем EN/RU).
 * Пока всегда возвращаем RU.
 */
export function useEditorI18n(): EditorI18n {
  return EDITOR_DICTS.ru;
}
