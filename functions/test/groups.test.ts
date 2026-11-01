/**
 * Group behaviour end to end: the real callables against the Firestore and Auth emulators.
 * Run with `npm run test:groups` (starts throwaway emulators on spare ports, see emulators.json).
 */
import { test, beforeEach } from "node:test";
import assert from "node:assert/strict";
import { getAuth } from "firebase-admin/auth";
import { db } from "../src/lib/firebase";
import {
  createGroup,
  deleteGroup,
  joinGroup,
  leaveGroup,
  removeMember,
  setRole,
  transferOwnership,
  updateGroup,
} from "../src/callable/groups";
import {
  createInvite,
  inviteByUsername,
  listInvites,
  previewInvite,
  redeemInvite,
  respondToInvitation,
  revokeInvite,
} from "../src/callable/invites";
import { deleteAccount } from "../src/callable/account";
import { removeMember as removeFromGroup } from "../src/lib/membership";

const PROJECT = process.env.GCLOUD_PROJECT ?? "demo-bandwagon-test";

// --- Helpers -------------------------------------------------------------------------------

// eslint-disable-next-line @typescript-eslint/no-explicit-any
type Callable = { run: (request: any) => any };

function call<T = Record<string, unknown>>(fn: Callable, uid: string | null, data: Record<string, unknown> = {}): Promise<T> {
  return Promise.resolve(fn.run({ data, auth: uid ? { uid, token: {} } : undefined, rawRequest: {} }));
}

/** Asserts the call fails with this HttpsError code (and, optionally, message). */
async function fails(promise: Promise<unknown>, code: string, message?: RegExp) {
  await assert.rejects(promise, (error: { code?: string; message?: string }) => {
    assert.equal(error.code, code, `expected ${code}, got ${error.code}: ${error.message}`);
    if (message) assert.match(error.message ?? "", message);
    return true;
  });
}

async function user(uid: string, opts: { username?: string | null; alias?: string } = {}) {
  const username = opts.username === undefined ? uid : opts.username;
  await db.collection("users").doc(uid).set({
    displayName: uid[0].toUpperCase() + uid.slice(1),
    tz: "UTC",
    ...(username ? { username } : {}),
    ...(opts.alias ? { alias: { name: opts.alias, avatar: { shape: "circle", color: "teal" } } } : {}),
  });
  if (username) await db.collection("usernames").doc(username).set({ uid, claimedAt: new Date() });
}

const group = async (id: string) => (await db.collection("groups").doc(id).get()).data();
const access = async (groupId: string, uid: string) => (await db.doc(`groups/${groupId}/access/${uid}`).get()).data();
const membership = async (uid: string, groupId: string) => (await db.doc(`users/${uid}/memberships/${groupId}`).get()).data();
const inbox = async (uid: string, groupId: string) => (await db.doc(`users/${uid}/inbox/${groupId}`).get()).exists;

async function newGroup(owner: string, extra: Record<string, unknown> = {}) {
  return call<{ groupId: string; memberId: string }>(createGroup, owner, { name: "Morning Walkers", ...extra });
}

async function inviteCode(groupId: string, by: string, extra: Record<string, unknown> = {}) {
  return (await call<{ code: string }>(createInvite, by, { groupId, maxUses: null, expiresInDays: 7, ...extra })).code;
}

async function joinWithLink(groupId: string, owner: string, ...uids: string[]) {
  const code = await inviteCode(groupId, owner);
  for (const uid of uids) await call(redeemInvite, uid, { code });
}

beforeEach(async () => {
  // Wipe the emulator so every test starts empty.
  const host = process.env.FIRESTORE_EMULATOR_HOST;
  await fetch(`http://${host}/emulator/v1/projects/${PROJECT}/databases/(default)/documents`, { method: "DELETE" });
  await user("olivia");
  await user("max");
  await user("nia");
  await user("sam");
});

// --- Creating groups -------------------------------------------------------------------------

test("creating a group makes the caller its owner everywhere membership is stored", async () => {
  const { groupId, memberId } = await newGroup("olivia", { description: "Before work", tags: ["walking"] });
  const g = await group(groupId);
  assert.equal(g?.memberCount, 1);
  assert.equal(g?.status, "ACTIVE");
  assert.equal(g?.identity, "NAMED");
  assert.equal(g?.visibility, "INVITE_ONLY");
  assert.equal((await access(groupId, "olivia"))?.role, "OWNER");
  assert.equal((await membership("olivia", groupId))?.groupName, "Morning Walkers");
  const member = (await db.doc(`groups/${groupId}/members/${memberId}`).get()).data();
  assert.equal(member?.username, "olivia");
  assert.notEqual(memberId, "olivia", "member ids never expose the uid");
});

test("creating a group needs sign-in, a username, a valid name, and an alias for anonymous groups", async () => {
  await fails(call(createGroup, null, { name: "X group" }), "unauthenticated");
  await user("nousername", { username: null });
  await fails(call(createGroup, "nousername", { name: "Walkers" }), "failed-precondition", /username/);
  await fails(call(createGroup, "olivia", { name: "" }), "invalid-argument");
  await fails(call(createGroup, "olivia", { name: "Anon", identity: "ANONYMOUS" }), "failed-precondition", /ALIAS_REQUIRED/);
  await user("ali", { alias: "quietfox" });
  const { groupId } = await call<{ groupId: string }>(createGroup, "ali", { name: "Anon", identity: "ANONYMOUS" });
  assert.equal((await group(groupId))?.identity, "ANONYMOUS");
});

test("group creation is rate limited to five a day", async () => {
  for (let i = 0; i < 5; i += 1) await newGroup("olivia", { name: `Group ${i}` });
  await fails(newGroup("olivia", { name: "One too many" }), "resource-exhausted");
});

// --- Invite links -----------------------------------------------------------------------------

test("an invite link previews the group without its members, then joins", async () => {
  const { groupId } = await newGroup("olivia");
  const code = await inviteCode(groupId, "olivia");
  const preview = await call<Record<string, unknown>>(previewInvite, "max", { code });
  assert.equal(preview.name, "Morning Walkers");
  assert.equal(preview.memberCount, 1);
  assert.equal(preview.members, undefined);

  const outcome = await call<{ alreadyMember: boolean }>(redeemInvite, "max", { code: code.toLowerCase() });
  assert.equal(outcome.alreadyMember, false);
  assert.equal((await group(groupId))?.memberCount, 2);
  assert.equal((await access(groupId, "max"))?.role, "MEMBER");
  assert.equal((await membership("max", groupId))?.role, "MEMBER");
  assert.equal((await db.doc(`invites/${code}`).get()).get("uses"), 1);
});

test("redeeming twice is harmless: no second member, no extra use", async () => {
  const { groupId } = await newGroup("olivia");
  const code = await inviteCode(groupId, "olivia", { maxUses: 5 });
  await call(redeemInvite, "max", { code });
  const again = await call<{ alreadyMember: boolean }>(redeemInvite, "max", { code });
  assert.equal(again.alreadyMember, true);
  assert.equal((await group(groupId))?.memberCount, 2);
  assert.equal((await db.doc(`invites/${code}`).get()).get("uses"), 1);
});

test("unusable invites are refused with a clear reason", async () => {
  const { groupId } = await newGroup("olivia");
  await fails(call(redeemInvite, "max", { code: "BAD" }), "invalid-argument");
  await fails(call(redeemInvite, "max", { code: "ZZZZZZ" }), "failed-precondition", /no longer exists/);

  const single = await inviteCode(groupId, "olivia", { maxUses: 1 });
  await call(redeemInvite, "max", { code: single });
  await fails(call(redeemInvite, "nia", { code: single }), "failed-precondition", /used up/);

  const revoked = await inviteCode(groupId, "olivia");
  await call(revokeInvite, "olivia", { code: revoked });
  await fails(call(redeemInvite, "nia", { code: revoked }), "failed-precondition", /turned off/);

  const expired = await inviteCode(groupId, "olivia");
  await db.doc(`invites/${expired}`).update({ expiresAt: new Date(Date.now() - 1000) });
  await fails(call(previewInvite, "nia", { code: expired }), "failed-precondition", /expired/);
});

test("a one-use invite redeemed by two people at once lets exactly one in", async () => {
  const { groupId } = await newGroup("olivia");
  const code = await inviteCode(groupId, "olivia", { maxUses: 1 });
  const results = await Promise.allSettled([call(redeemInvite, "max", { code }), call(redeemInvite, "nia", { code })]);
  assert.equal(results.filter((r) => r.status === "fulfilled").length, 1);
  assert.equal((await group(groupId))?.memberCount, 2);
});

test("in invite-only groups only the owner and mods make links; in public groups anyone can", async () => {
  const { groupId } = await newGroup("olivia");
  await joinWithLink(groupId, "olivia", "max");
  await fails(call(createInvite, "max", { groupId }), "permission-denied");
  await fails(call(createInvite, "sam", { groupId }), "permission-denied", /not in this group/);
  await fails(call(listInvites, "max", { groupId }), "permission-denied");

  const pub = await newGroup("olivia", { name: "Open Walkers", visibility: "PUBLIC" });
  await call(joinGroup, "max", { groupId: pub.groupId });
  const { code } = await call<{ code: string }>(createInvite, "max", { groupId: pub.groupId });
  assert.match(code, /^[A-Z0-9]{6}$/);
});

test("guessing invite codes is rate limited", async () => {
  for (let i = 0; i < 30; i += 1) await fails(call(previewInvite, "sam", { code: "ZZZZZZ" }), "failed-precondition");
  await fails(call(previewInvite, "sam", { code: "ZZZZZZ" }), "resource-exhausted");
});

// --- Public groups --------------------------------------------------------------------------

test("anyone can join a public group from Discover, but not an invite-only one", async () => {
  const pub = await newGroup("olivia", { name: "Open Walkers", visibility: "PUBLIC" });
  await call(joinGroup, "max", { groupId: pub.groupId });
  assert.equal((await group(pub.groupId))?.memberCount, 2);

  const priv = await newGroup("olivia", { name: "Close Friends" });
  await fails(call(joinGroup, "max", { groupId: priv.groupId }), "permission-denied", /invite only/);
  await fails(call(joinGroup, "max", { groupId: "missing" }), "not-found");
});

// --- Invites by username --------------------------------------------------------------------

test("an invite by username lands in the inbox and can be accepted or declined", async () => {
  const { groupId } = await newGroup("olivia");
  await call(inviteByUsername, "olivia", { groupId, username: "@Max" });
  assert.equal(await inbox("max", groupId), true);
  const accepted = await call<{ joined: boolean }>(respondToInvitation, "max", { groupId, accept: true });
  assert.equal(accepted.joined, true);
  assert.equal(await inbox("max", groupId), false);
  assert.equal((await group(groupId))?.memberCount, 2);

  await call(inviteByUsername, "olivia", { groupId, username: "nia" });
  const declined = await call<{ joined: boolean }>(respondToInvitation, "nia", { groupId, accept: false });
  assert.equal(declined.joined, false);
  assert.equal(await inbox("nia", groupId), false);
  assert.equal(await access(groupId, "nia"), undefined);
});

test("username invites reject unknown names, yourself and existing members", async () => {
  const { groupId } = await newGroup("olivia");
  await joinWithLink(groupId, "olivia", "max");
  await fails(call(inviteByUsername, "olivia", { groupId, username: "nobody" }), "not-found");
  await fails(call(inviteByUsername, "olivia", { groupId, username: "olivia" }), "invalid-argument");
  await fails(call(inviteByUsername, "olivia", { groupId, username: "max" }), "already-exists");
  await fails(call(respondToInvitation, "sam", { groupId, accept: true }), "not-found");
});

test("anonymous groups never reveal whether someone is already a member", async () => {
  await user("ali", { alias: "quietfox" });
  await user("bea", { alias: "boldorca" });
  const { groupId } = await call<{ groupId: string }>(createGroup, "ali", { name: "Anon", identity: "ANONYMOUS" });
  await joinWithLink(groupId, "ali", "bea");
  const result = await call<{ ok: boolean }>(inviteByUsername, "ali", { groupId, username: "bea" });
  assert.equal(result.ok, true);
  assert.equal(await inbox("bea", groupId), false);

  const members = await db.collection(`groups/${groupId}/members`).get();
  for (const doc of members.docs) {
    assert.equal(doc.get("username"), undefined, "anonymous members show no username");
    assert.equal(doc.get("displayName"), undefined, "anonymous members show no name");
    assert.ok(doc.get("alias"));
  }
  // No alias, no entry.
  await fails(call(redeemInvite, "sam", { code: await inviteCode(groupId, "ali") }), "failed-precondition", /ALIAS_REQUIRED/);
});

// --- Roles ------------------------------------------------------------------------------------

test("only the owner changes roles, and ownership moves with a transfer", async () => {
  const { groupId } = await newGroup("olivia");
  await joinWithLink(groupId, "olivia", "max", "nia");
  const maxId = (await access(groupId, "max"))?.memberId;
  const oliviaId = (await access(groupId, "olivia"))?.memberId;

  await fails(call(setRole, "max", { groupId, memberId: maxId, role: "MOD" }), "permission-denied");
  await call(setRole, "olivia", { groupId, memberId: maxId, role: "MOD" });
  assert.equal((await access(groupId, "max"))?.role, "MOD");
  assert.equal((await membership("max", groupId))?.role, "MOD");
  assert.equal((await db.doc(`groups/${groupId}/members/${maxId}`).get()).get("role"), "MOD");
  await fails(call(setRole, "olivia", { groupId, memberId: oliviaId, role: "MEMBER" }), "invalid-argument");
  await fails(call(setRole, "olivia", { groupId, memberId: "nope", role: "MOD" }), "not-found");

  await fails(call(transferOwnership, "olivia", { groupId, memberId: oliviaId }), "not-found");
  await call(transferOwnership, "olivia", { groupId, memberId: maxId });
  assert.equal((await access(groupId, "max"))?.role, "OWNER");
  assert.equal((await access(groupId, "olivia"))?.role, "MOD");
  await fails(call(deleteGroup, "olivia", { groupId }), "permission-denied");
});

test("owners remove anyone, mods remove plain members only, nobody removes themselves", async () => {
  const { groupId } = await newGroup("olivia");
  await joinWithLink(groupId, "olivia", "max", "nia", "sam");
  const id = async (uid: string) => (await access(groupId, uid))?.memberId;
  await call(setRole, "olivia", { groupId, memberId: await id("max"), role: "MOD" });
  await call(setRole, "olivia", { groupId, memberId: await id("nia"), role: "MOD" });

  await fails(call(removeMember, "sam", { groupId, memberId: await id("nia") }), "permission-denied");
  await fails(call(removeMember, "max", { groupId, memberId: await id("nia") }), "permission-denied");
  await fails(call(removeMember, "max", { groupId, memberId: await id("olivia") }), "permission-denied");
  await fails(call(removeMember, "olivia", { groupId, memberId: await id("olivia") }), "invalid-argument");

  await call(removeMember, "max", { groupId, memberId: await id("sam") });
  await call(removeMember, "olivia", { groupId, memberId: await id("nia") });
  assert.equal((await group(groupId))?.memberCount, 2);
  assert.equal(await membership("sam", groupId), undefined);
});

// --- Bans ---------------------------------------------------------------------------------------

test("a banned member can't come back by link, Discover or username invite", async () => {
  const { groupId } = await newGroup("olivia", { visibility: "PUBLIC" });
  await joinWithLink(groupId, "olivia", "max");
  // An invitation sent before the ban.
  await db.doc(`users/max/inbox/${groupId}`).set({ groupId, groupName: "Morning Walkers", createdAt: new Date() });
  await call(removeMember, "olivia", { groupId, memberId: (await access(groupId, "max"))?.memberId, ban: true });

  await fails(call(redeemInvite, "max", { code: await inviteCode(groupId, "olivia") }), "permission-denied");
  await fails(call(joinGroup, "max", { groupId }), "permission-denied");
  // The stale invitation can't be accepted, and is cleared rather than failing forever.
  await fails(call(respondToInvitation, "max", { groupId, accept: true }), "permission-denied");
  assert.equal(await inbox("max", groupId), false);
  // A new invitation is "sent" but never delivered, so the ban isn't revealed.
  const sent = await call<{ ok: boolean }>(inviteByUsername, "olivia", { groupId, username: "max" });
  assert.equal(sent.ok, true);
  assert.equal(await inbox("max", groupId), false);
});

// --- Leaving and deleting -----------------------------------------------------------------------

test("members leave freely; an owner must hand over first unless they're alone", async () => {
  const { groupId } = await newGroup("olivia");
  await joinWithLink(groupId, "olivia", "max");
  await fails(call(leaveGroup, "olivia", { groupId }), "failed-precondition", /owner/);
  const left = await call<{ deleted: boolean }>(leaveGroup, "max", { groupId });
  assert.equal(left.deleted, false);
  assert.equal((await group(groupId))?.memberCount, 1);
  assert.equal(await membership("max", groupId), undefined);
  await fails(call(leaveGroup, "max", { groupId }), "permission-denied");

  const code = await inviteCode(groupId, "olivia");
  const last = await call<{ deleted: boolean }>(leaveGroup, "olivia", { groupId });
  assert.equal(last.deleted, true);
  assert.equal(await group(groupId), undefined);
  assert.equal((await db.doc(`invites/${code}`).get()).exists, false);
});

test("leaving and being removed at the same moment counts the member out once", async () => {
  const { groupId } = await newGroup("olivia");
  await joinWithLink(groupId, "olivia", "max", "nia");
  // Straight at the shared removal path, so both runs really overlap.
  await Promise.all([
    removeFromGroup(groupId, "max", { reason: "LEFT" }),
    removeFromGroup(groupId, "max", { reason: "REMOVED" }),
    removeFromGroup(groupId, "max", { reason: "REMOVED" }),
  ]);
  assert.equal((await group(groupId))?.memberCount, 2);
  assert.equal(await access(groupId, "max"), undefined);
});

test("deleting a group removes it for every member, with its invites", async () => {
  const { groupId } = await newGroup("olivia");
  await joinWithLink(groupId, "olivia", "max", "nia");
  await fails(call(deleteGroup, "max", { groupId }), "permission-denied");
  await call(deleteGroup, "olivia", { groupId });
  assert.equal(await group(groupId), undefined);
  for (const uid of ["olivia", "max", "nia"]) assert.equal(await membership(uid, groupId), undefined);
  assert.equal((await db.collection("invites").where("groupId", "==", groupId).get()).size, 0);
  // An invitation to the deleted group is cleared when the invitee tries it.
  await db.doc(`users/sam/inbox/${groupId}`).set({ groupId, createdAt: new Date() });
  await fails(call(respondToInvitation, "sam", { groupId, accept: true }), "not-found");
  assert.equal(await inbox("sam", groupId), false);
});

test("deleting your account hands your groups to the longest-serving mod", async () => {
  await getAuth().createUser({ uid: "olivia" });
  const { groupId } = await newGroup("olivia");
  await joinWithLink(groupId, "olivia", "max", "nia");
  await call(setRole, "olivia", { groupId, memberId: (await access(groupId, "nia"))?.memberId, role: "MOD" });
  await call(deleteAccount, "olivia");
  assert.equal((await access(groupId, "nia"))?.role, "OWNER");
  assert.equal(await access(groupId, "olivia"), undefined);
  assert.equal((await group(groupId))?.memberCount, 2);
  assert.equal((await db.doc("users/olivia").get()).exists, false);

  // A sole owner deleting their account takes the group with them.
  await getAuth().createUser({ uid: "sam" });
  const solo = await newGroup("sam", { name: "Just Me" });
  await call(deleteAccount, "sam");
  assert.equal(await group(solo.groupId), undefined);
});

// --- Editing --------------------------------------------------------------------------------

test("only the owner edits a group; a rename reaches every member's list, identity never changes", async () => {
  const { groupId } = await newGroup("olivia");
  await joinWithLink(groupId, "olivia", "max");
  await call(setRole, "olivia", { groupId, memberId: (await access(groupId, "max"))?.memberId, role: "MOD" });
  await fails(call(updateGroup, "max", { groupId, name: "Mod's Group" }), "permission-denied");
  await fails(call(updateGroup, "olivia", { groupId, identity: "ANONYMOUS" }), "invalid-argument");
  await call(updateGroup, "olivia", { groupId, name: "Sunrise Walkers", visibility: "PUBLIC" });
  assert.equal((await group(groupId))?.visibility, "PUBLIC");
  assert.equal((await membership("max", groupId))?.groupName, "Sunrise Walkers");
  assert.equal((await group(groupId))?.identity, "NAMED");
});
