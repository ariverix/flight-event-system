import { describe, it, expect } from 'vitest';
import { getHandleColors } from '../stepTypeColors';

describe('getHandleColors', () => {
  it('в тёмной теме возвращает тёмную пару фон/рамка', () => {
    expect(getHandleColors(true)).toEqual({ background: '#3a3a3c', border: '#5a5a5e' });
  });

  it('в светлой теме возвращает светлую пару фон/рамка', () => {
    expect(getHandleColors(false)).toEqual({ background: '#d1d1d6', border: '#aeaeb2' });
  });
});
