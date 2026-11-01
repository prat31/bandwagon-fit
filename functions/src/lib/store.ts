import { HttpsError } from "firebase-functions/v2/https";
import { db } from "./firebase";
import {
  asChallenge,
  asDailySummary,
  asGroup,
  asRole,
  type AccessRecord,
  type ChallengeRecord,
  type GroupRecord,
  type Role,
} from "./models";
import type { DailySummary } from "../domain/types";
import { toDate } from "./time";

export const groupRef = (groupId: string) => db.collection("groups").doc(groupId);
export const accessRef = (groupId: string, uid: string) =>
  groupRef(groupId).collection("access").doc(uid);
export const memberRef = (groupId: string, memberId: string) =>
  groupRef(groupId).collection("members").doc(memberId);
export const challengeRef = (groupId: string, challengeId: string) =>
  groupRef(groupId).collection("challenges").doc(challengeId);
export const membershipRef = (uid: string, groupId: string) =>
  db.collection("users").doc(uid).collection("memberships").doc(groupId);

export async function getGroup(groupId: string): Promise<GroupRecord | null> {
  const snap = await groupRef(groupId).get();
  return asGroup(snap.id, snap.data());
}

export async function getUser(uid: string): Promise<Record<string, unknown> | null> {
  const snap = await db.collection("users").doc(uid).get();
  return snap.data() ?? null;
}

export async function getUserTimeZone(uid: string): Promise<string> {
  const user = await getUser(uid);
  return typeof user?.tz === "string" ? user.tz : "UTC";
}

export async function getAccess(groupId: string, uid: string): Promise<AccessRecord | null> {
  const snap = await accessRef(groupId, uid).get();
  if (!snap.exists) return null;
  return {
    uid,
    memberId: String(snap.get("memberId")),
    role: asRole(snap.get("role")),
    joinedAt: toDate(snap.get("joinedAt")),
  };
}

/** Throws unless the caller is in the group, optionally with one of the given roles. */
export async function requireAccess(
  groupId: string,
  uid: string,
  roles: Role[] = ["OWNER", "MOD", "MEMBER"],
): Promise<AccessRecord> {
  if (!groupId) throw new HttpsError("invalid-argument", "Missing group.");
  const access = await getAccess(groupId, uid);
  if (!access) throw new HttpsError("permission-denied", "You're not in this group.");
  if (!roles.includes(access.role)) {
    throw new HttpsError(
      "permission-denied",
      roles.includes("MOD") ? "Only the owner and mods can do that." : "Only the owner can do that.",
    );
  }
  return access;
}

/** Resolves a member id back to its uid. Server only: clients never see uids of other members. */
export async function accessByMemberId(
  groupId: string,
  memberId: string,
): Promise<AccessRecord | null> {
  const snap = await groupRef(groupId)
    .collection("access")
    .where("memberId", "==", memberId)
    .limit(1)
    .get();
  const doc = snap.docs[0];
  if (!doc) return null;
  return {
    uid: doc.id,
    memberId,
    role: asRole(doc.get("role")),
    joinedAt: toDate(doc.get("joinedAt")),
  };
}

export async function getChallenge(
  groupId: string,
  challengeId: string,
): Promise<ChallengeRecord | null> {
  const snap = await challengeRef(groupId, challengeId).get();
  return asChallenge(groupId, snap.id, snap.data());
}

export async function activeChallenges(groupId: string): Promise<ChallengeRecord[]> {
  const snap = await groupRef(groupId)
    .collection("challenges")
    .where("status", "==", "ACTIVE")
    .get();
  return snap.docs
    .map((doc) => asChallenge(groupId, doc.id, doc.data()))
    .filter((c): c is ChallengeRecord => c !== null);
}

export async function loadDailyRange(
  uid: string,
  startDate: string,
  endDate: string,
): Promise<DailySummary[]> {
  const snap = await db
    .collection("users")
    .doc(uid)
    .collection("daily")
    .where("date", ">=", startDate)
    .where("date", "<=", endDate)
    .get();
  return snap.docs
    .map((doc) => asDailySummary(doc.id, doc.data()))
    .filter((d): d is DailySummary => d !== null)
    .sort((a, b) => a.date.localeCompare(b.date));
}

/** Pages through a group's members (uid + access) without loading the whole group at once. */
export async function forEachAccess(
  groupId: string,
  visit: (page: AccessRecord[]) => Promise<void>,
  pageSize = 500,
): Promise<void> {
  let query = groupRef(groupId).collection("access").orderBy("__name__").limit(pageSize);
  for (;;) {
    const snap = await query.get();
    if (snap.empty) return;
    await visit(
      snap.docs.map((doc) => ({
        uid: doc.id,
        memberId: String(doc.get("memberId")),
        role: asRole(doc.get("role")),
        joinedAt: toDate(doc.get("joinedAt")),
      })),
    );
    if (snap.size < pageSize) return;
    query = query.startAfter(snap.docs[snap.docs.length - 1]);
  }
}
