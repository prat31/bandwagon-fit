import { test } from "node:test";
import assert from "node:assert/strict";
import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";

import {
  addDays,
  datesBetween,
  dayRange,
  dayStart,
  endInstant,
  evaluateMember,
  groupKept,
  groupStreak,
  handleProblem,
  cleanGroupText,
  localDate,
  localParts,
  localTime,
  milestonesCrossed,
  nextWeeklyWindow,
  overlapMinutes,
  rank,
  sleepWindow,
  validateSpec,
  weekday,
  wrappedClockMinutes,
  zonedTimeToUtc,
  type ChallengeSpec,
  type DailySummary,
} from "../src/domain/index";
import { inviteProblem } from "../src/lib/invites";
import { asDailySummary } from "../src/lib/models";

const KOLKATA = "Asia/Kolkata";
const NEW_YORK = "America/New_York";
const SHARED = join(__dirname, "..", "..", "shared");

/** Fixture dailies list only the fields they need; the rest are null like a real upload. */
function daily(partial: Record<string, unknown>): DailySummary {
  return asDailySummary(String(partial.date), { userId: "u", timeZone: "UTC", ...partial })!;
}


test("day range is half-open in local time", () => {
  const range = dayRange("2026-11-03", KOLKATA);
  assert.equal(range.start.toISOString(), "2026-11-02T18:30:00.000Z");
  assert.equal(range.end.toISOString(), "2026-11-03T18:30:00.000Z");
});

test("day range respects the spring forward DST transition", () => {
  const start = dayStart("2026-03-08", NEW_YORK);
  const end = dayStart("2026-03-09", NEW_YORK);
  assert.equal(start.toISOString(), "2026-03-08T05:00:00.000Z");
  assert.equal((end.getTime() - start.getTime()) / 3_600_000, 23);
});

test("local parts and local date are resolved in the member zone", () => {
  const instant = new Date("2026-11-03T20:30:00Z");
  assert.equal(localDate(instant, KOLKATA), "2026-11-04");
  assert.equal(localTime(instant, KOLKATA), "02:00");
  assert.equal(localParts(instant, KOLKATA).hour, 2);
  assert.equal(weekday("2026-11-03"), 2); // Tuesday
});

test("zoned wall clock converts back to UTC across a DST boundary", () => {
  assert.equal(zonedTimeToUtc(2026, 3, 8, 0, 0, NEW_YORK).toISOString(), "2026-03-08T05:00:00.000Z");
  assert.equal(zonedTimeToUtc(2026, 3, 9, 0, 0, NEW_YORK).toISOString(), "2026-03-09T04:00:00.000Z");
});

test("overlap clips a session to the requested day", () => {
  const start = new Date("2026-11-03T18:00:00Z"); // 23:30 IST
  const end = new Date("2026-11-03T19:15:00Z"); // 00:45 IST next day
  assert.equal(overlapMinutes(start, end, "2026-11-03", KOLKATA), 30);
});

test("sleep window runs from noon the day before to 18:00 on the wake date", () => {
  const window = sleepWindow("2026-11-03", KOLKATA);
  assert.equal(window.start.toISOString(), "2026-11-02T06:30:00.000Z");
  assert.equal(window.end.toISOString(), "2026-11-03T12:30:00.000Z");
});

test("datesBetween is inclusive and safe for reversed input", () => {
  assert.deepEqual(datesBetween("2026-10-30", "2026-11-02"), [
    "2026-10-30",
    "2026-10-31",
    "2026-11-01",
    "2026-11-02",
  ]);
  assert.deepEqual(datesBetween("2026-11-02", "2026-10-30"), []);
  assert.equal(addDays("2026-12-31", 1), "2027-01-01");
});

// --- Golden fixtures: the same files run in the Kotlin tests (core/model) ---------------------

const fixtureDir = join(SHARED, "fixtures", "challenges");
for (const file of readdirSync(fixtureDir).filter((f) => f.endsWith(".json"))) {
  const fixture = JSON.parse(readFileSync(join(fixtureDir, file), "utf8"));
  test(`fixture ${file}: ${fixture.name}`, () => {
    assert.equal(validateSpec(fixture.spec), null);
    const results: Record<string, ReturnType<typeof evaluateMember>> = {};
    for (const [memberId, days] of Object.entries(fixture.members as Record<string, unknown[]>)) {
      results[memberId] = evaluateMember(fixture.spec as ChallengeSpec, (days as Record<string, unknown>[]).map(daily));
      for (const [key, value] of Object.entries(fixture.expected[memberId] ?? {})) {
        assert.deepEqual(
          (results[memberId].result as unknown as Record<string, unknown>)[key],
          value,
          `${memberId}.${key}`,
        );
      }
    }
    if (fixture.ranks) {
      const ranked = rank(
        Object.entries(results).map(([memberId, r]) => ({ memberId, score: r.result.score, tieBreak: r.result.tieBreak })),
      );
      for (const [memberId, expected] of Object.entries(fixture.ranks)) {
        assert.equal(ranked.find((r) => r.memberId === memberId)?.rank, expected, `rank of ${memberId}`);
      }
    }
    if (fixture.days) {
      for (const [memberId, byDate] of Object.entries(fixture.days as Record<string, Record<string, string | null>>)) {
        for (const [date, status] of Object.entries(byDate)) {
          assert.equal(results[memberId].days.find((d) => d.date === date)?.status ?? null, status, `${memberId} ${date}`);
        }
      }
    }
    if (fixture.teamTotal !== undefined) {
      const total = Object.values(results).reduce((sum, r) => sum + r.result.score, 0);
      assert.equal(total, fixture.teamTotal);
    }
  });
}

test("every shared template becomes a valid spec once dates and a title are filled in", () => {
  const { templates } = JSON.parse(readFileSync(join(SHARED, "challenge-templates.json"), "utf8"));
  for (const template of templates) {
    const spec = {
      schemaVersion: 1,
      ...template.spec,
      window: { ...(template.spec.window ?? {}), start: "2026-11-02", end: addDays("2026-11-02", template.durationDays - 1) },
      display: { ...template.spec.display, templateId: template.id, title: template.name },
    };
    assert.equal(validateSpec(spec), null, template.id);
  }
});

// --- Engine rules -----------------------------------------------------------------------------

test("bedtime is minutes from midnight, wrapped so late evening is negative", () => {
  assert.equal(wrappedClockMinutes("2026-11-01T17:30:00Z", KOLKATA), -60); // 23:00
  assert.equal(wrappedClockMinutes("2026-11-01T18:30:00Z", KOLKATA), 0); // 00:00
  assert.equal(wrappedClockMinutes("2026-11-01T19:30:00Z", KOLKATA), 60); // 01:00
  assert.equal(wrappedClockMinutes("2026-11-01T16:30:00Z", KOLKATA), -120); // 22:00
});

test("ranking: ties share a rank and the next rank skips", () => {
  const ranked = rank([
    { memberId: "a", score: 10, tieBreak: 0 },
    { memberId: "b", score: 20, tieBreak: 0 },
    { memberId: "c", score: 10, tieBreak: 0 },
    { memberId: "d", score: 5, tieBreak: 0 },
  ]);
  assert.deepEqual(ranked.map((r) => [r.memberId, r.rank]), [["b", 1], ["a", 2], ["c", 2], ["d", 4]]);
});

test("group streak needs half the members with data and no misses", () => {
  assert.equal(groupKept({ date: "d", hit: 2, missed: 0, members: 4 }), true);
  assert.equal(groupKept({ date: "d", hit: 1, missed: 0, members: 4 }), false);
  assert.equal(groupKept({ date: "d", hit: 3, missed: 1, members: 4 }), false);
  const days = [
    { date: "1", hit: 2, missed: 0, members: 2 },
    { date: "2", hit: 2, missed: 0, members: 2 },
    { date: "3", hit: 1, missed: 1, members: 2 },
    { date: "4", hit: 2, missed: 0, members: 2 },
  ];
  assert.deepEqual(groupStreak(days), { current: 1, best: 2 });
});

test("a challenge is final once its end date is over in the last time zone, plus grace", () => {
  // 2026-11-04 ends at 2026-11-05T12:00Z in UTC-12; plus 12 h grace.
  assert.equal(endInstant("2026-11-04").toISOString(), "2026-11-06T00:00:00.000Z");
});

test("weekly challenges repeat with the same length right after they end", () => {
  const spec = { window: { start: "2026-11-02", end: "2026-11-08", recurrence: "WEEKLY" } } as ChallengeSpec;
  assert.deepEqual(nextWeeklyWindow(spec), { start: "2026-11-09", end: "2026-11-15", recurrence: "WEEKLY" });
});

test("team milestones are crossed once each", () => {
  assert.deepEqual(milestonesCrossed([], 52), [25, 50]);
  assert.deepEqual(milestonesCrossed([25, 50], 52), []);
  assert.deepEqual(milestonesCrossed([25, 50], 100), [75, 100]);
});

test("spec validation explains what is wrong", () => {
  const base = {
    schemaVersion: 1, kind: "TEAM", metric: { id: "steps" }, daily: { op: "value" }, aggregate: "SUM",
    goal: { target: 100 }, window: { start: "2026-11-02", end: "2026-11-03" },
    display: { templateId: "x", renderer: "PROGRESS", title: "Go" },
  };
  assert.equal(validateSpec(base), null);
  assert.match(validateSpec({ ...base, goal: null }) ?? "", /goal/);
  assert.match(validateSpec({ ...base, aggregate: "BEST_DAY" }) ?? "", /Team/);
  assert.match(validateSpec({ ...base, metric: { id: "heart_rate" } }) ?? "", /metric/);
  assert.match(validateSpec({ ...base, window: { start: "2026-11-03", end: "2026-11-02" } }) ?? "", /before/);
  assert.match(validateSpec({ ...base, window: { start: "2026-01-01", end: "2027-06-01" } }) ?? "", /year/);
  assert.match(validateSpec({ ...base, display: { ...base.display, title: " " } }) ?? "", /name/);
});

test("v1 daily summaries without a shared list still count what they hold", () => {
  const legacy = asDailySummary("2026-11-02", { date: "2026-11-02", steps: 5000, workoutMinutes: null });
  assert.deepEqual(legacy?.shared, ["steps"]);
});

// --- Identity and invites ---------------------------------------------------------------------

test("usernames: length, characters, reserved and blocked words", () => {
  assert.equal(handleProblem("maya_r"), null);
  assert.equal(handleProblem("m.a.y.a"), null);
  assert.match(handleProblem("ab") ?? "", /3/);
  assert.match(handleProblem("1maya") ?? "", /letter/);
  assert.match(handleProblem("ma..ya") ?? "", /Dots/);
  assert.match(handleProblem("maya.") ?? "", /Dots/);
  assert.match(handleProblem("admin") ?? "", /reserved/);
  assert.match(handleProblem("xshitx") ?? "", /different/);
});

test("group text is trimmed, tags are whitelisted, blocked words are refused", () => {
  const ok = cleanGroupText({ name: "  Morning   Walkers ", description: "hi", tags: ["walking", "nope", "walking"] });
  assert.deepEqual(ok, { ok: true, value: { name: "Morning Walkers", description: "hi", tags: ["walking"] } });
  assert.equal(cleanGroupText({ name: "ab" }).ok, false);
  assert.equal(cleanGroupText({ name: "Porn club" }).ok, false);
});

test("invites: revoked, expired, used up and wrong group are refused", () => {
  const now = new Date("2026-11-02T00:00:00Z");
  const base = { groupId: "g", uses: 0, maxUses: 1, expiresAt: new Date("2026-11-03T00:00:00Z") };
  assert.equal(inviteProblem(base, "g", now), null);
  assert.equal(inviteProblem({ ...base, maxUses: null, expiresAt: null }, "g", now), null);
  assert.match(inviteProblem({ ...base, revoked: true }, "g", now) ?? "", /turned off/);
  assert.match(inviteProblem({ ...base, uses: 1 }, "g", now) ?? "", /used up/);
  assert.match(inviteProblem({ ...base, expiresAt: new Date("2026-11-01T00:00:00Z") }, "g", now) ?? "", /expired/);
  assert.match(inviteProblem(base, "other", now) ?? "", /another group/);
  assert.match(inviteProblem(undefined, "g", now) ?? "", /no longer/);
});
