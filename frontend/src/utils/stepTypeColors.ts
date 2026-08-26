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

export type StepNodeState = 'idle' | 'active' | 'success' | 'failure' | 'unreached';

export interface StateTokens {
  bg: string;
  border: string;
  textMain: string;
  textSub: string;
  shadow: string;
  animation?: string;
  opacity?: number;
}

/**
 * Токены поверхности узла по состоянию выполнения — общие для канвы
 * (CustomStepNode) и панели деталей узла (NodeDetailPanel), чтобы карточка
 * узла и её детальный вид совпадали цветом состояния.
 */
export function getStateTokens(state: StepNodeState, isDark: boolean): StateTokens {
  if (isDark) {
    switch (state) {
      case 'active':    return { bg: 'rgba(255,159,10,0.12)',  border: '#ff9f0a', textMain: '#ff9f0a', textSub: 'rgba(255,159,10,0.75)', shadow: 'none', animation: 'nodeGlow 2s ease-in-out infinite' };
      case 'success':   return { bg: 'rgba(48,209,88,0.12)',   border: '#30d158', textMain: '#30d158', textSub: 'rgba(48,209,88,0.75)',  shadow: 'none' };
      case 'failure':   return { bg: 'rgba(255,69,58,0.12)',   border: '#ff453a', textMain: '#ff453a', textSub: 'rgba(255,69,58,0.75)',  shadow: 'none' };
      case 'unreached': return { bg: '#262626', border: 'rgba(255,255,255,0.10)', textMain: 'rgba(255,255,255,0.35)', textSub: 'rgba(255,255,255,0.22)', shadow: 'none', opacity: 0.5 };
      default:          return { bg: '#262626', border: 'rgba(255,255,255,0.12)', textMain: '#f5f5f7', textSub: 'rgba(255,255,255,0.55)', shadow: 'none' };
    }
  } else {
    switch (state) {
      case 'active':    return { bg: 'rgba(255,149,0,0.10)',  border: '#ff9500', textMain: '#c2410c', textSub: '#9a3412', shadow: 'none', animation: 'nodeGlow 2s ease-in-out infinite' };
      case 'success':   return { bg: 'rgba(52,199,89,0.10)',  border: '#34c759', textMain: '#15803d', textSub: '#166534', shadow: 'none' };
      case 'failure':   return { bg: 'rgba(255,59,48,0.10)',  border: '#ff3b30', textMain: '#b91c1c', textSub: '#991b1b', shadow: 'none' };
      case 'unreached': return { bg: '#f5f5f7', border: 'rgba(0,0,0,0.08)', textMain: '#8e8e93', textSub: '#c7c7cc', shadow: 'none', opacity: 0.6 };
      default:          return { bg: '#ffffff', border: 'rgba(0,0,0,0.12)', textMain: '#1d1d1f', textSub: '#6e6e73', shadow: 'none' };
    }
  }
}
