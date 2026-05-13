package ru.protectinfotrans.eca.execution.port.in;

import ru.protectinfotrans.eca.execution.domain.ExecutionStatus;
import ru.protectinfotrans.eca.execution.dto.ExecutionInstanceResponse;
import ru.protectinfotrans.eca.sequence.dto.PageResponse;

/**
 * Входной порт модуля Execution Engine — просмотр статуса выполнения последовательностей.
 * Реализует UC-05.
 *
 * См. диплом: раздел 1.3.5 (UC-05 Просмотр статуса выполнения), раздел 1.4.4, таблица 1.6
 */
public interface ExecutionManagementUseCase {

    /**
     * UC-05: Получить список экземпляров выполнения с фильтрацией и пагинацией.
     *
     * @param page номер страницы
     * @param size размер страницы
     * @param status фильтр по статусу (опционально)
     * @param aircraftId фильтр по ВС (опционально)
     * @param sequenceId фильтр по последовательности (опционально)
     */
    PageResponse<ExecutionInstanceResponse> listExecutions(
            int page,
            int size,
            ExecutionStatus status,
            String aircraftId,
            Long sequenceId
    );

    /**
     * UC-05: Получить детали экземпляра с историей шагов.
     */
    ExecutionInstanceResponse getExecution(Long id);
}
