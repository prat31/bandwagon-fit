import type { DailySummary, MetricId, MetricRef } from "./types";
import { localParts } from "./dayRanges";

const MINUTES_PER_DAY = 24 * 60;

export interface MetricDefinition {
  id: MetricId;
  unit: "steps" | "meters" | "kcal" | "minutes" | "count" | "floors" | "clock";
  /** Days above this are treated as bad data: left out of totals and flagged. */
  dailyCap: number | null;
  extract(daily: DailySummary, filter?: MetricRef["filter"]): number | null;
}

/** Minutes from local midnight, wrapped to [-720, 720) so 23:30 is -30 and 00:30 is 30. */
export function wrappedClockMinutes(instantIso: string, zoneId: string): number {
  const p = localParts(new Date(instantIso), zoneId);
  const minutes = p.hour * 60 + p.minute;
  return minutes >= MINUTES_PER_DAY / 2 ? minutes - MINUTES_PER_DAY : minutes;
}

function workoutTypeSum(
  daily: DailySummary,
  types: string[] | undefined,
  pick: (t: { minutes: number; count: number; distanceMeters?: number | null }) => number | null | undefined,
): number | null {
  if (!types || types.length === 0) return null;
  const wanted = new Set(types.map((t) => t.toUpperCase()));
  let total = 0;
  for (const [type, value] of Object.entries(daily.workoutsByType ?? {})) {
    if (wanted.has(type.toUpperCase())) total += pick(value) ?? 0;
  }
  return total;
}

const definitions: MetricDefinition[] = [
  { id: "steps", unit: "steps", dailyCap: 100_000, extract: (d) => d.steps },
  {
    id: "distance",
    unit: "meters",
    dailyCap: 150_000,
    extract: (d, filter) =>
      filter?.workoutTypes?.length
        ? workoutTypeSum(d, filter.workoutTypes, (t) => t.distanceMeters)
        : d.distanceMeters,
  },
  { id: "active_calories", unit: "kcal", dailyCap: 8_000, extract: (d) => d.activeCalories },
  { id: "active_minutes", unit: "minutes", dailyCap: 900, extract: (d) => d.activeMinutes },
  {
    id: "workout_minutes",
    unit: "minutes",
    dailyCap: 900,
    extract: (d, filter) =>
      filter?.workoutTypes?.length
        ? workoutTypeSum(d, filter.workoutTypes, (t) => t.minutes)
        : d.workoutMinutes,
  },
  {
    id: "workout_count",
    unit: "count",
    dailyCap: 20,
    extract: (d, filter) =>
      filter?.workoutTypes?.length
        ? workoutTypeSum(d, filter.workoutTypes, (t) => t.count)
        : d.workoutCount,
  },
  { id: "floors", unit: "floors", dailyCap: 500, extract: (d) => d.floors },
  {
    id: "sleep_minutes",
    unit: "minutes",
    dailyCap: 16 * 60,
    extract: (d) => d.sleep?.durationMinutes ?? null,
  },
  {
    id: "bedtime",
    unit: "clock",
    dailyCap: null,
    extract: (d) => (d.sleep ? wrappedClockMinutes(d.sleep.start, d.sleep.zoneId) : null),
  },
  {
    id: "wake_time",
    unit: "clock",
    dailyCap: null,
    extract: (d) => {
      if (!d.sleep) return null;
      const p = localParts(new Date(d.sleep.end), d.sleep.zoneId);
      return p.hour * 60 + p.minute;
    },
  },
  { id: "mindful_minutes", unit: "minutes", dailyCap: 600, extract: (d) => d.mindfulMinutes },
];

const registry = new Map<MetricId, MetricDefinition>(definitions.map((d) => [d.id, d]));

export function metric(id: MetricId): MetricDefinition {
  const def = registry.get(id);
  if (!def) throw new Error(`Unknown metric ${id}`);
  return def;
}

export function isMetricId(value: unknown): value is MetricId {
  return typeof value === "string" && registry.has(value as MetricId);
}

/** `shared` holds metric ids; the clock metrics come with sleep. */
export function sharedKey(id: MetricId): MetricId {
  return id === "bedtime" || id === "wake_time" ? "sleep_minutes" : id;
}

export function isShared(daily: DailySummary, id: MetricId): boolean {
  return (daily.shared ?? []).includes(sharedKey(id));
}
