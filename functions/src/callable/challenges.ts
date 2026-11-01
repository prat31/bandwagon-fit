import { onCall, HttpsError } from "firebase-functions/v2/https";
import { challengeRef, getChallenge, groupRef, requireAccess } from "../lib/store";
import { requireActiveGroup } from "../lib/membership";
import { scoreAllMembers } from "../lib/scoring";
import { sendPushToGroup, writeFeedEvent } from "../lib/push";
import { localDate, validateSpec, type ChallengeSpec } from "../domain";

function requireUid(auth: { uid: string } | undefined): string {
  if (!auth?.uid) throw new HttpsError("unauthenticated", "Sign in first.");
  return auth.uid;
}

function cleanSpec(input: unknown): ChallengeSpec {
  const problem = validateSpec(input);
  if (problem) throw new HttpsError("invalid-argument", problem);
  const spec = input as ChallengeSpec;
  return {
    ...spec,
    display: { ...spec.display, title: spec.display.title.trim() },
  };
}

/**
 * Owner and mods create challenges. Every member is enrolled by being in the group, so nothing is
 * written per member here beyond scoring data they have already uploaded.
 */
export const createChallenge = onCall({ timeoutSeconds: 540 }, async (request) => {
  const uid = requireUid(request.auth);
  const groupId = String(request.data?.groupId ?? "");
  const access = await requireAccess(groupId, uid, ["OWNER", "MOD"]);
  const group = await requireActiveGroup(groupId);
  const spec = cleanSpec(request.data?.spec);
  // Over everywhere on Earth already (the last time zone to finish is UTC-12).
  if (spec.window.end < localDate(new Date(), "Etc/GMT+12")) {
    throw new HttpsError("invalid-argument", "That challenge would already be over.");
  }

  const ref = groupRef(groupId).collection("challenges").doc();
  await ref.set({
    spec,
    status: "ACTIVE",
    createdByMemberId: access.memberId,
    startDate: spec.window.start,
    endDate: spec.window.end,
    createdAt: new Date(),
  });
  await writeFeedEvent(groupId, {
    type: "CHALLENGE_CREATED",
    actorMemberId: access.memberId,
    message: spec.display.title,
    data: { challengeId: ref.id },
  });
  await scoreAllMembers({
    id: ref.id,
    groupId,
    spec,
    status: "ACTIVE",
    createdByMemberId: access.memberId,
  });
  await sendPushToGroup(
    groupId,
    {
      title: group.name,
      body: `New challenge: ${spec.display.title}`,
      channelId: "groups",
      type: "newChallenges",
      route: `bandwagon://groups/${groupId}/challenges/${ref.id}`,
      data: { groupId, challengeId: ref.id },
    },
    { exceptUid: uid },
  );
  return { challengeId: ref.id };
});

/** Settings can change until the challenge starts; after that only cancelling is allowed. */
export const updateChallenge = onCall({ timeoutSeconds: 540 }, async (request) => {
  const uid = requireUid(request.auth);
  const groupId = String(request.data?.groupId ?? "");
  const challengeId = String(request.data?.challengeId ?? "");
  await requireAccess(groupId, uid, ["OWNER", "MOD"]);
  const challenge = await getChallenge(groupId, challengeId);
  if (!challenge || challenge.status !== "ACTIVE") throw new HttpsError("not-found", "Challenge not found.");
  // Started somewhere on Earth already: too late to change the rules.
  if (localDate(new Date(), "Etc/GMT-14") >= challenge.spec.window.start) {
    throw new HttpsError("failed-precondition", "This challenge has started, so it can't be edited.");
  }
  const spec = cleanSpec(request.data?.spec);
  await challengeRef(groupId, challengeId).update({
    spec,
    startDate: spec.window.start,
    endDate: spec.window.end,
    updatedAt: new Date(),
  });
  await scoreAllMembers({ ...challenge, spec });
  return { ok: true };
});

export const cancelChallenge = onCall(async (request) => {
  const uid = requireUid(request.auth);
  const groupId = String(request.data?.groupId ?? "");
  const challengeId = String(request.data?.challengeId ?? "");
  const access = await requireAccess(groupId, uid, ["OWNER", "MOD"]);
  const challenge = await getChallenge(groupId, challengeId);
  if (!challenge || challenge.status !== "ACTIVE") throw new HttpsError("not-found", "Challenge not found.");
  await challengeRef(groupId, challengeId).update({ status: "CANCELLED", cancelledAt: new Date() });
  await writeFeedEvent(groupId, {
    type: "CHALLENGE_CANCELLED",
    actorMemberId: access.memberId,
    message: challenge.spec.display.title,
    data: { challengeId },
  });
  return { ok: true };
});
