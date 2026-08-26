export type StepType = 'ACTION' | 'EVALUATE' | 'WAIT' | string;

/**
 * Акцентный цвет типа шага — единая палитра (системные цвета macOS) для
 * канвы (CustomStepNode, миникарта SequenceEditorGraph) и списка шагов
 * (EditorStepList), чтобы тип шага читался одинаково в обоих местах.
 */
export function getTypeAccent(stepType: StepType, isDark: boolean): string {
  if (stepType === 'ACTION')   return isDark ? '#0a84ff' : '#0071e3';
  if (stepType === 'EVALUATE') return isDark ? '#bf5af2' : '#af52de';
  if (stepType === 'WAIT')     return isDark ? '#ff9f0a' : '#ff9500';
  return isDark ? '#8e8e93' : '#8e8e93';
}
