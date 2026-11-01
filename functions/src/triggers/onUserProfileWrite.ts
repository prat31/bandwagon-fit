import { onDocumentWritten } from "firebase-functions/v2/firestore";
import { db } from "../lib/firebase";
import { refreshMemberIdentity } from "../lib/membership";
import { cleanDisplayName } from "../domain/identity";
import { zonedTimeToUtc, localDate, parseDate } from "../domain/dayRanges";

/** Which half-hour of the UTC day is 09:00 for this user, for the morning digest. */
export function digestSlotUtc(timeZone: string, now: Date = new Date()): number {
  const { year, month, day } = parseDate(localDate(now, timeZone));
  const nine = zonedTimeToUtc(year, month, day, 9, 0, timeZone);
  return Math.floor((nine.getUTCHours() * 60 + nine.getUTCMinutes()) / 30);
}

/**
 * Display name, photo and "About me" are edited by the user on their own profile. Copy them to the public
 * profile and into member docs of their named groups. Anonymous groups never see these fields.
 */
export const onUserProfileWrite = onDocumentWritten("users/{uid}", async (event) => {
  const uid = event.params.uid;
  const before = event.data?.before.data();
  const after = event.data?.after.data();
  if (!after) return; // deletion is handled by deleteAccount

  if (typeof after.tz === "string" && (before?.tz !== after.tz || after.digestSlotUtc === undefined)) {
    try {
      await db.collection("users").doc(uid).set({ digestSlotUtc: digestSlotUtc(after.tz) }, { merge: true });
    } catch {
      // An invalid zone id just means no digest.
    }
  }

  const changed = before?.displayName !== after.displayName || before?.photoUrl !== after.photoUrl ||
    before?.about !== after.about;
  if (!changed || typeof after.username !== "string") return;
  await db.collection("publicProfiles").doc(uid).set(
    {
      username: after.username,
      displayName: cleanDisplayName(after.displayName),
      photoUrl: typeof after.photoUrl === "string" ? after.photoUrl : null,
      about: typeof after.about === "string" ? after.about : null,
      updatedAt: new Date(),
    },
    { merge: true },
  );
  await refreshMemberIdentity(uid, "NAMED");
});
