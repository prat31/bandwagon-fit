/** Username, alias and group-text rules. Pure functions so they are easy to test. */

const HANDLE = /^[a-z][a-z0-9._]{2,19}$/;

const RESERVED = new Set([
  "admin",
  "administrator",
  "bandwagon",
  "help",
  "mod",
  "moderator",
  "official",
  "owner",
  "root",
  "staff",
  "support",
  "system",
  "team",
  "anonymous",
  "everyone",
  "me",
  "you",
]);

/** A short, deliberately conservative list. Matched as substrings of the normalised text. */
const BLOCKED = ["fuck", "shit", "cunt", "nigg", "fag", "rape", "nazi", "whore", "slut", "porn"];

export function normaliseHandle(value: unknown): string {
  return typeof value === "string" ? value.trim().toLowerCase().replace(/^@/, "") : "";
}

function containsBlocked(text: string): boolean {
  const flat = text.toLowerCase().replace(/[^a-z]/g, "");
  return BLOCKED.some((word) => flat.includes(word));
}

/** Returns why a username or alias can't be used, or null when it is fine. */
export function handleProblem(handle: string): string | null {
  if (handle.length < 3) return "Use at least 3 characters.";
  if (handle.length > 20) return "Use at most 20 characters.";
  if (!HANDLE.test(handle)) {
    return "Start with a letter and use only letters, numbers, dots and underscores.";
  }
  if (handle.includes("..") || handle.endsWith(".")) return "Dots can't be doubled or come last.";
  if (RESERVED.has(handle)) return "That name is reserved.";
  if (containsBlocked(handle)) return "Please choose a different name.";
  return null;
}

/** Generated avatars only: a shape and a colour token, never a photo. */
export const ALIAS_SHAPES = ["circle", "square", "triangle", "diamond", "hexagon", "star"] as const;
export const ALIAS_COLORS = ["coral", "mango", "teal", "green", "plum", "sky"] as const;

export interface AliasAvatar {
  shape: (typeof ALIAS_SHAPES)[number];
  color: (typeof ALIAS_COLORS)[number];
}

export function cleanAvatar(value: unknown): AliasAvatar {
  const data = (value ?? {}) as Record<string, unknown>;
  const shape = ALIAS_SHAPES.includes(data.shape as AliasAvatar["shape"])
    ? (data.shape as AliasAvatar["shape"])
    : "circle";
  const color = ALIAS_COLORS.includes(data.color as AliasAvatar["color"])
    ? (data.color as AliasAvatar["color"])
    : "teal";
  return { shape, color };
}

const MAX_NAME = 40;

/** The display name shown to others. Never empty, capped so it can't flood a group UI. */
export function cleanDisplayName(value: unknown): string {
  const name = typeof value === "string" ? value.trim().replace(/\s+/g, " ") : "";
  return name.length > 0 ? name.slice(0, MAX_NAME) : "Friend";
}

export const GROUP_TAGS = [
  "walking",
  "running",
  "sleep",
  "fitness",
  "mindfulness",
  "weight",
  "workplace",
  "local",
] as const;

export interface GroupText {
  name: string;
  description: string;
  tags: string[];
}

/** Group names and descriptions are public in Discover, so they pass the same filter. */
export function cleanGroupText(input: { name?: unknown; description?: unknown; tags?: unknown }):
  | { ok: true; value: GroupText }
  | { ok: false; reason: string } {
  const name = typeof input.name === "string" ? input.name.trim().replace(/\s+/g, " ") : "";
  const description =
    typeof input.description === "string" ? input.description.trim().slice(0, 280) : "";
  if (name.length < 3 || name.length > 40) {
    return { ok: false, reason: "Group names are 3 to 40 characters." };
  }
  if (containsBlocked(name) || containsBlocked(description)) {
    return { ok: false, reason: "Please choose different words for the name or description." };
  }
  const tags = Array.isArray(input.tags)
    ? [...new Set(input.tags.filter((t): t is string => GROUP_TAGS.includes(t as never)))].slice(0, 3)
    : [];
  return { ok: true, value: { name, description, tags } };
}
