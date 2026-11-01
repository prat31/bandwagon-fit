import { setGlobalOptions } from "firebase-functions/v2";

setGlobalOptions({ region: "asia-south1", maxInstances: 20 });

export { onDailySummaryWrite } from "./triggers/onDailySummaryWrite";
export { onUserProfileWrite } from "./triggers/onUserProfileWrite";
export { finalizeChallenges, refreshDirtySummaries } from "./scheduled/challenges";
export { morningDigest } from "./scheduled/digest";
export { cleanup } from "./scheduled/cleanup";
export { claimUsername, setAlias, findUser } from "./callable/identity";
export {
  createGroup,
  updateGroup,
  joinGroup,
  leaveGroup,
  deleteGroup,
  setRole,
  transferOwnership,
  removeMember,
  reportContent,
} from "./callable/groups";
export {
  createInvite,
  listInvites,
  revokeInvite,
  previewInvite,
  redeemInvite,
  inviteByUsername,
  respondToInvitation,
} from "./callable/invites";
export { createChallenge, updateChallenge, cancelChallenge } from "./callable/challenges";
export { deleteAccount } from "./callable/account";
