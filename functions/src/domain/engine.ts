import type {
  ChallengeSpec,
  Condition,
  DailySummary,
  DayOutcome,
  MemberResult,
  MetricId,
} from "./types";
import { METRIC_IDS } from "./types";
import { addDays, datesBetween, dayStart, weekday } from "./dayRanges";
import { isMetricId, isShared, metric } from "./metrics";

export const GRACE_HOURS = 12;
export const MILESTONES = [25, 50, 75, 100];
export const MAX_WINDOW_DAYS = 366;

/** Metrics the spec needs; a member must share all of them to take part. */
export function requiredMetrics(spec: ChallengeSpec): MetricId[] {
  if (spec.daily.op === "all") {
    return [...new Set(spec.daily.conditions.map((c) => c.metric))];
  }
  return [spec.metric.id];
}

/** The member-local dates the challenge counts, honouring the days-of-week filter. */
export function countedDates(spec: ChallengeSpec): string[] {
  const days = spec.window.days && spec.window.days.length > 0 ? new Set(spec.window.days) : null;
  return datesBetween(spec.window.start, spec.window.end).filter(
    (date) => days === null || days.has(weekday(date)),
  );
}

function passes(condition: Condition, value: number | null): boolean {
  if (value === null) return false;
  const tolerance = condition.toleranceMin ?? 0;
  return condition.cmp === "<="
    ? value <= condition.value + tolerance
    : value >= condition.value - tolerance;
}

interface DayValue {
  /** Value fed to the aggregate (raw, capped, or 1/0 for hit rules). */
  value: number | null;
  /** Raw metric value, used for SUM tie-breaks. */
  raw: number | null;
  hit: boolean;
  shared: boolean;
  flagged: boolean;
}

function evaluateDay(spec: ChallengeSpec, daily: DailySummary | undefined): DayValue {
  const empty = { value: null, raw: null, hit: false, shared: true, flagged: false };
  if (!daily) return empty;
  const needed = requiredMetrics(spec);
  if (!needed.every((id) => isShared(daily, id))) {
    return { ...empty, shared: false };
  }

  const read = (id: MetricId, filter = id === spec.metric.id ? spec.metric.filter : undefined) => {
    const def = metric(id);
    const value = def.extract(daily, filter);
    if (value === null || value === undefined) return { value: null, flagged: false };
    if (def.dailyCap !== null && value > def.dailyCap) return { value: null, flagged: true };
    return { value, flagged: false };
  };

  if (spec.daily.op === "all") {
    const results = spec.daily.conditions.map((c) => ({ c, ...read(c.metric) }));
    const flagged = results.some((r) => r.flagged);
    if (results.every((r) => r.value === null)) return { ...empty, flagged };
    const hit = results.every((r) => passes(r.c, r.value));
    return { value: hit ? 1 : 0, raw: hit ? 1 : 0, hit, shared: true, flagged };
  }

  const primary = read(spec.metric.id);
  if (primary.value === null) return { ...empty, flagged: primary.flagged };
  const raw = primary.value;
  switch (spec.daily.op) {
    case "value":
      return { value: raw, raw, hit: raw > 0, shared: true, flagged: false };
    case "cap": {
      const capped = Math.min(raw, spec.daily.max);
      return { value: capped, raw, hit: raw > 0, shared: true, flagged: false };
    }
    case "atLeast": {
      const hit = raw >= spec.daily.value;
      return { value: hit ? 1 : 0, raw, hit, shared: true, flagged: false };
    }
  }
}

/**
 * Scores one member from their own daily summaries. Pure and idempotent: the server re-runs it
 * over the member's whole window on every change, so a missed or repeated event can't drift.
 */
export function evaluateMember(
  spec: ChallengeSpec,
  summaries: DailySummary[],
): { result: MemberResult; days: DayOutcome[] } {
  const byDate = new Map(summaries.map((s) => [s.date, s]));
  const dates = countedDates(spec);
  const values = dates.map((date) => ({ date, ...evaluateDay(spec, byDate.get(date)) }));

  const withData = values.filter((v) => v.value !== null);
  const anySummary = dates.some((date) => byDate.has(date));
  const notShared = anySummary && values.every((v) => !v.shared || !byDate.has(v.date));

  let score = 0;
  switch (spec.aggregate) {
    case "SUM":
      score = withData.reduce((sum, v) => sum + (v.value ?? 0), 0);
      break;
    case "AVERAGE":
      score =
        withData.length === 0
          ? 0
          : withData.reduce((sum, v) => sum + (v.value ?? 0), 0) / withData.length;
      break;
    case "DAYS_HIT":
      score = values.filter((v) => v.hit).length;
      break;
    case "BEST_DAY":
      score = withData.reduce((best, v) => Math.max(best, v.value ?? 0), 0);
      break;
    case "LONGEST_STREAK":
      score = longestRun(values.map((v) => v.hit));
      break;
  }

  const target = spec.goal?.target ?? null;
  let finishedOn: string | null = null;
  if (spec.kind === "TARGET" && target !== null && target > 0) {
    finishedOn = firstDateReaching(spec, values, target);
  }
  const finished = finishedOn !== null;

  let tieBreak = 0;
  if (spec.ranking?.tieBreak === "SUM") {
    tieBreak = withData.reduce((sum, v) => sum + (v.raw ?? 0), 0);
  } else if (spec.ranking?.tieBreak === "EARLIEST_FINISH") {
    // Earlier finish ranks higher, so a smaller day index gives a bigger tie-break.
    tieBreak = finishedOn ? -dates.indexOf(finishedOn) : -MAX_WINDOW_DAYS;
  }

  return {
    result: {
      score: round(score),
      tieBreak,
      progress: spec.kind === "TARGET" && target ? Math.min(1, score / target) : null,
      daysHit: values.filter((v) => v.hit).length,
      daysWithData: withData.length,
      longestStreak: longestRun(values.map((v) => v.hit)),
      finished,
      finishedOn,
      notShared,
      flagged: values.some((v) => v.flagged),
    },
    days: values.map((v) => ({
      date: v.date,
      value: v.value,
      status: !byDate.has(v.date)
        ? null
        : !v.shared
          ? "NOT_SHARED"
          : v.value === null
            ? null
            : v.hit
              ? "HIT"
              : "MISSED",
    })),
  };
}

function round(value: number): number {
  return Math.round(value * 100) / 100;
}

function longestRun(hits: boolean[]): number {
  let best = 0;
  let run = 0;
  for (const hit of hits) {
    run = hit ? run + 1 : 0;
    best = Math.max(best, run);
  }
  return best;
}

/** The first date the running aggregate reached the target, for SUM and DAYS_HIT goals. */
function firstDateReaching(
  spec: ChallengeSpec,
  values: Array<{ date: string; value: number | null; hit: boolean }>,
  target: number,
): string | null {
  let running = 0;
  let run = 0;
  let best = 0;
  for (const v of values) {
    switch (spec.aggregate) {
      case "SUM":
        running += v.value ?? 0;
        break;
      case "DAYS_HIT":
        running += v.hit ? 1 : 0;
        break;
      case "BEST_DAY":
        running = Math.max(running, v.value ?? 0);
        break;
      case "LONGEST_STREAK":
        run = v.hit ? run + 1 : 0;
        best = Math.max(best, run);
        running = best;
        break;
      case "AVERAGE":
        return null;
    }
    if (running >= target) return v.date;
  }
  return null;
}

export interface RankInput {
  memberId: string;
  score: number;
  tieBreak: number;
}

/** Competition ranking: equal score and tie-break share a rank ("1, 2, 2, 4"). */
export function rank<T extends RankInput>(entries: T[]): Array<T & { rank: number }> {
  const sorted = [...entries].sort(
    (a, b) => b.score - a.score || b.tieBreak - a.tieBreak || a.memberId.localeCompare(b.memberId),
  );
  const out: Array<T & { rank: number }> = [];
  sorted.forEach((entry, index) => {
    const previous = out[index - 1];
    const tied = previous && previous.score === entry.score && previous.tieBreak === entry.tieBreak;
    out.push({ ...entry, rank: tied ? previous.rank : index + 1 });
  });
  return out;
}

export function pct(total: number, target: number): number {
  return target <= 0 ? 0 : (total / target) * 100;
}

export function milestonesCrossed(previous: number[], pctValue: number): number[] {
  return MILESTONES.filter((m) => !previous.includes(m) && pctValue >= m);
}

export interface DayCounts {
  date: string;
  hit: number;
  missed: number;
  /** Members expected to report: everyone in the group. */
  members: number;
}

/** At least half the members need data, and everyone with data hit the rule. */
export function groupKept(day: DayCounts): boolean {
  const withData = day.hit + day.missed;
  return withData > 0 && withData * 2 >= day.members && day.missed === 0;
}

export function groupStreak(daysOldestFirst: DayCounts[]): { current: number; best: number } {
  let current = 0;
  for (let i = daysOldestFirst.length - 1; i >= 0; i -= 1) {
    if (!groupKept(daysOldestFirst[i])) break;
    current += 1;
  }
  const best = longestRun(daysOldestFirst.map(groupKept));
  return { current, best: Math.max(best, current) };
}

/**
 * A challenge is final once its end date is over everywhere on Earth, plus a grace period for late
 * syncs. This avoids reading every member's time zone, which matters in very large groups.
 */
export function endInstant(endDate: string, graceHours = GRACE_HOURS): Date {
  const latest = dayStart(addDays(endDate, 1), "Etc/GMT+12");
  return new Date(latest.getTime() + graceHours * 3_600_000);
}

export function isActiveOn(spec: ChallengeSpec, date: string): boolean {
  return date >= spec.window.start && date <= spec.window.end;
}

/** The next weekly instance: same length, starting the day after this one ends. */
export function nextWeeklyWindow(spec: ChallengeSpec): ChallengeSpec["window"] {
  const length = datesBetween(spec.window.start, spec.window.end).length;
  const start = addDays(spec.window.end, 1);
  return { ...spec.window, start, end: addDays(start, Math.max(length, 7) - 1) };
}

const DATE = /^\d{4}-\d{2}-\d{2}$/;
const KINDS = ["COMPETE", "TARGET", "TEAM"];
const AGGREGATES = ["SUM", "AVERAGE", "DAYS_HIT", "BEST_DAY", "LONGEST_STREAK"];
const RENDERERS = ["LEADERBOARD", "PROGRESS", "BOARD", "BOSS", "ROUTE"];

/** Returns a user-facing reason the spec is invalid, or null when it can be stored. */
export function validateSpec(input: unknown): string | null {
  if (!input || typeof input !== "object") return "Missing challenge settings.";
  const spec = input as ChallengeSpec;
  if (spec.schemaVersion !== 1) return "Unsupported challenge version.";
  if (!KINDS.includes(spec.kind)) return "Unknown challenge kind.";
  if (!spec.metric || !isMetricId(spec.metric.id)) return "Unknown metric.";
  if (!AGGREGATES.includes(spec.aggregate)) return "Unknown scoring.";
  if (!spec.display || !RENDERERS.includes(spec.display.renderer)) return "Unknown display.";
  const title = typeof spec.display.title === "string" ? spec.display.title.trim() : "";
  if (title.length === 0 || title.length > 60) return "Give the challenge a name up to 60 characters.";

  const daily = spec.daily as { op?: string; max?: unknown; value?: unknown; conditions?: unknown };
  switch (daily?.op) {
    case "value":
      break;
    case "cap":
      if (typeof daily.max !== "number" || daily.max <= 0) return "The daily cap must be positive.";
      break;
    case "atLeast":
      if (typeof daily.value !== "number" || daily.value <= 0) return "The daily target must be positive.";
      break;
    case "all": {
      const conditions = daily.conditions;
      if (!Array.isArray(conditions) || conditions.length === 0 || conditions.length > 4) {
        return "A daily rule needs one to four conditions.";
      }
      for (const c of conditions as Condition[]) {
        if (!isMetricId(c.metric) || !["<=", ">="].includes(c.cmp) || typeof c.value !== "number") {
          return "A daily condition is not valid.";
        }
      }
      break;
    }
    default:
      return "Unknown daily rule.";
  }

  if (spec.kind !== "COMPETE") {
    if (!spec.goal || typeof spec.goal.target !== "number" || spec.goal.target <= 0) {
      return "Set a goal above zero.";
    }
  }
  if (spec.kind === "TEAM" && !["SUM", "DAYS_HIT"].includes(spec.aggregate)) {
    return "Team goals add up totals or days.";
  }

  const window = spec.window;
  if (!window || !DATE.test(window.start ?? "") || !DATE.test(window.end ?? "")) {
    return "Pick start and end dates.";
  }
  if (window.end < window.start) return "The end date is before the start date.";
  if (datesBetween(window.start, window.end).length > MAX_WINDOW_DAYS) {
    return "Challenges can last up to a year.";
  }
  if (window.days && (!Array.isArray(window.days) || window.days.some((d) => !Number.isInteger(d) || d < 0 || d > 6))) {
    return "Days of the week are not valid.";
  }
  if (window.recurrence && window.recurrence !== "WEEKLY") return "Unknown repeat setting.";
  return null;
}

export { METRIC_IDS };
