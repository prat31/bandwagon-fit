import { onSchedule } from "firebase-functions/v2/scheduler";
import { db } from "../lib/firebase";
import { sendPushToUsers } from "../lib/push";
import { activeChallenges, challengeRef, getUserTimeZone } from "../lib/store";
import { isActiveOn, localDate } from "../domain";

/**
 * 09:00 local: "last night, 812 of 1,040 kept the pact". Users are picked by their precomputed
 * UTC half-hour slot, so the work scales with users due now, not with group sizes.
 */
export const morningDigest = onSchedule(
  { schedule: "every 30 minutes", timeoutSeconds: 540 },
  async () => {
    const now = new Date();
    const slot = Math.floor((now.getUTCHours() * 60 + now.getUTCMinutes()) / 30);
    const users = await db.collection("users").where("digestSlotUtc", "==", slot).get();

    for (const user of users.docs) {
      const settings = (user.get("settings") ?? {}) as Record<string, unknown>;
      if (settings.morningDigest === false) continue;
      const memberships = await user.ref.collection("memberships").get();
      for (const membership of memberships.docs) {
        const today = localDate(now, await getUserTimeZone(user.id));
        const boards = (await activeChallenges(membership.id)).filter(
          (c) => c.spec.display.renderer === "BOARD" && isActiveOn(c.spec, today),
        );
        for (const challenge of boards) {
          const day = await challengeRef(challenge.groupId, challenge.id).collection("days").doc(today).get();
          if (!day.exists) continue;
          const hit = Number(day.get("hit") ?? 0);
          const members = Number(day.get("members") ?? 0);
          await sendPushToUsers([user.id], {
            title: challenge.spec.display.title,
            body: `${hit} of ${members} kept it last night 😴`,
            channelId: "results",
            type: "sleepDigest",
            route: `bandwagon://groups/${challenge.groupId}/challenges/${challenge.id}`,
            data: { groupId: challenge.groupId, challengeId: challenge.id },
          });
        }
      }
    }
  },
);
