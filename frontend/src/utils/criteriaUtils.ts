/**
 * Утилиты для работы с JSON-критериями последовательностей.
 * Типизированы без `any`: JSON-значение представлено как `unknown`.
 */
export const parseCriteria = (json: string | null): unknown => {
  if (!json) return null;
  try {
    return JSON.parse(json) as unknown;
  } catch {
    return null;
  }
};

export const stringifyCriteria = (criteria: unknown): string => {
  try {
    return JSON.stringify(criteria, null, 2);
  } catch {
    return '';
  }
};

export const validateCriteriaJson = (json: string): boolean => {
  if (!json.trim()) return true;
  try {
    JSON.parse(json);
    return true;
  } catch {
    return false;
  }
};
