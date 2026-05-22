export const parseCriteria = (json: string | null): any => {
  if (!json) return null;
  try {
    return JSON.parse(json);
  } catch {
    return null;
  }
};

export const stringifyCriteria = (criteria: any): string => {
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
  } catch (e) {
    return false;
  }
};
