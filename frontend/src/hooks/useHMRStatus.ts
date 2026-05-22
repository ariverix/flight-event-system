// Логирование HMR обновлений в консоль браузера (только в dev)
export const setupHMRLogging = () => {
  if (!import.meta.hot) return;

  import.meta.hot.on('vite:beforeUpdate', (data: any) => {
    const files = (data.updates ?? []).map((u: any) => u.path?.split('/').pop()).join(', ');
    console.log(`%c🔄 HMR: ${files}`, 'color:#3b82f6;font-weight:bold');
  });

  import.meta.hot.on('vite:afterUpdate', () => {
    console.log('%c✅ HMR применено', 'color:#10b981;font-weight:bold');
  });

  import.meta.hot.on('vite:error', (data: any) => {
    console.error('%c❌ HMR ошибка:', 'color:#ef4444;font-weight:bold', data.err?.message ?? data);
  });

  import.meta.hot.on('vite:beforeFullReload', () => {
    console.log('%c🔄 Полная перезагрузка...', 'color:#f59e0b;font-weight:bold');
  });
};
