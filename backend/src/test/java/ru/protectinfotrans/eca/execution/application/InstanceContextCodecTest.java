package ru.protectinfotrans.eca.execution.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.protectinfotrans.eca.execution.domain.InstanceContext;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-тесты для {@link InstanceContextCodec} — сериализация/десериализация персистентного
 * контекста инстанса ({@code ExecutionInstance#contextJson}, P1-3 часть 2).
 */
@DisplayName("InstanceContextCodec")
class InstanceContextCodecTest {

    private InstanceContextCodec codec;

    @BeforeEach
    void setUp() {
        codec = new InstanceContextCodec(new ObjectMapper());
    }

    @Test
    @DisplayName("decode(null) и decode(\"\") возвращают пустой контекст без падения")
    void decodeNullOrBlank_returnsEmptyContext() {
        assertThat(codec.decode(null).getFromThisPointReference(1)).isNull();
        assertThat(codec.decode("").getFromThisPointReference(1)).isNull();
        assertThat(codec.decode("   ").getFromThisPointReference(1)).isNull();
    }

    @Test
    @DisplayName("decode(\"{}\") (исторический статичный контекст) даёт пустой контекст")
    void decodeEmptyObject_returnsEmptyContext() {
        InstanceContext context = codec.decode("{}");
        assertThat(context.getFromThisPointReference(1)).isNull();
    }

    @Test
    @DisplayName("decode повреждённого JSON не падает, возвращает пустой контекст")
    void decodeMalformedJson_returnsEmptyContextWithoutThrowing() {
        InstanceContext context = codec.decode("{not-valid-json");
        assertThat(context.getFromThisPointReference(1)).isNull();
    }

    @Test
    @DisplayName("encode/decode round-trip сохраняет точку отсчёта from-this-point-only по индексу шага")
    void encodeDecodeRoundTrip_preservesFromThisPointReference() {
        LocalDateTime reference = LocalDateTime.now().withNano(0);
        InstanceContext context = InstanceContext.empty().withFromThisPointReference(2, reference);

        String json = codec.encode(context);
        assertThat(json).isNotBlank().contains("fromThisPointReferences");

        InstanceContext decoded = codec.decode(json);
        assertThat(decoded.getFromThisPointReference(2)).isEqualTo(reference);
        // другой индекс шага в контексте не присутствует
        assertThat(decoded.getFromThisPointReference(1)).isNull();
    }

    @Test
    @DisplayName("несколько точек отсчёта для разных шагов сохраняются независимо")
    void multipleStepReferences_areIndependentlyPreserved() {
        LocalDateTime ref1 = LocalDateTime.now().minusMinutes(10).withNano(0);
        LocalDateTime ref3 = LocalDateTime.now().minusMinutes(2).withNano(0);

        InstanceContext context = InstanceContext.empty()
                .withFromThisPointReference(1, ref1)
                .withFromThisPointReference(3, ref3);

        InstanceContext decoded = codec.decode(codec.encode(context));

        assertThat(decoded.getFromThisPointReference(1)).isEqualTo(ref1);
        assertThat(decoded.getFromThisPointReference(3)).isEqualTo(ref3);
        assertThat(decoded.getFromThisPointReference(2)).isNull();
    }

    @Test
    @DisplayName("withFromThisPointReference не мутирует исходный объект (immutable wrapper)")
    void withFromThisPointReference_doesNotMutateOriginal() {
        InstanceContext original = InstanceContext.empty();
        LocalDateTime reference = LocalDateTime.now();

        InstanceContext updated = original.withFromThisPointReference(5, reference);

        assertThat(original.getFromThisPointReference(5)).isNull();
        assertThat(updated.getFromThisPointReference(5)).isEqualTo(reference);
    }

    @Test
    @DisplayName("повторная запись по тому же индексу шага перезаписывает старую точку отсчёта")
    void withFromThisPointReference_overwritesPreviousValueForSameStep() {
        LocalDateTime first = LocalDateTime.now().minusHours(1);
        LocalDateTime second = LocalDateTime.now();

        InstanceContext context = InstanceContext.empty()
                .withFromThisPointReference(1, first)
                .withFromThisPointReference(1, second);

        assertThat(context.getFromThisPointReference(1)).isEqualTo(second);
    }
}
