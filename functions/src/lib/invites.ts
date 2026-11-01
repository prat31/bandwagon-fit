import { randomInt } from "node:crypto";
import { db } from "./firebase";
import { toDate } from "./time";

/** No 0/O/1/I so codes can be read aloud and typed without confusion. */
const ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
const CODE_LENGTH = 6;

export function inviteLink(code: string): string {
  return `https://bandwagon.fit/i/${code}`;
}

function generateCode(): string {
  let code = "";
  for (let i = 0; i < CODE_LENGTH; i += 1) {
    code += ALPHABET[randomInt(0, ALPHABET.length)];
  }
  return code;
}

export function isInviteCode(value: string): boolean {
  return /^[A-Z0-9]{6}$/.test(value);
}

/** Why an invite can't be used for this group, or null when it can. */
export function inviteProblem(
  data: Record<string, unknown> | undefined,
  groupId: string | null,
  now: Date = new Date(),
): string | null {
  if (!data) return "That invite no longer exists.";
  if (data.revoked === true) return "That invite was turned off.";
  if (groupId !== null && data.groupId !== groupId) return "That invite is for another group.";
  const expiresAt = toDate(data.expiresAt);
  if (expiresAt && expiresAt.getTime() < now.getTime()) return "That invite has expired.";
  const maxUses = typeof data.maxUses === "number" ? data.maxUses : null;
  const uses = typeof data.uses === "number" ? data.uses : 0;
  if (maxUses !== null && uses >= maxUses) return "That invite has been used up.";
  return null;
}

/**
 * One-time (maxUses 1), multi-use (maxUses N) or unlimited (null) links, expiring after
 * `expiresInDays` or never (null).
 */
export async function createInviteForGroup(
  groupId: string,
  createdByMemberId: string,
  createdByUid: string,
  maxUses: number | null,
  expiresInDays: number | null,
): Promise<{ code: string; link: string }> {
  for (let attempt = 0; attempt < 8; attempt += 1) {
    const code = generateCode();
    const expiresAt =
      expiresInDays === null ? null : new Date(Date.now() + expiresInDays * 24 * 60 * 60 * 1000);
    try {
      await db.collection("invites").doc(code).create({
        code,
        groupId,
        createdByMemberId,
        createdBy: createdByUid,
        expiresAt,
        maxUses,
        uses: 0,
        revoked: false,
        createdAt: new Date(),
      });
      return { code, link: inviteLink(code) };
    } catch (error) {
      if ((error as { code?: number }).code !== 6) throw error; // 6 = ALREADY_EXISTS
    }
  }
  throw new Error("Could not allocate an invite code");
}
