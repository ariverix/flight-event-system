package ru.protectinfotrans.eca.templates.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.protectinfotrans.eca.templates.port.in.MissingTemplateVariableException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit-тесты движка подстановки переменных — детерминированность, отсутствующая переменная,
 * экранирование/single-pass, синтаксис {@code {{var}}}.
 */
@DisplayName("TemplateRenderer")
class TemplateRendererTest {

    private final TemplateRenderer renderer = new TemplateRenderer();

    @Test
    @DisplayName("подставляет одну переменную")
    void substitutesSingleVariable() {
        String result = renderer.render("Hello {{name}}!", Map.of("name", "VP-BQR"));
        assertThat(result).isEqualTo("Hello VP-BQR!");
    }

    @Test
    @DisplayName("подставляет несколько переменных")
    void substitutesMultipleVariables() {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("aircraft", "VP-BQR");
        vars.put("flight", "SU1234");

        String result = renderer.render("Aircraft {{aircraft}} flight {{flight}} request position", vars);

        assertThat(result).isEqualTo("Aircraft VP-BQR flight SU1234 request position");
    }

    @Test
    @DisplayName("одна и та же пара (body, variables) всегда даёт один и тот же результат — детерминированность")
    void isDeterministic() {
        String body = "ETA {{eta}} for flight {{flight}}";
        Map<String, Object> vars = Map.of("eta", "12:30", "flight", "SU1234");

        String first = renderer.render(body, vars);
        String second = renderer.render(body, vars);

        assertThat(first).isEqualTo(second);
    }

    @Test
    @DisplayName("текст без плейсхолдеров возвращается без изменений")
    void returnsBodyUnchangedWhenNoPlaceholders() {
        String result = renderer.render("Free text, no variables here", Map.of());
        assertThat(result).isEqualTo("Free text, no variables here");
    }

    @Test
    @DisplayName("null body -> null без исключения")
    void nullBodyReturnsNull() {
        assertThat(renderer.render(null, Map.of())).isNull();
    }

    @Test
    @DisplayName("null variables -> трактуется как пустая карта, плейсхолдеры без значений бросают исключение")
    void nullVariablesTreatedAsEmptyMap() {
        assertThatThrownBy(() -> renderer.render("Hello {{name}}", null))
                .isInstanceOf(MissingTemplateVariableException.class)
                .hasMessageContaining("name");
    }

    @Test
    @DisplayName("отсутствующая переменная бросает MissingTemplateVariableException (явный сбой, не тихая замена)")
    void missingVariableThrows() {
        assertThatThrownBy(() -> renderer.render("Hello {{name}}, ETA {{eta}}", Map.of("name", "Captain")))
                .isInstanceOf(MissingTemplateVariableException.class)
                .hasMessageContaining("eta");
    }

    @Test
    @DisplayName("значение переменной null подставляется как пустая строка, а не 'null'")
    void nullValueSubstitutedAsEmptyString() {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("comment", null);

        String result = renderer.render("Comment: {{comment}}.", vars);

        assertThat(result).isEqualTo("Comment: .");
    }

    @Test
    @DisplayName("значение переменной, само содержащее {{...}}, НЕ подставляется рекурсивно (single-pass)")
    void valueContainingPlaceholderSyntaxIsNotReRendered() {
        Map<String, Object> vars = Map.of(
                "injected", "{{name}}",
                "name", "should-not-appear"
        );

        String result = renderer.render("Value: {{injected}}", vars);

        assertThat(result).isEqualTo("Value: {{name}}");
    }

    @Test
    @DisplayName("числовое/булево значение подставляется через toString()")
    void nonStringValuesUseToString() {
        Map<String, Object> vars = Map.of("count", 42, "urgent", true);

        String result = renderer.render("Count={{count}} Urgent={{urgent}}", vars);

        assertThat(result).isEqualTo("Count=42 Urgent=true");
    }

    @Test
    @DisplayName("extractVariableNames возвращает все имена плейсхолдеров без дублей, в порядке появления")
    void extractsVariableNames() {
        Set<String> names = renderer.extractVariableNames("{{a}} {{b}} {{a}} {{c}}");
        assertThat(names).containsExactly("a", "b", "c");
    }

    @Test
    @DisplayName("extractVariableNames на теле без плейсхолдеров возвращает пустой набор")
    void extractsEmptySetWhenNoPlaceholders() {
        assertThat(renderer.extractVariableNames("no placeholders")).isEmpty();
    }

    @Test
    @DisplayName("extractVariableNames на null body возвращает пустой набор")
    void extractsEmptySetForNullBody() {
        assertThat(renderer.extractVariableNames(null)).isEmpty();
    }

    @Test
    @DisplayName("плейсхолдер с пробелами внутри скобок ({{ var }}) тоже распознаётся")
    void placeholderWithInnerWhitespaceIsRecognized() {
        String result = renderer.render("Value: {{ name }}", Map.of("name", "VP-BQR"));
        assertThat(result).isEqualTo("Value: VP-BQR");
    }

    @Test
    @DisplayName("${var} (Spring-стиль) НЕ распознаётся как плейсхолдер — синтаксис только {{var}}")
    void dollarBraceSyntaxIsNotTreatedAsPlaceholder() {
        String result = renderer.render("Value: ${name}", Map.of());
        assertThat(result).isEqualTo("Value: ${name}");
    }

    @Test
    @DisplayName("имя переменной с точкой (вложенная адресация, точка интеграции P3-2 custom fields) распознаётся")
    void dottedVariableNameIsRecognized() {
        String result = renderer.render("Delay: {{customField.ETA_DELAY}}",
                Map.of("customField.ETA_DELAY", "15min"));
        assertThat(result).isEqualTo("Delay: 15min");
    }
}
