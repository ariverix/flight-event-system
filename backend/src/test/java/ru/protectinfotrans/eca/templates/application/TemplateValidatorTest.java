package ru.protectinfotrans.eca.templates.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.protectinfotrans.eca.MessageType;
import ru.protectinfotrans.eca.sequence.domain.UplinkOrigin;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("TemplateValidator")
class TemplateValidatorTest {

    private final TemplateValidator validator = new TemplateValidator();

    @Test
    @DisplayName("DOWNLINK без origin -> валидно")
    void downlinkWithoutOriginIsValid() {
        assertThatCode(() -> validator.validateOriginConsistency(MessageType.DOWNLINK, null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("DOWNLINK с origin -> IllegalArgumentException")
    void downlinkWithOriginIsInvalid() {
        assertThatThrownBy(() ->
                validator.validateOriginConsistency(MessageType.DOWNLINK, UplinkOrigin.COMPUTER_GENERATED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DOWNLINK");
    }

    @Test
    @DisplayName("UPLINK с origin COMPUTER_GENERATED -> валидно")
    void uplinkWithComputerGeneratedOriginIsValid() {
        assertThatCode(() ->
                validator.validateOriginConsistency(MessageType.UPLINK, UplinkOrigin.COMPUTER_GENERATED))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("UPLINK с origin EXTERNAL_USER -> валидно")
    void uplinkWithExternalUserOriginIsValid() {
        assertThatCode(() ->
                validator.validateOriginConsistency(MessageType.UPLINK, UplinkOrigin.EXTERNAL_USER))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("UPLINK без origin -> IllegalArgumentException")
    void uplinkWithoutOriginIsInvalid() {
        assertThatThrownBy(() -> validator.validateOriginConsistency(MessageType.UPLINK, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UPLINK");
    }

    @Test
    @DisplayName("GROUND без origin -> IllegalArgumentException")
    void groundWithoutOriginIsInvalid() {
        assertThatThrownBy(() -> validator.validateOriginConsistency(MessageType.GROUND, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("GROUND");
    }

    @Test
    @DisplayName("GROUND с origin -> валидно")
    void groundWithOriginIsValid() {
        assertThatCode(() ->
                validator.validateOriginConsistency(MessageType.GROUND, UplinkOrigin.EXTERNAL_USER))
                .doesNotThrowAnyException();
    }
}
