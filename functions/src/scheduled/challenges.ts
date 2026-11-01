import { onSchedule } from "firebase-functions/v2/scheduler";
import { db } from "../lib/firebase";
import { asChallenge } from "../lib/models";
import { finalizeChallenge, refreshSummary } from "../lib/scoring";
import { getChallenge } from "../lib/store";
import { addDays, localDate } from "../domain";

/** Hourly: close challenges whose end date is over everywhere, plus grace. */
export const finalizeChallenges = onSchedule(
  { schedule: "every 60 minutes", timeoutSeconds: 540 },
  async () => {
    const latestEnd = addDays(localDate(new Date(), "UTC"), -1);
    const snap = await db
      .collectionGroup("challenges")
      .where("status", "==", "ACTIVE")
      .where("endDate", "<=", latestEnd)
      .get();
    for (const doc of snap.docs) {
      const groupId = doc.ref.parent.parent?.id;
      const challenge = groupId ? asChallenge(groupId, doc.id, doc.data()) : null;
      if (challenge) await finalizeChallenge(challenge);
    }
  },
);

/**
 * Every 2 minutes: refresh summaries that changed since their last refresh. Together with the
 * throttle in scoring.ts this caps summary writes at one per challenge per window.
 */
export const refreshDirtySummaries = onSchedule(
  { schedule: "every 2 minutes", timeoutSeconds: 120 },
  async () => {
    const snap = await db.collectionGroup("summary").where("dirty", "==", true).limit(200).get();
    for (const doc of snap.docs) {
      const challengeDoc = doc.ref.parent.parent;
      const groupId = challengeDoc?.parent.parent?.id;
      if (!challengeDoc || !groupId) continue;
      const challenge = await getChallenge(groupId, challengeDoc.id);
      if (challenge) await refreshSummary(challenge);
      else await doc.ref.set({ dirty: false }, { merge: true });
    }
  },
);
