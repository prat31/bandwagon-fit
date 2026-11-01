import type {
  ChallengeSpec,
  DailySummary,
  MetricId,
  SleepSummary,
  WorkoutTypeTotal,
} from "../domain/types";
import { isMetricId } from "../domain/metrics";

export type Identity = "NAMED" | "ANONYMOUS";
export type Visibility = "PUBLIC" | "INVITE_ONLY";
export type Role = "OWNER" | "MOD" | "MEMBER";

export interface GroupRecord {
  id: string;
  name: string;
  description: string;
  tags: string[];
  photoUrl: string | null;
  identity: Identity;
  visibility: Visibility;
  memberCount: number;
  status: "ACTIVE" | "DELETED";
}

export interface AccessRecord {
  uid: string;
  memberId: string;
  role: Role;
  joinedAt: Date | null;
}

export interface ChallengeRecord {
  id: string;
  groupId: string;
  spec: ChallengeSpec;
  status: "ACTIVE" | "COMPLETED" | "CANCELLED";
  createdByMemberId: string | null;
}

type DocData = Record<string, unknown>;

function numberOrNull(value: unknown): number | null {
  return typeof value === "number" && Number.isFinite(value) ? value : null;
}

function stringArray(value: unknown): string[] {
  return Array.isArray(value) ? value.filter((v): v is string => typeof v === "string") : [];
}

export function asSleep(value: unknown): SleepSummary | null {
  if (!value || typeof value !== "object") return null;
  const data = value as DocData;
  const start = typeof data.start === "string" ? data.start : null;
  const end = typeof data.end === "string" ? data.end : null;
  const durationMinutes = numberOrNull(data.durationMinutes);
  const zoneId = typeof data.zoneId === "string" ? data.zoneId : null;
  if (!start || !end || durationMinutes === null || !zoneId) return null;
  return { start, end, durationMinutes, zoneId };
}

function asWorkouts(value: unknown): Record<string, WorkoutTypeTotal> {
  if (!value || typeof value !== "object") return {};
  const out: Record<string, WorkoutTypeTotal> = {};
  for (const [type, raw] of Object.entries(value as DocData)) {
    if (!raw || typeof raw !== "object") continue;
    const entry = raw as DocData;
    out[type] = {
      minutes: numberOrNull(entry.minutes) ?? 0,
      count: numberOrNull(entry.count) ?? 0,
      distanceMeters: numberOrNull(entry.distanceMeters),
    };
  }
  return out;
}

/**
 * v1 summaries had no `shared` list; treat any metric with a value as shared so old rows still
 * score instead of showing "not shared".
 */
function inferShared(data: DocData, sleep: SleepSummary | null): MetricId[] {
  const shared: MetricId[] = [];
  if (numberOrNull(data.steps) !== null) shared.push("steps");
  if (numberOrNull(data.workoutMinutes) !== null) shared.push("workout_minutes", "workout_count");
  if (sleep) shared.push("sleep_minutes");
  return shared;
}

export function asDailySummary(id: string, data: DocData | undefined): DailySummary | null {
  if (!data) return null;
  const sleep = asSleep(data.sleep);
  const shared = Array.isArray(data.shared)
    ? (data.shared as unknown[]).filter(isMetricId)
    : inferShared(data, sleep);
  return {
    userId: typeof data.userId === "string" ? data.userId : "",
    date: typeof data.date === "string" ? data.date : id,
    timeZone: typeof data.timeZone === "string" ? data.timeZone : "UTC",
    steps: numberOrNull(data.steps),
    distanceMeters: numberOrNull(data.distanceMeters),
    activeCalories: numberOrNull(data.activeCalories),
    activeMinutes: numberOrNull(data.activeMinutes),
    workoutMinutes: numberOrNull(data.workoutMinutes),
    workoutCount: numberOrNull(data.workoutCount),
    workoutsByType: asWorkouts(data.workoutsByType),
    floors: numberOrNull(data.floors),
    mindfulMinutes: numberOrNull(data.mindfulMinutes),
    sleep,
    shared,
    sources: stringArray(data.sources),
    manualExcluded: data.manualExcluded === true,
    computedAt: typeof data.computedAt === "string" ? data.computedAt : "",
  };
}

export function asGroup(id: string, data: DocData | undefined): GroupRecord | null {
  if (!data) return null;
  return {
    id,
    name: typeof data.name === "string" ? data.name : "",
    description: typeof data.description === "string" ? data.description : "",
    tags: stringArray(data.tags),
    photoUrl: typeof data.photoUrl === "string" ? data.photoUrl : null,
    identity: data.identity === "ANONYMOUS" ? "ANONYMOUS" : "NAMED",
    visibility: data.visibility === "PUBLIC" ? "PUBLIC" : "INVITE_ONLY",
    memberCount: numberOrNull(data.memberCount) ?? 0,
    status: data.status === "DELETED" ? "DELETED" : "ACTIVE",
  };
}

export function asRole(value: unknown): Role {
  return value === "OWNER" || value === "MOD" ? value : "MEMBER";
}

export function asChallenge(
  groupId: string,
  id: string,
  data: DocData | undefined,
): ChallengeRecord | null {
  if (!data || !data.spec) return null;
  const status = data.status === "COMPLETED" || data.status === "CANCELLED" ? data.status : "ACTIVE";
  return {
    id,
    groupId,
    spec: data.spec as ChallengeSpec,
    status,
    createdByMemberId: typeof data.createdByMemberId === "string" ? data.createdByMemberId : null,
  };
}
