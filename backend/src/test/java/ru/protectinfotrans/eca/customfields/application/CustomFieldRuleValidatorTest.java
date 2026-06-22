package ru.protectinfotrans.eca.customfields.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.protectinfotrans.eca.customfields.domain.ExtractionSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CustomFieldRuleValidator")
class CustomFieldRuleValidatorTest {

    private final CustomFieldRuleValidator validator = new CustomFieldRuleValidator();

    @Test
    @DisplayName("CONTENT: regex с ровно одной capturing-группой -> валидно")
    void contentWithExactlyOneGroupIsValid() {
        assertThatCode(() -> validator.validatePattern(ExtractionSource.CONTENT, "GATE=(\\w+)"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("CONTENT: regex без capturing-групп -> IllegalArgumentException")
    void contentWithoutGroupsIsInvalid() {
        assertThatThrownBy(() -> validator.validatePattern(ExtractionSource.CONTENT, "GATE=\\w+"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly one capturing group");
    }

    @Test
    @DisplayName("CONTENT: regex с двумя capturing-группами -> IllegalArgumentException")
    void contentWithTwoGroupsIsInvalid() {
        assertThatThrownBy(() -> validator.validatePattern(ExtractionSource.CONTENT, "(\\w+)=(\\w+)"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly one capturing group");
    }

    @Test
    @DisplayName("CONTENT: некорректный regex -> IllegalArgumentException")
    void contentWithInvalidRegexIsInvalid() {
        assertThatThrownBy(() -> validator.validatePattern(ExtractionSource.CONTENT, "GATE=(["))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a valid regex");
    }

    @Test
    @DisplayName("METADATA: непустой ключ -> валидно")
    void metadataWithNonBlankKeyIsValid() {
        assertThatCode(() -> validator.validatePattern(ExtractionSource.METADATA, "gateNumber"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("METADATA: пустой ключ -> IllegalArgumentException")
    void metadataWithBlankKeyIsInvalid() {
        assertThatThrownBy(() -> validator.validatePattern(ExtractionSource.METADATA, "   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-blank metadata key");
    }

    @Test
    @DisplayName("METADATA: null ключ -> IllegalArgumentException")
    void metadataWithNullKeyIsInvalid() {
        assertThatThrownBy(() -> validator.validatePattern(ExtractionSource.METADATA, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-blank metadata key");
    }
}
