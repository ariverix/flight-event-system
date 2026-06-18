package ru.protectinfotrans.eca.execution.port.in;

import ru.protectinfotrans.eca.execution.domain.ExecutionStatus;
import ru.protectinfotrans.eca.execution.dto.ExecutionInstanceResponse;
import ru.protectinfotrans.eca.PageResponse;

/**
 * Входной порт модуля Execution Engine — просмотр статуса выполнения последовательностей.
 *
 */
public interface ExecutionManagementUseCase {

    /**
     * Получить список экземпляров выполнения с фильтрацией и пагинацией.
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
     * Получить детали экземпляра с историей шагов.
     */
    ExecutionInstanceResponse getExecution(Long id);
}
