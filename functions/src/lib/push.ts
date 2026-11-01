import { getMessaging } from "firebase-admin/messaging";
import { db } from "./firebase";
import { forEachAccess, getUser } from "./store";
import { localParts } from "../domain/dayRanges";

export type ChannelId = "results" | "reminders" | "groups";

/**
 * One per toggle in the app's You → Notifications. Keys match `NotificationType.key` in
 * core/model Groups.kt; a user who turned a type off gets no push of that type.
 */
export type NotificationType =
  | "invites"
  | "roleChanges"
  | "newChallenges"
  | "milestones"
  | "results"
  | "sleepDigest";

export interface PushPayload {
  title: string;
  body: string;
  channelId: ChannelId;
  type: NotificationType;
  /** Deep-link destination, read by the app's FCM service. */
  route: string;
  data?: Record<string, string>;
}

function minutesOf(value: string): number {
  const [hour, minute] = value.split(":").map(Number);
  return hour * 60 + minute;
}

/** False when the user turned this type off (the digest also honours the older morningDigest flag). */
export function wantsType(settings: Record<string, unknown>, type: NotificationType): boolean {
  const toggles = (settings.notifications ?? {}) as Record<string, unknown>;
  if (toggles[type] === false) return false;
  if (type === "sleepDigest" && toggles[type] === undefined && settings.morningDigest === false) return false;
  return true;
}

/**
 * The user wants this type, and it isn't quiet hours (22:00-07:00 by default, in the member's own
 * time zone, unless they turned quiet hours off).
 */
async function shouldDeliver(uid: string, type: NotificationType, now: Date): Promise<boolean> {
  const user = await getUser(uid);
  const timeZone = typeof user?.tz === "string" ? user.tz : "UTC";
  const settings = (user?.settings ?? {}) as Record<string, unknown>;
  if (!wantsType(settings, type)) return false;
  if (settings.quietHoursEnabled === false) return true;
  const quietStart = typeof settings.quietHoursStart === "string" ? settings.quietHoursStart : "22:00";
  const quietEnd = typeof settings.quietHoursEnd === "string" ? settings.quietHoursEnd : "07:00";
  const parts = localParts(now, timeZone);
  const current = parts.hour * 60 + parts.minute;
  const start = minutesOf(quietStart);
  const end = minutesOf(quietEnd);
  const quiet = start <= end
    ? current >= start && current < end
    : current >= start || current < end;
  return !quiet;
}

/** Send a data+notification message to every device of every user. Invalid tokens are pruned. */
export async function sendPushToUsers(uids: string[], payload: PushPayload): Promise<number> {
  if (uids.length === 0) return 0;
  const tokenOwners: Array<{ token: string; uid: string; deviceId: string }> = [];
  const now = new Date();

  await Promise.all(
    uids.map(async (uid) => {
      if (!(await shouldDeliver(uid, payload.type, now))) return;
      const devices = await db.collection("users").doc(uid).collection("devices").get();
      for (const device of devices.docs) {
        const token = device.get("fcmToken");
        if (typeof token === "string" && token.length > 0) {
          tokenOwners.push({ token, uid, deviceId: device.id });
        }
      }
    }),
  );

  if (tokenOwners.length === 0) return 0;

  let sent = 0;
  for (let i = 0; i < tokenOwners.length; i += 500) {
    const chunk = tokenOwners.slice(i, i + 500);
    const response = await getMessaging().sendEachForMulticast({
      tokens: chunk.map((t) => t.token),
      notification: { title: payload.title, body: payload.body },
      data: { route: payload.route, ...(payload.data ?? {}) },
      android: {
        priority: "normal",
        notification: { channelId: payload.channelId },
      },
    });
    sent += response.successCount;
    const invalid: Array<{ uid: string; deviceId: string }> = [];
    response.responses.forEach((result, index) => {
      const code = result.error?.code;
      if (
        code === "messaging/registration-token-not-registered" ||
        code === "messaging/invalid-registration-token"
      ) {
        invalid.push(chunk[index]);
      }
    });
    await Promise.all(
      invalid.map((entry) =>
        db.collection("users").doc(entry.uid).collection("devices").doc(entry.deviceId).delete(),
      ),
    );
  }
  return sent;
}

/**
 * Sends to every member of a group, a page at a time, so a very large group never loads all its
 * members into memory at once.
 */
export async function sendPushToGroup(
  groupId: string,
  payload: PushPayload,
  options: { exceptUid?: string; roles?: string[] } = {},
): Promise<number> {
  let sent = 0;
  await forEachAccess(groupId, async (page) => {
    const uids = page
      .filter((a) => a.uid !== options.exceptUid)
      .filter((a) => !options.roles || options.roles.includes(a.role))
      .map((a) => a.uid);
    sent += await sendPushToUsers(uids, payload);
  });
  return sent;
}

/**
 * Feed events are readable by every group member. Actors are member ids, never uids, so an
 * anonymous group's feed can't be joined against anything outside the group.
 */
export async function writeFeedEvent(
  groupId: string,
  event: {
    type: string;
    actorMemberId?: string | null;
    message?: string | null;
    data?: Record<string, unknown>;
  },
  id?: string,
): Promise<void> {
  const feed = db.collection("groups").doc(groupId).collection("feed");
  const doc = id ? feed.doc(id) : feed.doc();
  await doc.set({
    type: event.type,
    actorMemberId: event.actorMemberId ?? null,
    message: event.message ?? null,
    data: event.data ?? {},
    createdAt: new Date(),
  });
}
