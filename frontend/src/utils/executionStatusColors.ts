export type ExecutionStatusLike = 'RUNNING' | 'WAITING' | 'COMPLETED' | 'ABORTED' | string;

/**
 * Акцентный цвет статуса выполнения — единая палитра (системные цвета macOS)
 * для AppLayout (уведомления), InstancesDashboard и ExecutionDetail/List,
 * чтобы статус читался одинаково во всех местах.
 */
export function getExecutionStatusColor(status: ExecutionStatusLike, isDark: boolean): string {
  switch (status) {
    case 'RUNNING':   return isDark ? '#0a84ff' : '#0071e3';
    case 'WAITING':   return isDark ? '#ff9f0a' : '#c2410c';
    case 'COMPLETED': return isDark ? '#30d158' : '#248a3d';
    case 'ABORTED':   return isDark ? '#ff453a' : '#d70015';
    default:          return '#8e8e93';
  }
}
