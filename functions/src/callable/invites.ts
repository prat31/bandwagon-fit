import { onCall, HttpsError } from "firebase-functions/v2/https";
import { db } from "../lib/firebase";
import { createInviteForGroup, inviteProblem, isInviteCode } from "../lib/invites";
import { addMember, requireActiveGroup } from "../lib/membership";
import { DAY, HOUR, enforceRateLimit } from "../lib/rateLimit";
import { scoreNewMember } from "../lib/scoring";
import { sendPushToUsers } from "../lib/push";
import { getAccess, groupRef, requireAccess } from "../lib/store";
import { toIso } from "../lib/time";
import { cleanDisplayName, normaliseHandle } from "../domain/identity";

/** Joining needs one or two lookups; this stops anyone guessing codes by brute force. */
const INVITE_LOOKUPS_PER_HOUR = 30;

function requireUid(auth: { uid: string } | undefined): string {
  if (!auth?.uid) throw new HttpsError("unauthenticated", "Sign in first.");
  return auth.uid;
}

/** Who may invite: owner and mods in invite-only groups, anyone in a public group. */
async function requireInviter(groupId: string, uid: string) {
  const group = await requireActiveGroup(groupId);
  const roles: Array<"OWNER" | "MOD" | "MEMBER"> =
    group.visibility === "PUBLIC" ? ["OWNER", "MOD", "MEMBER"] : ["OWNER", "MOD"];
  const access = await requireAccess(groupId, uid, roles);
  return { group, access };
}

export const createInvite = onCall(async (request) => {
  const uid = requireUid(request.auth);
  const groupId = String(request.data?.groupId ?? "");
  const { access } = await requireInviter(groupId, uid);
  const rawUses = request.data?.maxUses;
  const maxUses = rawUses === null || rawUses === undefined ? null : Math.max(1, Math.min(10_000, Number(rawUses) || 1));
  const rawDays = request.data?.expiresInDays;
  const expiresInDays = rawDays === null ? null : [1, 7, 30].includes(Number(rawDays)) ? Number(rawDays) : 7;
  return createInviteForGroup(groupId, access.memberId, uid, maxUses, expiresInDays);
});

/** Invite codes are secrets, so clients list them through the server, owner and mods only. */
export const listInvites = onCall(async (request) => {
  const uid = requireUid(request.auth);
  const groupId = String(request.data?.groupId ?? "");
  await requireAccess(groupId, uid, ["OWNER", "MOD"]);
  const snap = await db.collection("invites").where("groupId", "==", groupId).get();
  return {
    invites: snap.docs
      .filter((doc) => doc.get("revoked") !== true)
      .map((doc) => ({
        code: doc.id,
        link: `https://bandwagon.fit/i/${doc.id}`,
        maxUses: doc.get("maxUses") ?? null,
        uses: doc.get("uses") ?? 0,
        expiresAt: toIso(doc.get("expiresAt")),
        usable: inviteProblem(doc.data(), groupId) === null,
      })),
  };
});

export const revokeInvite = onCall(async (request) => {
  const uid = requireUid(request.auth);
  const code = String(request.data?.code ?? "").toUpperCase();
  const snap = await db.collection("invites").doc(code).get();
  if (!snap.exists) throw new HttpsError("not-found", "That invite no longer exists.");
  await requireAccess(String(snap.get("groupId")), uid, ["OWNER", "MOD"]);
  await snap.ref.update({ revoked: true, revokedAt: new Date() });
  return { ok: true };
});

/** Preview before joining: what the group is, never who is in it. */
export const previewInvite = onCall(async (request) => {
  const uid = requireUid(request.auth);
  await enforceRateLimit(uid, "inviteLookup", INVITE_LOOKUPS_PER_HOUR, HOUR, "Too many invite codes tried. Try again in a while.");
  const code = String(request.data?.code ?? "").trim().toUpperCase();
  if (!isInviteCode(code)) throw new HttpsError("invalid-argument", "That invite code is not valid.");
  const invite = await db.collection("invites").doc(code).get();
  const problem = inviteProblem(invite.data(), null);
  if (problem) throw new HttpsError("failed-precondition", problem);
  const group = await requireActiveGroup(String(invite.get("groupId")));
  return {
    groupId: group.id,
    name: group.name,
    description: group.description,
    identity: group.identity,
    visibility: group.visibility,
    memberCount: group.memberCount,
    photoUrl: group.photoUrl,
  };
});

export const redeemInvite = onCall(async (request) => {
  const uid = requireUid(request.auth);
  await enforceRateLimit(uid, "inviteLookup", INVITE_LOOKUPS_PER_HOUR, HOUR, "Too many invite codes tried. Try again in a while.");
  const code = String(request.data?.code ?? "").trim().toUpperCase();
  if (!isInviteCode(code)) throw new HttpsError("invalid-argument", "That invite code is not valid.");
  const invite = await db.collection("invites").doc(code).get();
  const problem = inviteProblem(invite.data(), null);
  if (problem) throw new HttpsError("failed-precondition", problem);
  const groupId = String(invite.get("groupId"));
  const outcome = await addMember(groupId, uid, "MEMBER", { inviteCode: code });
  if (!outcome.alreadyMember) await scoreNewMember(groupId, uid, outcome.memberId);
  return outcome;
});

/**
 * In-app invite to an exact username. The invitee accepts or declines from their inbox. In an
 * anonymous group the inviter is told only that the invite was sent, never whether it was accepted.
 */
export const inviteByUsername = onCall(async (request) => {
  const uid = requireUid(request.auth);
  const groupId = String(request.data?.groupId ?? "");
  const username = normaliseHandle(request.data?.username);
  const { group, access } = await requireInviter(groupId, uid);
  await enforceRateLimit(uid, "inviteByUsername", 50, DAY);

  const nameSnap = await db.collection("usernames").doc(username).get();
  if (!nameSnap.exists || nameSnap.get("heldUntil")) {
    throw new HttpsError("not-found", "No one has that username.");
  }
  const inviteeUid = String(nameSnap.get("uid"));
  if (inviteeUid === uid) throw new HttpsError("invalid-argument", "That's you.");
  if (await getAccess(groupId, inviteeUid)) {
    if (group.identity === "ANONYMOUS") return { ok: true };
    throw new HttpsError("already-exists", "They're already in this group.");
  }
  // A banned user could never accept. Say "sent" so the ban isn't revealed, and send nothing.
  if ((await groupRef(groupId).collection("bans").doc(inviteeUid).get()).exists) return { ok: true };

  const inviter = await db.collection("users").doc(uid).get();
  const inbox = db.collection("users").doc(inviteeUid).collection("inbox").doc(groupId);
  await inbox.set({
    groupId,
    groupName: group.name,
    identity: group.identity,
    visibility: group.visibility,
    memberCount: group.memberCount,
    invitedBy: group.identity === "ANONYMOUS" ? null : cleanDisplayName(inviter.get("displayName")),
    invitedByMemberId: access.memberId,
    createdAt: new Date(),
  });
  await sendPushToUsers([inviteeUid], {
    title: "Group invite",
    body:
      group.identity === "ANONYMOUS"
        ? `You're invited to the anonymous group ${group.name}.`
        : `${cleanDisplayName(inviter.get("displayName"))} invited you to ${group.name}.`,
    channelId: "groups",
    type: "invites",
    route: "bandwagon://groups",
    data: { groupId },
  });
  return { ok: true };
});

export const respondToInvitation = onCall(async (request) => {
  const uid = requireUid(request.auth);
  const groupId = String(request.data?.groupId ?? "");
  const accept = request.data?.accept === true;
  const inbox = db.collection("users").doc(uid).collection("inbox").doc(groupId);
  const snap = await inbox.get();
  if (!snap.exists) throw new HttpsError("not-found", "That invite is no longer there.");
  if (!accept) {
    await inbox.delete();
    return { ok: true, joined: false };
  }
  let outcome;
  try {
    outcome = await addMember(groupId, uid, "MEMBER");
  } catch (error) {
    // Group deleted or the user banned since the invite: it can never be accepted, so clear it.
    const code = (error as HttpsError).code;
    if (code === "not-found" || code === "permission-denied") await inbox.delete();
    throw error;
  }
  await inbox.delete();
  if (!outcome.alreadyMember) await scoreNewMember(groupId, uid, outcome.memberId);
  return { ok: true, joined: true, ...outcome };
});
