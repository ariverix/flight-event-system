package ru.protectinfotrans.eca.eventhandling;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpEntity;
import org.springframework.http.ResponseEntity;
import ru.protectinfotrans.eca.BaseIntegrationTest;
import ru.protectinfotrans.eca.eventhandling.application.EventHandlerResolver;
import ru.protectinfotrans.eca.eventhandling.domain.DeliveryStatus;
import ru.protectinfotrans.eca.eventhandling.domain.EventHandler;
import ru.protectinfotrans.eca.eventhandling.domain.NotificationChannelType;
import ru.protectinfotrans.eca.eventhandling.domain.NotificationDelivery;
import ru.protectinfotrans.eca.eventhandling.dto.EventHandlerCreateRequest;
import ru.protectinfotrans.eca.eventhandling.dto.EventHandlerResponse;
import ru.protectinfotrans.eca.eventhandling.dto.FolderCreateRequest;
import ru.protectinfotrans.eca.eventhandling.dto.FolderResponse;
import ru.protectinfotrans.eca.eventhandling.domain.HandlerScope;
import ru.protectinfotrans.eca.eventhandling.domain.NotificationTrigger;
import ru.protectinfotrans.eca.eventhandling.port.in.EventHandlerManagementUseCase;
import ru.protectinfotrans.eca.eventhandling.port.in.FolderManagementUseCase;
import ru.protectinfotrans.eca.eventhandling.port.out.NotificationDeliveryRepositoryPort;
import ru.protectinfotrans.eca.execution.application.ExecutionService;
import ru.protectinfotrans.eca.execution.domain.ExecutionInstance;
import ru.protectinfotrans.eca.execution.port.out.ExecutionRepositoryPort;
import ru.protectinfotrans.eca.sequence.domain.StepType;
import ru.protectinfotrans.eca.sequence.domain.TransitionAction;
import ru.protectinfotrans.eca.sequence.dto.SequenceCreateRequest;
import ru.protectinfotrans.eca.sequence.dto.SequenceResponse;
import ru.protectinfotrans.eca.sequence.dto.StepCreateRequest;
import ru.protectinfotrans.eca.sequence.port.in.SequenceManagementUseCase;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P3-4: сквозной сценарий Event Handling — папки, наследование/override обработчиков, Notify-каналы
 * email/webhook, идемпотентная доставка. Демосценарий: борт VP-BQR / рейс SU1234.
 */
@DisplayName("P3-4: event handling — папки, наследование/override, каналы, идемпотентность")
class P3_4_EventHandlingScenarioIntTest extends BaseIntegrationTest {

    @Autowired private FolderManagementUseCase folderUseCase;
    @Autowired private EventHandlerManagementUseCase handlerUseCase;
    @Autowired private EventHandlerResolver resolver;
    @Autowired private NotificationDeliveryRepositoryPort deliveryRepository;
    @Autowired private SequenceManagementUseCase sequenceUseCase;
    @Autowired private ExecutionService executionService;
    @Autowired private ExecutionRepositoryPort executionRepository;

    private static final String AIRCRAFT_ID = "VP-BQR";
    private static final String FLIGHT_NUMBER = "SU1234";

    private ExecutionInstance findInstance(Long sequenceId) {
        return executionRepository.findAll(org.springframework.data.domain.PageRequest.of(0, 200))
                .getContent().stream()
                .filter(i -> i.getSequenceId().equals(sequenceId) && AIRCRAFT_ID.equals(i.getAircraftId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No instance for sequence " + sequenceId));
    }

    /** Последовательность с одним EVALUATE-шагом, который проходит (стадия INIT) и notify на success. */
    private Long createNotifyingSequence(String name) {
        SequenceResponse created = sequenceUseCase.createSequence(
                new SequenceCreateRequest(name, "P3-4", null, null), 1L);
        sequenceUseCase.addStep(created.id(), new StepCreateRequest(
                "EVALUATE INIT, notify on success",
                StepType.EVALUATE,
                "{\"type\":\"FLIGHT_STAGE\",\"operator\":\"EQUALS\",\"targetStage\":\"INIT\"}",
                null,
                TransitionAction.END, null, true,
                TransitionAction.ABORT, null, false
        ), 1L);
        sequenceUseCase.activateSequence(created.id(), 1L);
        return created.id();
    }

    private int awaitDeliveryCount(long executionId) {
        long deadline = System.currentTimeMillis() + 5000;
        Integer count = 0;
        while (System.currentTimeMillis() < deadline) {
            count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM notification_deliveries WHERE execution_id = ?",
                    Integer.class, executionId);
            if (count != null && count >= 1) {
                return count;
            }
            try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        return count == null ? 0 : count;
    }

    // ============================================================
    // 1. Сквозная доставка через движок (folder-level handler)
    // ============================================================
    @Nested
    @DisplayName("Сквозная доставка через движок")
    class EndToEndDeliveryTests {

        @Test
        @DisplayName("шаг с Notify -> folder-level обработчик доставляет уведомление (durable строка SENT)")
        void engineStepNotifyDeliversViaFolderHandler() {
            FolderResponse folder = folderUseCase.createFolder(new FolderCreateRequest("Рейсы SU", null));
            Long sequenceId = createNotifyingSequence("P3-4 e2e");
            sequenceUseCase.assignToFolder(sequenceId, folder.id(), 1L);
            handlerUseCase.createHandler(new EventHandlerCreateRequest(
                    HandlerScope.FOLDER, folder.id(), NotificationTrigger.ON_SUCCESS,
                    NotificationChannelType.EMAIL, "ops@example.com"));

            executionService.startExecution(sequenceId, AIRCRAFT_ID, FLIGHT_NUMBER);
            long executionId = findInstance(sequenceId).getId();

            int count = awaitDeliveryCount(executionId);
            assertThat(count).isEqualTo(1);

            String status = jdbcTemplate.queryForObject(
                    "SELECT status FROM notification_deliveries WHERE execution_id = ?", String.class, executionId);
            assertThat(status).isEqualTo(DeliveryStatus.SENT.name());
        }
    }

    // ============================================================
    // 2. Разрешение: наследование папок + override последовательности (на реальной БД)
    // ============================================================
    @Nested
    @DisplayName("Разрешение обработчиков (наследование/override)")
    class ResolutionTests {

        @Test
        @DisplayName("sequence-level обработчик переопределяет folder-level (override)")
        void sequenceOverridesFolder() {
            FolderResponse folder = folderUseCase.createFolder(new FolderCreateRequest("F", null));
            handlerUseCase.createHandler(new EventHandlerCreateRequest(
                    HandlerScope.FOLDER, folder.id(), NotificationTrigger.ON_ANY,
                    NotificationChannelType.EMAIL, "folder@example.com"));
            EventHandlerResponse seqHandler = handlerUseCase.createHandler(new EventHandlerCreateRequest(
                    HandlerScope.SEQUENCE, 555L, NotificationTrigger.ON_ANY,
                    NotificationChannelType.EMAIL, "seq@example.com"));

            List<EventHandler> resolved = resolver.resolve(555L, folder.id());

            assertThat(resolved).extracting(EventHandler::getId).containsExactly(seqHandler.id());
            assertThat(resolved).extracting(EventHandler::getTarget).containsExactly("seq@example.com");
        }

        @Test
        @DisplayName("обработчик родительской папки наследуется во вложенную папку (nearest-wins вверх по дереву)")
        void inheritsFromParentFolder() {
            FolderResponse parent = folderUseCase.createFolder(new FolderCreateRequest("Parent", null));
            FolderResponse child = folderUseCase.createFolder(new FolderCreateRequest("Child", parent.id()));
            EventHandlerResponse parentHandler = handlerUseCase.createHandler(new EventHandlerCreateRequest(
                    HandlerScope.FOLDER, parent.id(), NotificationTrigger.ON_ANY,
                    NotificationChannelType.WEBHOOK, "https://hook.example.com"));

            // последовательность лежит в child (своих обработчиков нет, child тоже пуст) -> берём parent
            List<EventHandler> resolved = resolver.resolve(777L, child.id());

            assertThat(resolved).extracting(EventHandler::getId).containsExactly(parentHandler.id());
        }
    }

    // ============================================================
    // 3. Идемпотентность доставки (durable дедуп на уровне БД)
    // ============================================================
    @Nested
    @DisplayName("Идемпотентность доставки")
    class IdempotencyTests {

        @Test
        @DisplayName("повторная запись доставки с тем же дедуп-ключом -> конфликт UNIQUE (exactly-once)")
        void duplicateDeliveryRejectedByUniqueIndex() {
            deliveryRepository.save(NotificationDelivery.builder()
                    .executionId(900L).stepIndex(1).result("SUCCESS").handlerId(42L)
                    .channel(NotificationChannelType.EMAIL).target("ops@example.com")
                    .status(DeliveryStatus.SENT).build());

            assertThat(deliveryRepository.existsByDedupKey(900L, 1, "SUCCESS", 42L)).isTrue();

            assertThatThrownBy(() -> deliveryRepository.save(NotificationDelivery.builder()
                    .executionId(900L).stepIndex(1).result("SUCCESS").handlerId(42L)
                    .channel(NotificationChannelType.EMAIL).target("ops@example.com")
                    .status(DeliveryStatus.SENT).build()))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("success и failure одного шага — РАЗНЫЕ доставки (result входит в дедуп-ключ)")
        void successAndFailureAreDistinctDeliveries() {
            deliveryRepository.save(NotificationDelivery.builder()
                    .executionId(901L).stepIndex(2).result("SUCCESS").handlerId(43L)
                    .channel(NotificationChannelType.EMAIL).target("a@example.com")
                    .status(DeliveryStatus.SENT).build());
            deliveryRepository.save(NotificationDelivery.builder()
                    .executionId(901L).stepIndex(2).result("FAILURE").handlerId(43L)
                    .channel(NotificationChannelType.EMAIL).target("a@example.com")
                    .status(DeliveryStatus.SENT).build());

            assertThat(deliveryRepository.existsByDedupKey(901L, 2, "SUCCESS", 43L)).isTrue();
            assertThat(deliveryRepository.existsByDedupKey(901L, 2, "FAILURE", 43L)).isTrue();
        }
    }

    // ============================================================
    // 4. RBAC на эндпоинтах
    // ============================================================
    @Nested
    @DisplayName("RBAC эндпоинтов папок/обработчиков")
    class RbacTests {

        @Test
        @DisplayName("POST /api/v1/folders без JWT -> 401")
        void createFolderWithoutTokenRejected() {
            ResponseEntity<String> resp = restTemplate.postForEntity(
                    "/api/v1/folders", new FolderCreateRequest("X", null), String.class);
            assertThat(resp.getStatusCode().value()).isEqualTo(401);
        }

        @Test
        @DisplayName("POST /api/v1/folders с admin JWT -> 201")
        void createFolderWithAdminToken() {
            String token = getAdminToken();
            ResponseEntity<FolderResponse> resp = restTemplate.exchange(
                    "/api/v1/folders", HttpMethod.POST,
                    new HttpEntity<>(new FolderCreateRequest("Папка через API", null), authHeaders(token)),
                    FolderResponse.class);
            assertThat(resp.getStatusCode().value()).isEqualTo(201);
            assertThat(resp.getBody()).isNotNull();
            assertThat(resp.getBody().id()).isNotNull();
        }

        @Test
        @DisplayName("GET /api/v1/event-handlers без JWT -> 401")
        void listHandlersWithoutTokenRejected() {
            ResponseEntity<String> resp = restTemplate.getForEntity(
                    "/api/v1/event-handlers?scope=FOLDER&scopeId=1", String.class);
            assertThat(resp.getStatusCode().value()).isEqualTo(401);
        }
    }
}
