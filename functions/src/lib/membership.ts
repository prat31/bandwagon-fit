import { randomBytes } from "node:crypto";
import { HttpsError } from "firebase-functions/v2/https";
import { FieldValue, db } from "./firebase";
import {
  accessRef,
  forEachAccess,
  getGroup,
  getUser,
  groupRef,
  memberRef,
  membershipRef,
} from "./store";
import { asRole, type GroupRecord, type Identity, type Role } from "./models";
import { writeFeedEvent } from "./push";
import { cleanDisplayName } from "../domain/identity";
import { localDate } from "../domain/dayRanges";
import { inviteProblem } from "./invites";

/** Groups this big collapse "X joined" feed events into one per day. */
export const QUIET_JOIN_THRESHOLD = 25;

/** Random per-membership id. Never the uid, so nothing in a group can be joined to a uid. */
export function newMemberId(): string {
  return randomBytes(10).toString("hex");
}

/** What other members see about someone: their public profile, or only their alias. */
export function memberIdentity(identity: Identity, user: Record<string, unknown> | null) {
  if (identity === "ANONYMOUS") {
    const alias = (user?.alias ?? {}) as Record<string, unknown>;
    return {
      alias: typeof alias.name === "string" ? alias.name : null,
      avatar: alias.avatar ?? null,
    };
  }
  return {
    username: typeof user?.username === "string" ? user.username : null,
    displayName: cleanDisplayName(user?.displayName),
    photoUrl: typeof user?.photoUrl === "string" ? user.photoUrl : null,
  };
}

/** A user may join an anonymous group only with an alias, and any group only with a username. */
export function requireIdentityFor(identity: Identity, user: Record<string, unknown> | null): void {
  if (typeof user?.username !== "string") {
    throw new HttpsError("failed-precondition", "Choose a username first.");
  }
  const alias = (user?.alias ?? null) as Record<string, unknown> | null;
  if (identity === "ANONYMOUS" && typeof alias?.name !== "string") {
    throw new HttpsError("failed-precondition", "ALIAS_REQUIRED");
  }
}

export interface JoinOutcome {
  groupId: string;
  memberId: string;
  alreadyMember: boolean;
}

/**
 * The only way anyone becomes a member. Writes the server-only access doc, the member doc other
 * members read, and the user's own membership doc, then posts a feed event.
 */
export async function addMember(
  groupId: string,
  uid: string,
  role: Role,
  options: { inviteCode?: string } = {},
): Promise<JoinOutcome> {
  const outcome = await db.runTransaction(async (tx) => {
    const groupSnap = await tx.get(groupRef(groupId));
    if (!groupSnap.exists || groupSnap.get("status") !== "ACTIVE") {
      throw new HttpsError("not-found", "That group no longer exists.");
    }
    const existing = await tx.get(accessRef(groupId, uid));
    if (existing.exists) {
      return { groupId, memberId: String(existing.get("memberId")), alreadyMember: true, memberCount: 0 };
    }
    const banned = await tx.get(groupRef(groupId).collection("bans").doc(uid));
    if (banned.exists) throw new HttpsError("permission-denied", "You can't join this group.");

    let inviteSnap = null;
    if (options.inviteCode) {
      const ref = db.collection("invites").doc(options.inviteCode);
      inviteSnap = await tx.get(ref);
      const problem = inviteProblem(inviteSnap.data(), groupId);
      if (problem) throw new HttpsError("failed-precondition", problem);
    }

    const userSnap = await tx.get(db.collection("users").doc(uid));
    const user = userSnap.data() ?? null;
    const identity: Identity = groupSnap.get("identity") === "ANONYMOUS" ? "ANONYMOUS" : "NAMED";
    requireIdentityFor(identity, user);

    const memberId = newMemberId();
    const now = new Date();
    tx.set(accessRef(groupId, uid), { memberId, role, joinedAt: now });
    tx.set(memberRef(groupId, memberId), { role, joinedAt: now, ...memberIdentity(identity, user) });
    tx.set(membershipRef(uid, groupId), {
      groupId,
      memberId,
      role,
      identity,
      groupName: groupSnap.get("name") ?? "",
      joinedAt: now,
    });
    tx.update(groupRef(groupId), { memberCount: FieldValue.increment(1), updatedAt: now });
    if (inviteSnap?.exists) tx.update(inviteSnap.ref, { uses: FieldValue.increment(1) });
    return {
      groupId,
      memberId,
      alreadyMember: false,
      memberCount: Number(groupSnap.get("memberCount") ?? 0) + 1,
    };
  });

  if (!outcome.alreadyMember && role !== "OWNER") {
    if (outcome.memberCount > QUIET_JOIN_THRESHOLD) {
      await bumpDailyJoins(groupId);
    } else {
      await writeFeedEvent(groupId, { type: "MEMBER_JOINED", actorMemberId: outcome.memberId });
    }
  }
  return { groupId: outcome.groupId, memberId: outcome.memberId, alreadyMember: outcome.alreadyMember };
}

/** One "N people joined today" event per day instead of hundreds of single events. */
async function bumpDailyJoins(groupId: string): Promise<void> {
  const date = localDate(new Date(), "UTC");
  const ref = groupRef(groupId).collection("feed").doc(`joins_${date}`);
  await db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    const count = Number(snap.get("data.count") ?? 0) + 1;
    tx.set(ref, {
      type: "MEMBERS_JOINED",
      actorMemberId: null,
      message: null,
      data: { count },
      createdAt: snap.exists ? snap.get("createdAt") : new Date(),
    });
  });
}

/** The member who inherits ownership: longest-serving mod, else longest-serving member. */
async function successor(groupId: string, excludeUid: string): Promise<{ uid: string; memberId: string } | null> {
  for (const role of ["MOD", "MEMBER"]) {
    const snap = await groupRef(groupId)
      .collection("access")
      .where("role", "==", role)
      .orderBy("joinedAt")
      .limit(2)
      .get();
    const doc = snap.docs.find((d) => d.id !== excludeUid);
    if (doc) return { uid: doc.id, memberId: String(doc.get("memberId")) };
  }
  return null;
}

/** Changes a member's role in all three places it is stored. */
export async function writeRole(
  groupId: string,
  uid: string,
  memberId: string,
  role: Role,
): Promise<void> {
  const batch = db.batch();
  batch.update(accessRef(groupId, uid), { role });
  batch.update(memberRef(groupId, memberId), { role });
  batch.set(membershipRef(uid, groupId), { role }, { merge: true });
  await batch.commit();
}

/**
 * Removes a member. An owner leaving hands the group to a successor (owners leaving on purpose
 * must transfer first; this path is for account deletion and removal). The last member leaving
 * deletes the group.
 */
export async function removeMember(
  groupId: string,
  uid: string,
  options: { ban?: boolean; reason?: "LEFT" | "REMOVED" | "ACCOUNT_DELETED" } = {},
): Promise<void> {
  const access = await accessRef(groupId, uid).get();
  if (!access.exists) return;
  const memberId = String(access.get("memberId"));
  const role = asRole(access.get("role"));

  if (role === "OWNER") {
    const next = await successor(groupId, uid);
    if (!next) {
      await deleteGroupDeep(groupId);
      return;
    }
    await writeRole(groupId, next.uid, next.memberId, "OWNER");
    await writeFeedEvent(groupId, { type: "OWNER_CHANGED", actorMemberId: next.memberId });
  }

  // One transaction, re-checking the access doc: two removals racing (leave while being removed,
  // a double tap) delete the member once and decrement the count once.
  const removed = await db.runTransaction(async (tx) => {
    const current = await tx.get(accessRef(groupId, uid));
    if (!current.exists) return false;
    tx.delete(accessRef(groupId, uid));
    tx.delete(memberRef(groupId, memberId));
    tx.delete(membershipRef(uid, groupId));
    tx.update(groupRef(groupId), { memberCount: FieldValue.increment(-1), updatedAt: new Date() });
    if (options.ban) tx.set(groupRef(groupId).collection("bans").doc(uid), { bannedAt: new Date() });
    return true;
  });
  if (!removed) return;

  // Their scores leave every challenge so leaderboards and team totals stay honest.
  const challenges = await groupRef(groupId).collection("challenges").get();
  for (const challenge of challenges.docs) {
    const score = await challenge.ref.collection("scores").doc(memberId).get();
    if (!score.exists) continue;
    const contribution = Number(score.get("contribution") ?? 0);
    const writes = db.batch();
    writes.delete(score.ref);
    if (contribution !== 0) {
      writes.set(
        challenge.ref.collection("totals").doc(`shard_${shardFor(memberId)}`),
        { value: FieldValue.increment(-contribution) },
        { merge: true },
      );
    }
    writes.set(challenge.ref.collection("summary").doc("current"), { dirty: true }, { merge: true });
    await writes.commit();
  }
}

export const TOTAL_SHARDS = 20;

/** A member always writes to the same shard, so their contribution can be moved exactly. */
export function shardFor(memberId: string): number {
  let hash = 0;
  for (const char of memberId) hash = (hash * 31 + char.charCodeAt(0)) >>> 0;
  return hash % TOTAL_SHARDS;
}

/** Deletes a group with everything under it, its invites and every member's membership doc. */
export async function deleteGroupDeep(groupId: string): Promise<void> {
  await forEachAccess(groupId, async (page) => {
    const batch = db.batch();
    page.forEach((a) => batch.delete(membershipRef(a.uid, groupId)));
    await batch.commit();
  });
  const invites = await db.collection("invites").where("groupId", "==", groupId).get();
  if (!invites.empty) {
    const batch = db.batch();
    invites.docs.forEach((doc) => batch.delete(doc.ref));
    await batch.commit();
  }
  await db.recursiveDelete(groupRef(groupId));
}

/** Copies a user's current public identity (or alias) into the member docs of their groups. */
export async function refreshMemberIdentity(uid: string, which: Identity): Promise<void> {
  const user = await getUser(uid);
  const memberships = await db
    .collection("users")
    .doc(uid)
    .collection("memberships")
    .where("identity", "==", which)
    .get();
  for (let i = 0; i < memberships.docs.length; i += 400) {
    const batch = db.batch();
    for (const doc of memberships.docs.slice(i, i + 400)) {
      const memberId = String(doc.get("memberId"));
      batch.set(memberRef(doc.id, memberId), memberIdentity(which, user), { merge: true });
    }
    await batch.commit();
  }
}

export async function requireActiveGroup(groupId: string): Promise<GroupRecord> {
  const group = await getGroup(groupId);
  if (!group || group.status !== "ACTIVE") throw new HttpsError("not-found", "That group no longer exists.");
  return group;
}
