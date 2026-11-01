import { onCall, HttpsError } from "firebase-functions/v2/https";
import { db } from "../lib/firebase";
import { refreshMemberIdentity } from "../lib/membership";
import { DAY, HOUR, enforceRateLimit } from "../lib/rateLimit";
import { toDate } from "../lib/time";
import { cleanAvatar, cleanDisplayName, handleProblem, normaliseHandle } from "../domain/identity";

const CHANGE_COOLDOWN_MS = 30 * DAY;

function requireUid(auth: { uid: string } | undefined): string {
  if (!auth?.uid) throw new HttpsError("unauthenticated", "Sign in first.");
  return auth.uid;
}

/** A name is free if nobody holds it, or its previous owner's 30-day hold has run out. */
function heldByOther(data: Record<string, unknown> | undefined, uid: string, now: Date): boolean {
  if (!data) return false;
  if (data.uid === uid) return false;
  const heldUntil = toDate(data.heldUntil);
  return heldUntil === null || heldUntil.getTime() > now.getTime();
}

/**
 * Usernames are unique and case-insensitive. A renamed account's old name stays reserved for 30
 * days so nobody can pick it up and impersonate them straight away.
 */
export const claimUsername = onCall(async (request) => {
  const uid = requireUid(request.auth);
  const username = normaliseHandle(request.data?.username);
  const problem = handleProblem(username);
  if (problem) throw new HttpsError("invalid-argument", problem);

  const now = new Date();
  await db.runTransaction(async (tx) => {
    const userRef = db.collection("users").doc(uid);
    const [nameSnap, aliasSnap, userSnap] = await Promise.all([
      tx.get(db.collection("usernames").doc(username)),
      tx.get(db.collection("aliases").doc(username)),
      tx.get(userRef),
    ]);
    const current = userSnap.get("username");
    if (current === username) return;
    if (heldByOther(nameSnap.data(), uid, now) || aliasSnap.exists) {
      throw new HttpsError("already-exists", "That username is taken.");
    }
    const changedAt = toDate(userSnap.get("usernameChangedAt"));
    if (typeof current === "string" && changedAt && now.getTime() - changedAt.getTime() < CHANGE_COOLDOWN_MS) {
      throw new HttpsError("failed-precondition", "You can change your username once every 30 days.");
    }

    tx.set(db.collection("usernames").doc(username), { uid, claimedAt: now });
    if (typeof current === "string") {
      tx.set(db.collection("usernames").doc(current), {
        uid,
        heldUntil: new Date(now.getTime() + CHANGE_COOLDOWN_MS),
      });
    }
    tx.set(userRef, { username, usernameChangedAt: now, updatedAt: now }, { merge: true });
    tx.set(
      db.collection("publicProfiles").doc(uid),
      {
        username,
        displayName: cleanDisplayName(userSnap.get("displayName")),
        photoUrl: userSnap.get("photoUrl") ?? null,
        about: userSnap.get("about") ?? null,
        updatedAt: now,
      },
      { merge: true },
    );
  });

  await refreshMemberIdentity(uid, "NAMED");
  return { username };
});

/**
 * One alias per user, shown in every anonymous group. It is stored without any uid that a client
 * can read: `aliases/{name}` only says the name is taken, `aliasOwners/{name}` is server-only.
 */
export const setAlias = onCall(async (request) => {
  const uid = requireUid(request.auth);
  const alias = normaliseHandle(request.data?.alias);
  const avatar = cleanAvatar(request.data?.avatar);
  const problem = handleProblem(alias);
  if (problem) throw new HttpsError("invalid-argument", problem);

  const now = new Date();
  await db.runTransaction(async (tx) => {
    const userRef = db.collection("users").doc(uid);
    const [ownerSnap, usernameSnap, userSnap] = await Promise.all([
      tx.get(db.collection("aliasOwners").doc(alias)),
      tx.get(db.collection("usernames").doc(alias)),
      tx.get(userRef),
    ]);
    const current = (userSnap.get("alias") ?? null) as { name?: string; changedAt?: unknown } | null;
    const renaming = current?.name !== alias;

    if (renaming) {
      if ((ownerSnap.exists && ownerSnap.get("uid") !== uid) || usernameSnap.exists) {
        throw new HttpsError("already-exists", "That alias is taken.");
      }
      const changedAt = toDate(current?.changedAt);
      if (current?.name && changedAt && now.getTime() - changedAt.getTime() < CHANGE_COOLDOWN_MS) {
        throw new HttpsError("failed-precondition", "You can change your alias once every 30 days.");
      }
      tx.set(db.collection("aliases").doc(alias), { createdAt: now });
      tx.set(db.collection("aliasOwners").doc(alias), { uid, createdAt: now });
      if (current?.name) {
        tx.delete(db.collection("aliases").doc(current.name));
        tx.delete(db.collection("aliasOwners").doc(current.name));
      }
    }
    tx.set(
      userRef,
      {
        alias: { name: alias, avatar, changedAt: renaming ? now : current?.changedAt ?? now },
        updatedAt: now,
      },
      { merge: true },
    );
  });

  await refreshMemberIdentity(uid, "ANONYMOUS");
  return { alias, avatar };
});

/** Exact-match lookup only, rate-limited, so usernames can't be enumerated. Never returns a uid. */
export const findUser = onCall(async (request) => {
  const uid = requireUid(request.auth);
  await enforceRateLimit(uid, "findUser", 30, HOUR, "Too many lookups. Try again in a while.");
  const username = normaliseHandle(request.data?.username);
  if (handleProblem(username)) return { found: false };

  const nameSnap = await db.collection("usernames").doc(username).get();
  if (!nameSnap.exists || nameSnap.get("heldUntil")) return { found: false };
  const profile = await db.collection("publicProfiles").doc(String(nameSnap.get("uid"))).get();
  if (!profile.exists) return { found: false };
  return {
    found: true,
    username,
    displayName: cleanDisplayName(profile.get("displayName")),
    photoUrl: profile.get("photoUrl") ?? null,
    about: profile.get("about") ?? null,
  };
});
