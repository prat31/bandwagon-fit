import { onCall, HttpsError } from "firebase-functions/v2/https";
import { db } from "../lib/firebase";
import {
  addMember,
  deleteGroupDeep,
  removeMember as removeMemberFromGroup,
  requireActiveGroup,
  requireIdentityFor,
  writeRole,
} from "../lib/membership";
import { DAY, enforceRateLimit } from "../lib/rateLimit";
import { scoreNewMember } from "../lib/scoring";
import { sendPushToUsers, writeFeedEvent } from "../lib/push";
import {
  accessByMemberId,
  forEachAccess,
  getUser,
  groupRef,
  membershipRef,
  requireAccess,
} from "../lib/store";
import type { Identity, Visibility } from "../lib/models";
import { cleanGroupText } from "../domain/identity";

function requireUid(auth: { uid: string } | undefined): string {
  if (!auth?.uid) throw new HttpsError("unauthenticated", "Sign in first.");
  return auth.uid;
}

function photoUrlOf(value: unknown): string | null {
  return typeof value === "string" && /^https:\/\//.test(value) ? value.slice(0, 500) : null;
}

/**
 * Identity (named or anonymous) is chosen here and can never change; nothing else writes it.
 * Visibility can be changed later by the owner.
 */
export const createGroup = onCall(async (request) => {
  const uid = requireUid(request.auth);
  const identity: Identity = request.data?.identity === "ANONYMOUS" ? "ANONYMOUS" : "NAMED";
  const visibility: Visibility = request.data?.visibility === "PUBLIC" ? "PUBLIC" : "INVITE_ONLY";
  const text = cleanGroupText(request.data ?? {});
  if (!text.ok) throw new HttpsError("invalid-argument", text.reason);

  requireIdentityFor(identity, await getUser(uid));
  await enforceRateLimit(uid, "createGroup", 5, DAY, "You can create up to 5 groups a day.");

  const ref = groupRef(db.collection("groups").doc().id);
  const now = new Date();
  await ref.set({
    ...text.value,
    nameLower: text.value.name.toLowerCase(),
    photoUrl: photoUrlOf(request.data?.photoUrl),
    identity,
    visibility,
    memberCount: 0,
    status: "ACTIVE",
    createdAt: now,
    updatedAt: now,
  });
  const { memberId } = await addMember(ref.id, uid, "OWNER");
  await writeFeedEvent(ref.id, { type: "GROUP_CREATED", actorMemberId: memberId });
  return { groupId: ref.id, memberId };
});

/** Owner-only edits. `identity` is deliberately not accepted. */
export const updateGroup = onCall(async (request) => {
  const uid = requireUid(request.auth);
  const groupId = String(request.data?.groupId ?? "");
  await requireAccess(groupId, uid, ["OWNER"]);
  const group = await requireActiveGroup(groupId);
  if ("identity" in (request.data ?? {})) {
    throw new HttpsError("invalid-argument", "A group's identity setting can't be changed.");
  }

  const text = cleanGroupText({
    name: request.data?.name ?? group.name,
    description: request.data?.description ?? group.description,
    tags: request.data?.tags ?? group.tags,
  });
  if (!text.ok) throw new HttpsError("invalid-argument", text.reason);
  const visibility: Visibility =
    request.data?.visibility === "PUBLIC" || request.data?.visibility === "INVITE_ONLY"
      ? request.data.visibility
      : group.visibility;

  await groupRef(groupId).update({
    ...text.value,
    nameLower: text.value.name.toLowerCase(),
    visibility,
    photoUrl: "photoUrl" in (request.data ?? {}) ? photoUrlOf(request.data.photoUrl) : group.photoUrl,
    updatedAt: new Date(),
  });

  if (text.value.name !== group.name) {
    // The group name is copied into members' own membership docs for the "My groups" list.
    await forEachAccess(groupId, async (page) => {
      const batch = db.batch();
      page.forEach((a) => batch.set(membershipRef(a.uid, groupId), { groupName: text.value.name }, { merge: true }));
      await batch.commit();
    });
  }
  return { ok: true };
});

/** Joining a public group from Discover. Invite-only groups need an invite. */
export const joinGroup = onCall(async (request) => {
  const uid = requireUid(request.auth);
  const groupId = String(request.data?.groupId ?? "");
  const group = await requireActiveGroup(groupId);
  if (group.visibility !== "PUBLIC") {
    throw new HttpsError("permission-denied", "This group is invite only.");
  }
  const outcome = await addMember(groupId, uid, "MEMBER");
  if (!outcome.alreadyMember) await scoreNewMember(groupId, uid, outcome.memberId);
  return outcome;
});

/** Owners must hand the group over first, unless they are the only member left. */
export const leaveGroup = onCall(async (request) => {
  const uid = requireUid(request.auth);
  const groupId = String(request.data?.groupId ?? "");
  const access = await requireAccess(groupId, uid);
  if (access.role === "OWNER") {
    const group = await requireActiveGroup(groupId);
    if (group.memberCount > 1) {
      throw new HttpsError("failed-precondition", "Make someone else the owner before you leave.");
    }
    await deleteGroupDeep(groupId);
    return { ok: true, deleted: true };
  }
  await removeMemberFromGroup(groupId, uid, { reason: "LEFT" });
  return { ok: true, deleted: false };
});

export const deleteGroup = onCall(async (request) => {
  const uid = requireUid(request.auth);
  const groupId = String(request.data?.groupId ?? "");
  await requireAccess(groupId, uid, ["OWNER"]);
  await deleteGroupDeep(groupId);
  return { ok: true };
});

/** Owner promotes a member to mod or demotes a mod to member. */
export const setRole = onCall(async (request) => {
  const uid = requireUid(request.auth);
  const groupId = String(request.data?.groupId ?? "");
  const memberId = String(request.data?.memberId ?? "");
  const role = request.data?.role === "MOD" ? "MOD" : "MEMBER";
  await requireAccess(groupId, uid, ["OWNER"]);
  const target = await accessByMemberId(groupId, memberId);
  if (!target) throw new HttpsError("not-found", "That member isn't in the group.");
  if (target.role === "OWNER") throw new HttpsError("invalid-argument", "Transfer ownership instead.");
  if (target.role === role) return { ok: true };

  await writeRole(groupId, target.uid, memberId, role);
  await writeFeedEvent(groupId, {
    type: role === "MOD" ? "MOD_ADDED" : "MOD_REMOVED",
    actorMemberId: memberId,
  });
  const group = await requireActiveGroup(groupId);
  await sendPushToUsers([target.uid], {
    title: group.name,
    body: role === "MOD" ? "You're now a mod of this group." : "You're no longer a mod of this group.",
    channelId: "groups",
    type: "roleChanges",
    route: `bandwagon://groups/${groupId}`,
    data: { groupId },
  });
  return { ok: true };
});

export const transferOwnership = onCall(async (request) => {
  const uid = requireUid(request.auth);
  const groupId = String(request.data?.groupId ?? "");
  const memberId = String(request.data?.memberId ?? "");
  const me = await requireAccess(groupId, uid, ["OWNER"]);
  const target = await accessByMemberId(groupId, memberId);
  if (!target || target.uid === uid) throw new HttpsError("not-found", "Pick another member.");

  await writeRole(groupId, target.uid, target.memberId, "OWNER");
  await writeRole(groupId, uid, me.memberId, "MOD");
  await writeFeedEvent(groupId, { type: "OWNER_CHANGED", actorMemberId: target.memberId });
  const group = await requireActiveGroup(groupId);
  await sendPushToUsers([target.uid], {
    title: group.name,
    body: "You're now the owner of this group.",
    channelId: "groups",
    type: "roleChanges",
    route: `bandwagon://groups/${groupId}`,
    data: { groupId },
  });
  return { ok: true };
});

/** Owners remove anyone; mods remove plain members. Optionally bans them from rejoining. */
export const removeMember = onCall(async (request) => {
  const uid = requireUid(request.auth);
  const groupId = String(request.data?.groupId ?? "");
  const memberId = String(request.data?.memberId ?? "");
  const ban = request.data?.ban === true;
  const me = await requireAccess(groupId, uid, ["OWNER", "MOD"]);
  const target = await accessByMemberId(groupId, memberId);
  if (!target) throw new HttpsError("not-found", "That member isn't in the group.");
  if (target.uid === uid) throw new HttpsError("invalid-argument", "Use Leave group instead.");
  if (target.role === "OWNER" || (me.role === "MOD" && target.role === "MOD")) {
    throw new HttpsError("permission-denied", "You can't remove that member.");
  }
  await removeMemberFromGroup(groupId, target.uid, { ban, reason: "REMOVED" });
  return { ok: true };
});

/** Reports land in a collection only the team reviews. Rate-limited to stop report spam. */
export const reportContent = onCall(async (request) => {
  const uid = requireUid(request.auth);
  const groupId = String(request.data?.groupId ?? "");
  const memberId = typeof request.data?.memberId === "string" ? request.data.memberId : null;
  const reason = String(request.data?.reason ?? "").slice(0, 500);
  if (!groupId) throw new HttpsError("invalid-argument", "Missing group.");
  await enforceRateLimit(uid, "report", 10, DAY);
  const target = memberId ? await accessByMemberId(groupId, memberId) : null;
  await db.collection("reports").add({
    groupId,
    memberId,
    reportedUid: target?.uid ?? null,
    reporterUid: uid,
    reason,
    createdAt: new Date(),
    status: "OPEN",
  });
  return { ok: true };
});
