import { onSchedule } from "firebase-functions/v2/scheduler";
import { db } from "../lib/firebase";

async function deleteOlderThan(
  collectionGroup: string,
  field: string,
  cutoff: Date,
  limit = 500,
): Promise<number> {
  const snap = await db.collectionGroup(collectionGroup).where(field, "<", cutoff).limit(limit).get();
  if (snap.empty) return 0;
  const batch = db.batch();
  snap.docs.forEach((doc) => batch.delete(doc.ref));
  await batch.commit();
  return snap.size;
}

/** Retention: daily summaries 400 days, feed events 90 days, rate-limit counters once expired. */
export const cleanup = onSchedule("every day 03:00", async () => {
  const now = Date.now();
  const day = 24 * 60 * 60 * 1000;

  await deleteOlderThan("daily", "computedAt", new Date(now - 400 * day));
  await deleteOlderThan("feed", "createdAt", new Date(now - 90 * day));
  await deleteOlderThan("rateLimits", "expiresAt", new Date(now));
});
