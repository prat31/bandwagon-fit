import { HttpsError } from "firebase-functions/v2/https";
import { db } from "./firebase";

/**
 * Fixed-window counter per user and action. Used for abuse-prone calls: group creation, username
 * lookups, invites by username and reports.
 */
export async function enforceRateLimit(
  uid: string,
  action: string,
  limit: number,
  windowMs: number,
  message = "You're doing that too often. Try again later.",
): Promise<void> {
  const window = Math.floor(Date.now() / windowMs);
  const ref = db.collection("rateLimits").doc(`${uid}_${action}_${window}`);
  const allowed = await db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    const count = Number(snap.get("count") ?? 0);
    if (count >= limit) return false;
    tx.set(ref, {
      count: count + 1,
      expiresAt: new Date((window + 1) * windowMs),
    });
    return true;
  });
  if (!allowed) throw new HttpsError("resource-exhausted", message);
}

export const HOUR = 60 * 60 * 1000;
export const DAY = 24 * HOUR;
