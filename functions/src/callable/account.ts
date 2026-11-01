import { onCall, HttpsError } from "firebase-functions/v2/https";
import { getAuth } from "firebase-admin/auth";
import { getStorage } from "firebase-admin/storage";
import { db } from "../lib/firebase";
import { removeMember } from "../lib/membership";

async function deleteCollection(path: FirebaseFirestore.CollectionReference): Promise<void> {
  for (;;) {
    const snap = await path.limit(400).get();
    if (snap.empty) return;
    const batch = db.batch();
    snap.docs.forEach((doc) => batch.delete(doc.ref));
    await batch.commit();
  }
}

/** Play Store requirement: in-app account deletion. Ownership moves on; emptied groups go away. */
export const deleteAccount = onCall({ timeoutSeconds: 540 }, async (request) => {
  const uid = request.auth?.uid;
  if (!uid) throw new HttpsError("unauthenticated", "Sign in first.");
  const userRef = db.collection("users").doc(uid);

  const memberships = await userRef.collection("memberships").get();
  for (const membership of memberships.docs) {
    await removeMember(membership.id, uid, { reason: "ACCOUNT_DELETED" });
  }

  const user = await userRef.get();
  const username = user.get("username");
  const alias = user.get("alias.name");
  const batch = db.batch();
  if (typeof username === "string") batch.delete(db.collection("usernames").doc(username));
  if (typeof alias === "string") {
    batch.delete(db.collection("aliases").doc(alias));
    batch.delete(db.collection("aliasOwners").doc(alias));
  }
  batch.delete(db.collection("publicProfiles").doc(uid));
  await batch.commit();

  // Old usernames still held for this account.
  const held = await db.collection("usernames").where("uid", "==", uid).get();
  await Promise.all(held.docs.map((doc) => doc.ref.delete()));

  for (const sub of ["daily", "devices", "memberships", "inbox"]) {
    await deleteCollection(userRef.collection(sub));
  }
  const invites = await db.collection("invites").where("createdBy", "==", uid).get();
  await Promise.all(invites.docs.map((doc) => doc.ref.delete()));

  // The profile photo, if one was uploaded.
  try {
    await getStorage().bucket().deleteFiles({ prefix: `users/${uid}/` });
  } catch {
    // No bucket configured (some local setups) or nothing to delete.
  }

  await userRef.delete();
  await getAuth().deleteUser(uid);
  return { ok: true };
});
