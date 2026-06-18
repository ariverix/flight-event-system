package ru.protectinfotrans.eca.eventprocessor.adapter.out;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import ru.protectinfotrans.eca.MessageType;
import ru.protectinfotrans.eca.eventprocessor.domain.IncomingMessage;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-тесты для MessageJpaAdapter.
 * Проверяет делегирование к MessageJpaRepository и ветвление по фильтрам/наличию afterTime.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MessageJpaAdapter")
class MessageJpaAdapterTest {

    @Mock
    private MessageJpaRepository jpaRepository;

    @InjectMocks
    private MessageJpaAdapter adapter;

    @Test
    @DisplayName("save: должен делегировать сохранение в репозиторий")
    void shouldSaveMessage() {
        var message = IncomingMessage.builder().aircraftId("RA-1234").build();
        when(jpaRepository.save(message)).thenReturn(message);

        IncomingMessage result = adapter.save(message);

        assertThat(result).isEqualTo(message);
        verify(jpaRepository).save(message);
    }

    @Test
    @DisplayName("findById: должен делегировать поиск по id")
    void shouldFindById() {
        var message = IncomingMessage.builder().id(5L).build();
        when(jpaRepository.findById(5L)).thenReturn(Optional.of(message));

        Optional<IncomingMessage> result = adapter.findById(5L);

        assertThat(result).contains(message);
    }

    @Test
    @DisplayName("findById: должен вернуть Optional.empty если не найдено")
    void shouldReturnEmptyWhenNotFound() {
        when(jpaRepository.findById(999L)).thenReturn(Optional.empty());

        Optional<IncomingMessage> result = adapter.findById(999L);

        assertThat(result).isEmpty();
    }

    @Nested
    @DisplayName("existsByAircraftAndTypeAndTemplate")
    class ExistsByAircraftAndTypeAndTemplate {

        @Test
        @DisplayName("должен вызвать запрос без времени когда afterTime == null")
        void shouldUseAnyTimeQueryWhenAfterTimeIsNull() {
            when(jpaRepository.existsByAircraftAndTypeAndTemplateAnyTime("RA-1234", MessageType.DOWNLINK, "POSITION"))
                    .thenReturn(true);

            boolean result = adapter.existsByAircraftAndTypeAndTemplate("RA-1234", MessageType.DOWNLINK, "POSITION", null);

            assertThat(result).isTrue();
            verify(jpaRepository).existsByAircraftAndTypeAndTemplateAnyTime("RA-1234", MessageType.DOWNLINK, "POSITION");
            verify(jpaRepository, never()).existsByAircraftAndTypeAndTemplateAfter(any(), any(), any(), any());
        }

        @Test
        @DisplayName("должен вызвать запрос с afterTime когда оно указано")
        void shouldUseAfterTimeQueryWhenAfterTimeProvided() {
            LocalDateTime afterTime = LocalDateTime.now().minusHours(1);
            when(jpaRepository.existsByAircraftAndTypeAndTemplateAfter("RA-1234", MessageType.UPLINK, "ACK", afterTime))
                    .thenReturn(false);

            boolean result = adapter.existsByAircraftAndTypeAndTemplate("RA-1234", MessageType.UPLINK, "ACK", afterTime);

            assertThat(result).isFalse();
            verify(jpaRepository).existsByAircraftAndTypeAndTemplateAfter("RA-1234", MessageType.UPLINK, "ACK", afterTime);
            verify(jpaRepository, never()).existsByAircraftAndTypeAndTemplateAnyTime(any(), any(), any());
        }
    }

    @Test
    @DisplayName("existsPositionReportWithinMinutes: должен делегировать с вычисленным sinceTime")
    void shouldCheckPositionReportWithinMinutes() {
        when(jpaRepository.existsPositionReportWithinMinutes(eq("RA-1234"), any(LocalDateTime.class)))
                .thenReturn(true);

        boolean result = adapter.existsPositionReportWithinMinutes("RA-1234", 30);

        assertThat(result).isTrue();
        verify(jpaRepository).existsPositionReportWithinMinutes(eq("RA-1234"), any(LocalDateTime.class));
    }

    @Nested
    @DisplayName("findAllWithFilters")
    class FindAllWithFilters {

        private final Pageable pageable = Pageable.ofSize(20);

        @Test
        @DisplayName("должен использовать findByAircraftIdAndMessageType когда оба фильтра указаны")
        void shouldUseBothFilters() {
            Page<IncomingMessage> page = new PageImpl<>(List.of());
            when(jpaRepository.findByAircraftIdAndMessageType("RA-1234", MessageType.DOWNLINK, pageable))
                    .thenReturn(page);

            Page<IncomingMessage> result = adapter.findAllWithFilters("RA-1234", MessageType.DOWNLINK, pageable);

            assertThat(result).isEqualTo(page);
            verify(jpaRepository).findByAircraftIdAndMessageType("RA-1234", MessageType.DOWNLINK, pageable);
        }

        @Test
        @DisplayName("должен использовать findByAircraftId когда указан только aircraftId")
        void shouldUseAircraftIdFilterOnly() {
            Page<IncomingMessage> page = new PageImpl<>(List.of());
            when(jpaRepository.findByAircraftId("RA-1234", pageable)).thenReturn(page);

            Page<IncomingMessage> result = adapter.findAllWithFilters("RA-1234", null, pageable);

            assertThat(result).isEqualTo(page);
            verify(jpaRepository).findByAircraftId("RA-1234", pageable);
        }

        @Test
        @DisplayName("должен использовать findByMessageType когда указан только messageType")
        void shouldUseMessageTypeFilterOnly() {
            Page<IncomingMessage> page = new PageImpl<>(List.of());
            when(jpaRepository.findByMessageType(MessageType.GROUND, pageable)).thenReturn(page);

            Page<IncomingMessage> result = adapter.findAllWithFilters(null, MessageType.GROUND, pageable);

            assertThat(result).isEqualTo(page);
            verify(jpaRepository).findByMessageType(MessageType.GROUND, pageable);
        }

        @Test
        @DisplayName("должен использовать findAll когда фильтры не указаны")
        void shouldUseFindAllWhenNoFilters() {
            Page<IncomingMessage> page = new PageImpl<>(List.of());
            when(jpaRepository.findAll(pageable)).thenReturn(page);

            Page<IncomingMessage> result = adapter.findAllWithFilters(null, null, pageable);

            assertThat(result).isEqualTo(page);
            verify(jpaRepository).findAll(pageable);
        }
    }
}
