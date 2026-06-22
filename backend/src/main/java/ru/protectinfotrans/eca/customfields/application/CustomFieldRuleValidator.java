package ru.protectinfotrans.eca.customfields.application;

import org.springframework.stereotype.Component;
import ru.protectinfotrans.eca.customfields.domain.ExtractionSource;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Валидация инварианта {@code extractionSource}/{@code pattern}: {@code CONTENT} требует валидный
 * regex с РОВНО ОДНОЙ capturing-группой (иначе "какое значение извлекать" неоднозначно —
 * {@code group(1)} либо не существует, либо является не той частью, которую автор правила имел в
 * виду); {@code METADATA} требует непустое имя ключа (свободная строка, не regex).
 */
@Component
public class CustomFieldRuleValidator {

    public void validatePattern(ExtractionSource extractionSource, String pattern) {
        if (extractionSource == ExtractionSource.METADATA) {
            if (pattern == null || pattern.isBlank()) {
                throw new IllegalArgumentException("METADATA extraction requires a non-blank metadata key as pattern");
            }
            return;
        }

        // CONTENT — regex с ровно одной capturing-группой
        Pattern compiled;
        try {
            compiled = Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("CONTENT extraction pattern is not a valid regex: " + e.getMessage(), e);
        }

        if (compiled.matcher("").groupCount() != 1) {
            throw new IllegalArgumentException(
                    "CONTENT extraction pattern must have exactly one capturing group, found "
                            + compiled.matcher("").groupCount());
        }
    }
}
