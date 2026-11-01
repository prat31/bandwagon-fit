#!/usr/bin/env node
// Adds a fake member to a group on the local Firebase emulators, so group features can be tested
// with one Android emulator. It signs up an anonymous user, claims a username and an alias, writes
// today's daily summary the way the app would, and joins through the real callables.
//
// Usage: node tools/dev/fake-member.mjs <INVITE_CODE | GROUP_ID> <NAME> [STEPS] [SLEEP_HOURS] [BEDTIME_HH:MM]
// A 6-character code redeems an invite; anything longer joins that public group directly.

const [code, name, stepsArg = "6500", sleepHoursArg, bedtimeArg = "22:45"] = process.argv.slice(2);
if (!code || !name) {
  console.error("Usage: fake-member.mjs <INVITE_CODE|GROUP_ID> <NAME> [STEPS] [SLEEP_HOURS] [BEDTIME_HH:MM]");
  process.exit(1);
}

const PROJECT = process.env.FIREBASE_PROJECT ?? "bandwagon-dev";
const REGION = "asia-south1";
const AUTH = "http://127.0.0.1:9099/identitytoolkit.googleapis.com/v1";
const FIRESTORE = `http://127.0.0.1:8080/v1/projects/${PROJECT}/databases/(default)/documents`;
const FUNCTIONS = `http://127.0.0.1:5001/${PROJECT}/${REGION}`;
const TIME_ZONE = Intl.DateTimeFormat().resolvedOptions().timeZone;

async function call(url, { method = "POST", token, body } = {}) {
  const response = await fetch(url, {
    method,
    headers: {
      "Content-Type": "application/json",
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const json = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(`${method} ${url} -> ${response.status} ${JSON.stringify(json)}`);
  return json;
}

/** Converts plain values to Firestore REST field values. */
function fields(object) {
  const out = {};
  for (const [key, value] of Object.entries(object)) out[key] = toValue(value);
  return out;
}

function toValue(value) {
  if (value === null) return { nullValue: null };
  if (value instanceof Date) return { timestampValue: value.toISOString() };
  if (Number.isInteger(value)) return { integerValue: String(value) };
  if (typeof value === "number") return { doubleValue: value };
  if (typeof value === "boolean") return { booleanValue: value };
  if (typeof value === "string") return { stringValue: value };
  if (Array.isArray(value)) return { arrayValue: { values: value.map(toValue) } };
  return { mapValue: { fields: fields(value) } };
}

function localDate(date) {
  return new Intl.DateTimeFormat("en-CA", { timeZone: TIME_ZONE }).format(date);
}

const signUp = await call(`${AUTH}/accounts:signUp?key=fake`, { body: { returnSecureToken: true } });
const token = signUp.idToken;
const uid = signUp.localId;
await call(`${AUTH}/accounts:update?key=fake`, { body: { idToken: token, displayName: name } });

const now = new Date();
await call(`${FIRESTORE}/users/${uid}`, {
  method: "PATCH",
  token,
  body: { fields: fields({ displayName: name, photoUrl: null, tz: TIME_ZONE, updatedAt: now }) },
});

const handle = `${name.toLowerCase().replace(/[^a-z0-9]/g, "").slice(0, 12) || "friend"}${Math.floor(Math.random() * 900 + 100)}`;
const handle2 = handle.startsWith("x") ? handle : `x${handle}`;
await call(`${FUNCTIONS}/claimUsername`, { token, body: { data: { username: handle } } });
const shapes = ["circle", "square", "triangle", "diamond", "hexagon", "star"];
const colors = ["coral", "mango", "teal", "green", "plum", "sky"];
await call(`${FUNCTIONS}/setAlias`, {
  token,
  body: { data: { alias: `${handle2}a`.slice(0, 20), avatar: { shape: shapes[handle.length % 6], color: colors[name.length % 6] } } },
});

const joined = /^[A-Z0-9]{6}$/.test(code.toUpperCase())
  ? await call(`${FUNCTIONS}/redeemInvite`, { token, body: { data: { code: code.toUpperCase() } } })
  : await call(`${FUNCTIONS}/joinGroup`, { token, body: { data: { groupId: code } } });

const today = localDate(now);
const daily = {
  userId: uid,
  date: today,
  timeZone: TIME_ZONE,
  steps: Number(stepsArg),
  distanceMeters: Math.round(Number(stepsArg) * 0.75),
  activeCalories: Math.round(Number(stepsArg) * 0.04),
  activeMinutes: Math.round(Number(stepsArg) / 250),
  workoutMinutes: 20,
  workoutCount: 1,
  workoutsByType: { WALKING: { minutes: 20, count: 1, distanceMeters: 1500 } },
  floors: Math.round(Number(stepsArg) / 1000),
  mindfulMinutes: 10,
  shared: ["steps", "distance", "active_calories", "active_minutes", "workout_minutes", "workout_count", "floors", "mindful_minutes"],
  sources: ["fake-member"],
  manualExcluded: false,
  computedAt: now,
  sleep: null,
};
if (sleepHoursArg) {
  // Bedtime is the previous evening (or after midnight) in local time; the end is bedtime + hours.
  const [hours, minutes] = bedtimeArg.split(":").map(Number);
  const start = new Date(`${today}T00:00:00`);
  start.setDate(start.getDate() - (hours >= 12 ? 1 : 0));
  start.setHours(hours, minutes, 0, 0);
  const end = new Date(start.getTime() + Number(sleepHoursArg) * 3_600_000);
  // Stored as ISO strings, like the app's DailySummary mapper writes them.
  daily.shared.push("sleep_minutes");
  daily.sleep = {
    start: start.toISOString(),
    end: end.toISOString(),
    durationMinutes: Math.round(Number(sleepHoursArg) * 60),
    zoneId: TIME_ZONE,
  };
}
await call(`${FIRESTORE}/users/${uid}/daily/${today}`, { method: "PATCH", token, body: { fields: fields(daily) } });
await call(`${FIRESTORE}/users/${uid}?updateMask.fieldPaths=lastSyncAt`, {
  method: "PATCH",
  token,
  body: { fields: fields({ lastSyncAt: now }) },
});

console.log(`${name} (@${handle}) joined ${joined.result.groupId} with ${daily.steps} steps on ${today}`);
