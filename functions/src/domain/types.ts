/** Plain data shapes shared by every Cloud Function. No Firebase imports in this folder. */

export interface SleepSummary {
  /** ISO-8601 instant. */
  start: string;
  /** ISO-8601 instant. */
  end: string;
  /** Duration in minutes with awake stages removed when available. */
  durationMinutes: number;
  /** IANA zone id at the time of the record. */
  zoneId: string;
}

export interface WorkoutTypeTotal {
  minutes: number;
  count: number;
  distanceMeters?: number | null;
}

/**
 * The only health data that leaves a phone: one document per member-local date, holding the
 * metrics a challenge can use. `shared` lists the metric ids the member lets the app read, so
 * "not shared" is never confused with zero.
 */
export interface DailySummary {
  userId: string;
  /** Local date, YYYY-MM-DD. */
  date: string;
  timeZone: string;
  steps: number | null;
  distanceMeters: number | null;
  activeCalories: number | null;
  activeMinutes: number | null;
  workoutMinutes: number | null;
  workoutCount: number | null;
  workoutsByType: Record<string, WorkoutTypeTotal>;
  floors: number | null;
  mindfulMinutes: number | null;
  sleep: SleepSummary | null;
  shared: MetricId[];
  sources: string[];
  manualExcluded: boolean;
  /** ISO-8601 instant. */
  computedAt: string;
}

export const METRIC_IDS = [
  "steps",
  "distance",
  "active_calories",
  "active_minutes",
  "workout_minutes",
  "workout_count",
  "floors",
  "sleep_minutes",
  "bedtime",
  "wake_time",
  "mindful_minutes",
] as const;

export type MetricId = (typeof METRIC_IDS)[number];

export type ChallengeKind = "COMPETE" | "TARGET" | "TEAM";
export type Aggregate = "SUM" | "AVERAGE" | "DAYS_HIT" | "BEST_DAY" | "LONGEST_STREAK";
export type Renderer = "LEADERBOARD" | "PROGRESS" | "BOARD" | "BOSS" | "ROUTE";

export interface Condition {
  metric: MetricId;
  cmp: "<=" | ">=";
  value: number;
  toleranceMin?: number;
}

export type DailyRule =
  | { op: "value" }
  | { op: "cap"; max: number }
  | { op: "atLeast"; value: number }
  | { op: "all"; conditions: Condition[] };

export interface MetricRef {
  id: MetricId;
  filter?: { workoutTypes?: string[] };
}

/**
 * A challenge is data, not a code path. Every preset (step battle, team goal, sleep pact, …) is
 * one of these; see shared/challenge-templates.json.
 */
export interface ChallengeSpec {
  schemaVersion: 1;
  kind: ChallengeKind;
  metric: MetricRef;
  daily: DailyRule;
  aggregate: Aggregate;
  /** TARGET: per member. TEAM: whole group. */
  goal?: { target: number } | null;
  window: {
    start: string;
    end: string;
    /** Days of week counted, 0 = Sunday ... 6 = Saturday. Missing means every day. */
    days?: number[] | null;
    recurrence?: "WEEKLY" | null;
  };
  ranking?: { tieBreak?: "SUM" | "EARLIEST_FINISH" | null } | null;
  /** Count the days on which every member with data hit the daily rule. */
  groupStreak?: boolean;
  display: {
    templateId: string;
    renderer: Renderer;
    title: string;
    description?: string | null;
    theme?: Record<string, string> | null;
  };
}

/** One member's evaluated position in a challenge. */
export interface MemberResult {
  score: number;
  tieBreak: number;
  /** 0..1 toward the member goal (TARGET) or null. */
  progress: number | null;
  daysHit: number;
  daysWithData: number;
  longestStreak: number;
  finished: boolean;
  /** The member-local date the goal was reached, for EARLIEST_FINISH. */
  finishedOn: string | null;
  /** The member doesn't share the metric this challenge needs. */
  notShared: boolean;
  /** At least one day was over the metric's sanity cap and was left out. */
  flagged: boolean;
}

export type DayStatus = "HIT" | "MISSED" | "NOT_SHARED";

export interface DayOutcome {
  date: string;
  /** Null when the member has no summary for that date. */
  value: number | null;
  status: DayStatus | null;
}
