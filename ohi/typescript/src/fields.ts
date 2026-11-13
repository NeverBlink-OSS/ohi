/** The fields that a generated class declares, without its index signature for extra fields. */
type Declared<T> = keyof { [K in keyof T as string extends K ? never : number extends K ? never : K]: T[K] };

/**
 * Every field that the generated class `T` declares. The compiler checks that a value of this type
 * lists all of them and nothing else, so a field added to the model must be added here too.
 */
export type Fields<T> = Record<Declared<T>, true>;

/** `value` with only the fields in `fields`, discarding the extras. */
export function declared<T>(value: T, fields: Fields<T>): T {
  return Object.fromEntries(Object.entries(value as object).filter(([key]) => Object.hasOwn(fields, key))) as T;
}
