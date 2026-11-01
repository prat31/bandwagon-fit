/**
 * Date bucketing in a member's local time zone.
 *
 * Cloud Functions run in UTC, so every rule that talks about "today" or "09:00 local" must
 * resolve the member's zone explicitly. This module is the only place that does that conversion.
 */

const formatterCache = new Map<string, Intl.DateTimeFormat>();

function formatter(timeZone: string): Intl.DateTimeFormat {
  let fmt = formatterCache.get(timeZone);
  if (!fmt) {
    fmt = new Intl.DateTimeFormat("en-US", {
      timeZone,
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
      hour: "2-digit",
      minute: "2-digit",
      second: "2-digit",
      hourCycle: "h23",
      weekday: "short",
    });
    formatterCache.set(timeZone, fmt);
  }
  return fmt;
}

export interface LocalParts {
  year: number;
  month: number;
  day: number;
  hour: number;
  minute: number;
  second: number;
  /** 0 = Sunday ... 6 = Saturday. */
  weekday: number;
}

const WEEKDAYS: Record<string, number> = {
  Sun: 0,
  Mon: 1,
  Tue: 2,
  Wed: 3,
  Thu: 4,
  Fri: 5,
  Sat: 6,
};

export function localParts(instant: Date, timeZone: string): LocalParts {
  const parts = formatter(timeZone).formatToParts(instant);
  const value = (type: string): string => parts.find((p) => p.type === type)?.value ?? "0";
  return {
    year: Number(value("year")),
    month: Number(value("month")),
    day: Number(value("day")),
    hour: Number(value("hour")) % 24,
    minute: Number(value("minute")),
    second: Number(value("second")),
    weekday: WEEKDAYS[value("weekday")] ?? 0,
  };
}

export function dateToString(year: number, month: number, day: number): string {
  return `${year.toString().padStart(4, "0")}-${month.toString().padStart(2, "0")}-${day
    .toString()
    .padStart(2, "0")}`;
}

export function parseDate(date: string): { year: number; month: number; day: number } {
  const [year, month, day] = date.split("-").map(Number);
  return { year, month, day };
}

export function addDays(date: string, days: number): string {
  const { year, month, day } = parseDate(date);
  const d = new Date(Date.UTC(year, month - 1, day + days));
  return dateToString(d.getUTCFullYear(), d.getUTCMonth() + 1, d.getUTCDate());
}

export function localDate(instant: Date, timeZone: string): string {
  const p = localParts(instant, timeZone);
  return dateToString(p.year, p.month, p.day);
}

export function localTime(instant: Date, timeZone: string): string {
  const p = localParts(instant, timeZone);
  return `${p.hour.toString().padStart(2, "0")}:${p.minute.toString().padStart(2, "0")}`;
}

/** Convert a local wall-clock time to an instant, correcting for the zone offset (and DST). */
export function zonedTimeToUtc(
  year: number,
  month: number,
  day: number,
  hour: number,
  minute: number,
  timeZone: string,
): Date {
  const desired = Date.UTC(year, month - 1, day, hour, minute, 0, 0);
  let timestamp = desired;
  for (let i = 0; i < 4; i += 1) {
    const p = localParts(new Date(timestamp), timeZone);
    const actual = Date.UTC(p.year, p.month - 1, p.day, p.hour, p.minute, p.second, 0);
    const diff = desired - actual;
    if (diff === 0) break;
    timestamp += diff;
  }
  return new Date(timestamp);
}

export function dayStart(date: string, timeZone: string): Date {
  const { year, month, day } = parseDate(date);
  return zonedTimeToUtc(year, month, day, 0, 0, timeZone);
}

export function dayRange(date: string, timeZone: string): { start: Date; end: Date } {
  return { start: dayStart(date, timeZone), end: dayStart(addDays(date, 1), timeZone) };
}

/** 12:00 the day before through 18:00 on the wake date. */
export function sleepWindow(wakeDate: string, timeZone: string): { start: Date; end: Date } {
  const { year, month, day } = parseDate(addDays(wakeDate, -1));
  const wake = parseDate(wakeDate);
  return {
    start: zonedTimeToUtc(year, month, day, 12, 0, timeZone),
    end: zonedTimeToUtc(wake.year, wake.month, wake.day, 18, 0, timeZone),
  };
}

export function sleepCutoff(wakeDate: string, timeZone: string): Date {
  const { year, month, day } = parseDate(wakeDate);
  return zonedTimeToUtc(year, month, day, 18, 0, timeZone);
}

export function datesBetween(start: string, end: string): string[] {
  const dates: string[] = [];
  let cursor = start;
  while (cursor <= end) {
    dates.push(cursor);
    cursor = addDays(cursor, 1);
  }
  return dates;
}

export function overlapMinutes(start: Date, end: Date, date: string, timeZone: string): number {
  const day = dayRange(date, timeZone);
  const from = Math.max(start.getTime(), day.start.getTime());
  const to = Math.min(end.getTime(), day.end.getTime());
  return to > from ? Math.floor((to - from) / 60_000) : 0;
}

export function weekday(date: string): number {
  const { year, month, day } = parseDate(date);
  return new Date(Date.UTC(year, month - 1, day)).getUTCDay();
}
