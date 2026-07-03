package ru.protectinfotrans.eca;

/**
 * Фаза 3 (прогон апгрейда, закрытие backlog P4-4 / SpotBugs CRLF_INJECTION_LOGS): санитизация
 * пользовательского ввода перед записью в лог.
 *
 * <p><b>Угроза (log forging / CRLF-injection):</b> если во внешне-контролируемую строку
 * (username, tail number ВС, тип сообщения, свободный текст) подложить символы CR/LF, то при
 * прямом логировании {@code log.info("... {}", value)} злоумышленник может внедрить в журнал
 * фальшивые строки лога (подделать записи, скрыть следы, сломать парсеры/SIEM). SpotBugs/FindSecBugs
 * помечает такие места как {@code CRLF_INJECTION_LOGS}.
 *
 * <p><b>Мера:</b> заменяем управляющие символы (CR, LF, TAB и прочие C0/C1) на подчёркивание,
 * чтобы одно логируемое значение не могло стать несколькими строками журнала. Значение остаётся
 * читаемым для диагностики; полностью экранировать/усекать не требуется — цель именно устранить
 * перевод строки и другие управляющие последовательности.
 *
 * <p>Утилита в корневом (shared) пакете {@code ru.protectinfotrans.eca} — доступна всем модулям
 * (аналогично {@code CorrelationIdFilter}). Границы Modulith не нарушаются: чистая функция без
 * состояния и зависимостей.
 */
public final class LogSanitizer {

    private LogSanitizer() {
    }

    /** Плейсхолдер, которым заменяется каждый управляющий символ. */
    private static final char REPLACEMENT = '_';

    /**
     * Возвращает копию входной строки, в которой каждый управляющий символ (CR/LF/TAB и прочие
     * C0/C1: код &lt; 0x20 или в диапазоне 0x7F..0x9F) заменён на {@code '_'}. {@code null}
     * возвращается как есть (SLF4J логирует его как {@code "null"} — безопасно).
     *
     * @param value внешне-контролируемое значение для логирования
     * @return безопасная для одной строки лога версия значения, либо {@code null}
     */
    public static String sanitize(String value) {
        if (value == null) {
            return null;
        }
        StringBuilder sb = null;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean control = c < 0x20 || (c >= 0x7F && c <= 0x9F);
            if (control) {
                if (sb == null) {
                    sb = new StringBuilder(value.length());
                    sb.append(value, 0, i);
                }
                sb.append(REPLACEMENT);
            } else if (sb != null) {
                sb.append(c);
            }
        }
        return sb == null ? value : sb.toString();
    }
}
