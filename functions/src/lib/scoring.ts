import { FieldValue, db } from "./firebase";
import {
  activeChallenges,
  challengeRef,
  forEachAccess,
  getGroup,
  getUser,
  loadDailyRange,
} from "./store";
import type { ChallengeRecord } from "./models";
import { shardFor, TOTAL_SHARDS } from "./membership";
import { sendPushToGroup, writeFeedEvent } from "./push";
import { toDate } from "./time";
import {
  addDays,
  endInstant,
  evaluateMember,
  groupStreak,
  isActiveOn,
  localDate,
  milestonesCrossed,
  nextWeeklyWindow,
  pct,
  rank,
  type DayCounts,
} from "../domain";

/**
 * A busy challenge refreshes its summary at most once per window, however many members sync.
 * One Firestore document only sustains about one write per second, so this is what lets a group
 * grow without a size cap. The emulator refreshes on every write so local testing stays snappy.
 */
export const SUMMARY_WINDOW_MS = process.env.FUNCTIONS_EMULATOR === "true" ? 0 : 2 * 60 * 1000;
export const TOP_ROWS = 10;
const BOARD_LOOKBACK_DAYS = 3;

const summaryRef = (groupId: string, challengeId: string) =>
  challengeRef(groupId, challengeId).collection("summary").doc("current");

function tracksDays(challenge: ChallengeRecord): boolean {
  return challenge.spec.groupStreak === true || challenge.spec.display.renderer === "BOARD";
}

/** Re-evaluates one member across the whole window and writes only their own documents. */
export async function scoreMember(
  challenge: ChallengeRecord,
  uid: string,
  memberId: string,
  options: { refresh?: boolean } = {},
): Promise<void> {
  const { spec } = challenge;
  const [summaries, user] = await Promise.all([
    loadDailyRange(uid, spec.window.start, spec.window.end),
    getUser(uid),
  ]);
  const { result, days } = evaluateMember(spec, summaries);
  const ref = challengeRef(challenge.groupId, challenge.id);
  const scoreRef = ref.collection("scores").doc(memberId);
  const contribution = spec.kind === "TEAM" ? result.score : 0;

  await db.runTransaction(async (tx) => {
    const previous = await tx.get(scoreRef);
    const delta = contribution - Number(previous.get("contribution") ?? 0);
    tx.set(scoreRef, {
      memberId,
      ...result,
      contribution,
      lastSyncAt: user?.lastSyncAt ?? null,
      updatedAt: new Date(),
    });
    if (delta !== 0) {
      tx.set(
        ref.collection("totals").doc(`shard_${shardFor(memberId)}`),
        { value: FieldValue.increment(delta) },
        { merge: true },
      );
    }
  });

  if (tracksDays(challenge)) {
    const shareBedtime = spec.display.theme?.shareBedtime === "true";
    const byDate = new Map(summaries.map((s) => [s.date, s]));
    const batch = db.batch();
    for (const day of days) {
      const row = ref.collection("days").doc(day.date).collection("rows").doc(memberId);
      if (day.status === null) {
        batch.delete(row);
        continue;
      }
      const sleep = byDate.get(day.date)?.sleep;
      batch.set(row, {
        status: day.status,
        bedtime: shareBedtime && sleep ? sleep.start : null,
        zoneId: shareBedtime && sleep ? sleep.zoneId : null,
      });
    }
    await batch.commit();
  }

  if (options.refresh !== false) await requestRefresh(challenge);
}

/** Refreshes now if the last refresh is old enough, otherwise marks the summary dirty once. */
export async function requestRefresh(challenge: ChallengeRecord): Promise<void> {
  const ref = summaryRef(challenge.groupId, challenge.id);
  const snap = await ref.get();
  const refreshedAt = toDate(snap.get("refreshedAt"));
  if (!refreshedAt || Date.now() - refreshedAt.getTime() >= SUMMARY_WINDOW_MS) {
    await refreshSummary(challenge);
  } else if (snap.get("dirty") !== true) {
    await ref.set({ dirty: true }, { merge: true });
  }
}

async function countWhere(query: FirebaseFirestore.Query): Promise<number> {
  const snap = await query.count().get();
  return snap.data().count;
}

/** Rebuilds the summary from indexed queries; the cost doesn't grow with the group. */
export async function refreshSummary(challenge: ChallengeRecord): Promise<void> {
  const { spec } = challenge;
  const ref = challengeRef(challenge.groupId, challenge.id);
  const group = await getGroup(challenge.groupId);
  const members = group?.memberCount ?? 0;
  const scores = ref.collection("scores");

  const topSnap = await scores
    .orderBy("score", "desc")
    .orderBy("tieBreak", "desc")
    .limit(TOP_ROWS)
    .get();
  const top = rank(
    topSnap.docs.map((doc) => ({
      memberId: doc.id,
      score: Number(doc.get("score") ?? 0),
      tieBreak: Number(doc.get("tieBreak") ?? 0),
      finished: doc.get("finished") === true,
      notShared: doc.get("notShared") === true,
    })),
  );

  const [scored, finishedCount] = await Promise.all([
    countWhere(scores),
    spec.kind === "TARGET" ? countWhere(scores.where("finished", "==", true)) : Promise.resolve(0),
  ]);

  let total: number | null = null;
  if (spec.kind === "TEAM") {
    const shards = await ref.collection("totals").get();
    total = shards.docs.reduce((sum, doc) => sum + Number(doc.get("value") ?? 0), 0);
  }

  let streak: { current: number; best: number } | null = null;
  if (tracksDays(challenge)) streak = await refreshDays(challenge, members);

  const target = spec.goal?.target ?? null;
  const percentage = total !== null && target ? pct(total, target) : null;
  const summary = summaryRef(challenge.groupId, challenge.id);

  const crossed = await db.runTransaction(async (tx) => {
    const previous = await tx.get(summary);
    const hit: number[] = Array.isArray(previous.get("milestonesHit"))
      ? (previous.get("milestonesHit") as number[])
      : [];
    const newly = percentage === null ? [] : milestonesCrossed(hit, percentage);
    tx.set(
      summary,
      {
        kind: spec.kind,
        top,
        participants: members,
        scored,
        finishedCount,
        total,
        target,
        pct: percentage,
        milestonesHit: [...hit, ...newly],
        streak,
        refreshedAt: new Date(),
        dirty: false,
      },
      { merge: true },
    );
    return newly;
  });

  for (const milestone of crossed) {
    await writeFeedEvent(challenge.groupId, {
      type: "MILESTONE",
      message: `${spec.display.title} reached ${milestone}% of its goal`,
      data: { challengeId: challenge.id, milestone },
    });
    await sendPushToGroup(challenge.groupId, {
      title: `${spec.display.title} is at ${milestone}%`,
      body: milestone === 100 ? "Goal reached! Every contribution counted." : "Keep going together!",
      channelId: "results",
      type: "milestones",
      route: `bandwagon://groups/${challenge.groupId}/challenges/${challenge.id}`,
      data: { groupId: challenge.groupId, challengeId: challenge.id },
    });
  }
}

/** Counts HIT/MISSED rows for the last few dates and derives the group streak from day docs. */
async function refreshDays(
  challenge: ChallengeRecord,
  members: number,
): Promise<{ current: number; best: number }> {
  const ref = challengeRef(challenge.groupId, challenge.id);
  const today = localDate(new Date(), "UTC");
  const dates: string[] = [];
  for (let offset = -BOARD_LOOKBACK_DAYS; offset <= 1; offset += 1) {
    const date = addDays(today, offset);
    if (isActiveOn(challenge.spec, date)) dates.push(date);
  }
  for (const date of dates) {
    const rows = ref.collection("days").doc(date).collection("rows");
    const [hit, missed] = await Promise.all([
      countWhere(rows.where("status", "==", "HIT")),
      countWhere(rows.where("status", "==", "MISSED")),
    ]);
    await ref.collection("days").doc(date).set({ date, hit, missed, members }, { merge: true });
  }
  const history = await ref.collection("days").orderBy("date", "desc").limit(60).get();
  const counts: DayCounts[] = history.docs
    .map((doc) => ({
      date: String(doc.get("date")),
      hit: Number(doc.get("hit") ?? 0),
      missed: Number(doc.get("missed") ?? 0),
      members: Number(doc.get("members") ?? members),
    }))
    .filter((d) => d.date < today || d.hit + d.missed > 0)
    .reverse();
  return groupStreak(counts);
}

/** Called for every uploaded daily summary: scores the member in each active challenge covering it. */
export async function scoreForUserDate(uid: string, date: string): Promise<void> {
  const memberships = await db.collection("users").doc(uid).collection("memberships").get();
  for (const membership of memberships.docs) {
    const memberId = String(membership.get("memberId"));
    const challenges = await activeChallenges(membership.id);
    for (const challenge of challenges) {
      if (isActiveOn(challenge.spec, date)) await scoreMember(challenge, uid, memberId);
    }
  }
}

/** Scores a newly joined member in every active challenge of the group. */
export async function scoreNewMember(groupId: string, uid: string, memberId: string): Promise<void> {
  for (const challenge of await activeChallenges(groupId)) {
    await scoreMember(challenge, uid, memberId);
  }
}

/** Everyone is enrolled in a new challenge, so score each member once from data already uploaded. */
export async function scoreAllMembers(challenge: ChallengeRecord): Promise<void> {
  await forEachAccess(challenge.groupId, async (page) => {
    for (let i = 0; i < page.length; i += 20) {
      await Promise.all(
        page.slice(i, i + 20).map((a) => scoreMember(challenge, a.uid, a.memberId, { refresh: false })),
      );
    }
  });
  await refreshSummary(challenge);
}

/** Closes a challenge once its end date has passed everywhere plus grace; repeats weekly ones. */
export async function finalizeChallenge(challenge: ChallengeRecord, now: Date = new Date()): Promise<boolean> {
  if (challenge.status !== "ACTIVE") return false;
  if (now.getTime() < endInstant(challenge.spec.window.end).getTime()) return false;

  await refreshSummary(challenge);
  const ref = challengeRef(challenge.groupId, challenge.id);
  const summary = await ref.collection("summary").doc("current").get();
  const top = (summary.get("top") as Array<{ memberId: string; rank: number }> | undefined) ?? [];
  const winners = challenge.spec.kind === "COMPETE" ? top.filter((r) => r.rank === 1).map((r) => r.memberId) : [];

  await ref.set({ status: "COMPLETED", completedAt: now }, { merge: true });
  await ref.collection("summary").doc("current").set({ finalised: true }, { merge: true });
  await writeFeedEvent(challenge.groupId, {
    type: "CHALLENGE_ENDED",
    message: `${challenge.spec.display.title} is over`,
    data: { challengeId: challenge.id, winners },
  });
  await sendPushToGroup(challenge.groupId, {
    title: `${challenge.spec.display.title} is over`,
    body: "See the final results.",
    channelId: "results",
    type: "results",
    route: `bandwagon://groups/${challenge.groupId}/challenges/${challenge.id}`,
    data: { groupId: challenge.groupId, challengeId: challenge.id },
  });

  if (challenge.spec.window.recurrence === "WEEKLY") {
    const spec = { ...challenge.spec, window: nextWeeklyWindow(challenge.spec) };
    await ref.parent.add({
      spec,
      status: "ACTIVE",
      createdByMemberId: challenge.createdByMemberId,
      previousId: challenge.id,
      startDate: spec.window.start,
      endDate: spec.window.end,
      createdAt: now,
    });
  }
  return true;
}

export { TOTAL_SHARDS };
