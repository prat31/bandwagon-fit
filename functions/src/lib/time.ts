/** Firestore stores instants as Timestamps, but tests and old rows may hold ISO strings or Dates. */
export function toDate(value: unknown): Date | null {
  if (!value) return null;
  if (value instanceof Date) return value;
  if (typeof value === "string") {
    const parsed = new Date(value);
    return Number.isNaN(parsed.getTime()) ? null : parsed;
  }
  if (typeof value === "object" && value !== null && "toDate" in value) {
    const toDateFn = (value as { toDate?: () => Date }).toDate;
    if (typeof toDateFn === "function") return toDateFn.call(value);
  }
  if (typeof value === "number") return new Date(value);
  return null;
}

export function toIso(value: unknown): string | null {
  const date = toDate(value);
  return date ? date.toISOString() : null;
}
