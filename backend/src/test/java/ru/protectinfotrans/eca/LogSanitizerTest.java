package ru.protectinfotrans.eca;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Фаза 3 (закрытие backlog P4-4 / CRLF_INJECTION_LOGS): unit-тесты {@link LogSanitizer}.
 */
@DisplayName("LogSanitizer — устранение CRLF/управляющих символов из логируемого ввода")
class LogSanitizerTest {

    @Test
    @DisplayName("CR/LF заменяются на '_' — внедрение фальшивых строк лога невозможно")
    void stripsCrlf() {
        String forged = "admin\r\n2026-01-01 INFO fake log line injected";

        String safe = LogSanitizer.sanitize(forged);

        assertThat(safe).doesNotContain("\r").doesNotContain("\n");
        assertThat(safe).isEqualTo("admin__2026-01-01 INFO fake log line injected");
    }

    @Test
    @DisplayName("TAB и прочие C0-управляющие символы заменяются, пробел сохраняется")
    void stripsTabAndOtherControls() {
        assertThat(LogSanitizer.sanitize("a\tb c")).isEqualTo("a_b c");
    }

    @Test
    @DisplayName("обычная строка без управляющих символов возвращается без изменений (тот же объект)")
    void leavesCleanStringUntouched() {
        String clean = "VP-BQR/SU1234";

        String result = LogSanitizer.sanitize(clean);

        assertThat(result).isSameAs(clean);
    }

    @Test
    @DisplayName("null возвращается как null (безопасно — SLF4J логирует как \"null\")")
    void nullReturnsNull() {
        assertThat(LogSanitizer.sanitize(null)).isNull();
    }

    @Test
    @DisplayName("пустая строка возвращается пустой")
    void emptyStringReturnsEmpty() {
        assertThat(LogSanitizer.sanitize("")).isEmpty();
    }

    @Test
    @DisplayName("C1-управляющий символ (0x7F) заменяется, печатный Unicode (кириллица) сохраняется")
    void stripsC1ControlsButKeepsPrintableUnicode() {
        String withDel = "рейс" + (char) 0x7F + "тест";

        assertThat(LogSanitizer.sanitize(withDel)).isEqualTo("рейс_тест");
        assertThat(LogSanitizer.sanitize("Борт VP-BQR")).isEqualTo("Борт VP-BQR");
    }
}
