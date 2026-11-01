import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
  type RulesTestEnvironment,
} from "@firebase/rules-unit-testing";
import {
  addDoc,
  collection,
  doc,
  getDoc,
  getDocs,
  query,
  setDoc,
  updateDoc,
  where,
} from "firebase/firestore";

let env: RulesTestEnvironment;

before(async () => {
  env = await initializeTestEnvironment({
    projectId: "bandwagon-rules-test",
    firestore: { rules: readFileSync("firestore.rules", "utf8") },
  });

  // Fixture: an anonymous invite-only group with alice (owner) and bob, and a public named group.
  await env.withSecurityRulesDisabled(async (context) => {
    const db = context.firestore();
    await setDoc(doc(db, "groups/anon"), {
      name: "Night Owls",
      identity: "ANONYMOUS",
      visibility: "INVITE_ONLY",
      status: "ACTIVE",
      memberCount: 2,
    });
    await setDoc(doc(db, "groups/anon/access/alice"), { memberId: "m_alice", role: "OWNER" });
    await setDoc(doc(db, "groups/anon/access/bob"), { memberId: "m_bob", role: "MEMBER" });
    await setDoc(doc(db, "groups/anon/members/m_alice"), { role: "OWNER", alias: "quietfox" });
    await setDoc(doc(db, "groups/anon/members/m_bob"), { role: "MEMBER", alias: "bluejay" });
    await setDoc(doc(db, "groups/anon/challenges/c1"), { spec: {}, status: "ACTIVE" });
    await setDoc(doc(db, "groups/anon/challenges/c1/scores/m_bob"), { score: 10 });
    await setDoc(doc(db, "groups/anon/challenges/c1/summary/current"), { top: [] });

    await setDoc(doc(db, "groups/open"), {
      name: "Morning Walkers",
      identity: "NAMED",
      visibility: "PUBLIC",
      status: "ACTIVE",
      memberCount: 1,
    });
    await setDoc(doc(db, "groups/open/access/carol"), { memberId: "m_carol", role: "OWNER" });
    await setDoc(doc(db, "groups/open/members/m_carol"), { role: "OWNER", username: "carol" });

    await setDoc(doc(db, "users/alice"), { displayName: "Alice", tz: "UTC", username: "alice" });
    await setDoc(doc(db, "users/alice/memberships/anon"), { memberId: "m_alice", role: "OWNER" });
    await setDoc(doc(db, "publicProfiles/alice"), { username: "alice", displayName: "Alice" });
    await setDoc(doc(db, "usernames/alice"), { uid: "alice" });
    await setDoc(doc(db, "aliases/quietfox"), { createdAt: new Date() });
    await setDoc(doc(db, "aliasOwners/quietfox"), { uid: "alice" });
  });
});

after(async () => {
  await env.cleanup();
});

const as = (uid: string) => env.authenticatedContext(uid).firestore();

test("a user can write and read their own daily summary with v2 fields", async () => {
  await assertSucceeds(
    setDoc(doc(as("alice"), "users/alice/daily/2026-11-03"), {
      userId: "alice",
      date: "2026-11-03",
      timeZone: "Asia/Kolkata",
      steps: 8214,
      distanceMeters: 6100,
      activeMinutes: 32,
      shared: ["steps", "distance", "active_minutes"],
      workoutsByType: { RUNNING: { minutes: 20, count: 1 } },
      computedAt: new Date(),
    }),
  );
  await assertSucceeds(getDoc(doc(as("alice"), "users/alice/daily/2026-11-03")));
});

test("daily summaries reject other owners and absurd values", async () => {
  const base = { userId: "alice", date: "2026-11-04", timeZone: "UTC", computedAt: new Date() };
  await assertFails(setDoc(doc(as("alice"), "users/alice/daily/2026-11-04"), { ...base, userId: "bob" }));
  await assertFails(setDoc(doc(as("alice"), "users/alice/daily/2026-11-04"), { ...base, steps: 500000 }));
  await assertFails(setDoc(doc(as("alice"), "users/alice/daily/2026-11-04"), { ...base, floors: -1 }));
});

test("nobody reads another user's raw daily data, not even a fellow member", async () => {
  await assertFails(getDoc(doc(as("bob"), "users/alice/daily/2026-11-03")));
});

test("profiles are private; usernames and aliases can't be written by clients", async () => {
  await assertSucceeds(getDoc(doc(as("alice"), "users/alice")));
  await assertFails(getDoc(doc(as("bob"), "users/alice")));
  await assertFails(getDocs(collection(as("bob"), "users")));
  await assertSucceeds(updateDoc(doc(as("alice"), "users/alice"), { displayName: "Ali" }));
  await assertFails(updateDoc(doc(as("alice"), "users/alice"), { username: "admin" }));
  await assertFails(updateDoc(doc(as("alice"), "users/alice"), { alias: { name: "x" } }));
  await assertFails(setDoc(doc(as("bob"), "usernames/taken"), { uid: "bob" }));
  await assertFails(setDoc(doc(as("bob"), "aliases/sneaky"), { createdAt: new Date() }));
});

test("About me is optional text of at most 160 characters", async () => {
  await assertSucceeds(updateDoc(doc(as("alice"), "users/alice"), { about: "Early riser." }));
  await assertSucceeds(updateDoc(doc(as("alice"), "users/alice"), { about: null }));
  await assertFails(updateDoc(doc(as("alice"), "users/alice"), { about: "x".repeat(161) }));
  await assertFails(updateDoc(doc(as("alice"), "users/alice"), { about: 42 }));
});

test("public profiles are fetchable by id but never listable", async () => {
  await assertSucceeds(getDoc(doc(as("bob"), "publicProfiles/alice")));
  await assertFails(getDocs(collection(as("bob"), "publicProfiles")));
  await assertFails(setDoc(doc(as("bob"), "publicProfiles/bob"), { username: "bob" }));
});

test("alias ownership is never readable, so an alias can't be traced to a uid", async () => {
  await assertSucceeds(getDoc(doc(as("bob"), "aliases/quietfox")));
  await assertFails(getDoc(doc(as("bob"), "aliasOwners/quietfox")));
  await assertFails(getDoc(doc(as("alice"), "aliasOwners/quietfox")));
});

test("members of an anonymous group can't read the uid map or each other's memberships", async () => {
  await assertSucceeds(getDoc(doc(as("bob"), "groups/anon/members/m_alice")));
  await assertFails(getDoc(doc(as("bob"), "groups/anon/access/alice")));
  await assertFails(getDocs(collection(as("bob"), "groups/anon/access")));
  await assertFails(getDoc(doc(as("bob"), "users/alice/memberships/anon")));
  await assertSucceeds(getDoc(doc(as("alice"), "users/alice/memberships/anon")));
});

test("invite-only groups are invisible to non-members; public ones show in Discover", async () => {
  await assertFails(getDoc(doc(as("dave"), "groups/anon")));
  await assertFails(getDocs(collection(as("dave"), "groups/anon/members")));
  await assertSucceeds(getDoc(doc(as("dave"), "groups/open")));
  await assertSucceeds(
    getDocs(query(collection(as("dave"), "groups"), where("visibility", "==", "PUBLIC"), where("status", "==", "ACTIVE"))),
  );
  await assertFails(getDocs(collection(as("dave"), "groups")));
});

test("a public group's members stay hidden until you join", async () => {
  await assertFails(getDocs(collection(as("dave"), "groups/open/members")));
  await assertSucceeds(getDocs(collection(as("carol"), "groups/open/members")));
});

test("no client can create, edit or delete a group, including its identity", async () => {
  await assertFails(setDoc(doc(as("alice"), "groups/mine"), { name: "Mine", identity: "NAMED" }));
  await assertFails(updateDoc(doc(as("alice"), "groups/anon"), { identity: "NAMED" }));
  await assertFails(updateDoc(doc(as("alice"), "groups/anon"), { name: "Renamed" }));
  await assertFails(setDoc(doc(as("alice"), "groups/anon/access/dave"), { memberId: "x", role: "MEMBER" }));
  await assertFails(setDoc(doc(as("alice"), "groups/anon/members/x"), { role: "MOD" }));
});

test("members read challenges, scores and summaries; nobody writes them", async () => {
  await assertSucceeds(getDoc(doc(as("bob"), "groups/anon/challenges/c1")));
  await assertSucceeds(getDoc(doc(as("bob"), "groups/anon/challenges/c1/summary/current")));
  await assertSucceeds(getDocs(collection(as("bob"), "groups/anon/challenges/c1/scores")));
  await assertFails(getDoc(doc(as("dave"), "groups/anon/challenges/c1/scores/m_bob")));
  await assertFails(setDoc(doc(as("bob"), "groups/anon/challenges/c1/scores/m_bob"), { score: 9999 }));
  await assertFails(setDoc(doc(as("alice"), "groups/anon/challenges/c2"), { spec: {} }));
});

test("reactions are posted as your own member id only", async () => {
  const reaction = (actorMemberId: string) => ({
    type: "REACTION",
    actorMemberId,
    message: null,
    data: { eventId: "e1", reaction: "🔥" },
    createdAt: new Date(),
  });
  await assertSucceeds(addDoc(collection(as("bob"), "groups/anon/feed"), reaction("m_bob")));
  await assertFails(addDoc(collection(as("bob"), "groups/anon/feed"), reaction("m_alice")));
  await assertFails(addDoc(collection(as("dave"), "groups/anon/feed"), reaction("m_dave")));
  await assertFails(
    addDoc(collection(as("bob"), "groups/anon/feed"), { ...reaction("m_bob"), type: "MEMBER_JOINED" }),
  );
});

test("invite codes, reports and rate limits are server only", async () => {
  await assertFails(setDoc(doc(as("alice"), "invites/ABC234"), { groupId: "anon" }));
  await assertFails(getDoc(doc(as("alice"), "invites/ABC234")));
  await assertFails(addDoc(collection(as("alice"), "reports"), { reason: "x" }));
  await assertFails(getDocs(collection(as("alice"), "rateLimits")));
  assert.ok(true);
});
