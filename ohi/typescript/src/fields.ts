/** `value` with only the given fields, which come from the generated field lists, so without extras. */
export function declared<T>(value: T, fields: readonly string[]): T {
  return Object.fromEntries(Object.entries(value as object).filter(([key]) => fields.includes(key))) as T;
}
