import { onDocumentWritten } from "firebase-functions/v2/firestore";
import { scoreForUserDate } from "../lib/scoring";

/**
 * The single write path for derived data. Whenever a daily summary is created, changed or deleted,
 * the member is re-scored in every active challenge that covers that date. Only that member's
 * documents are written; summaries refresh on their own schedule (see scoring.ts).
 */
export const onDailySummaryWrite = onDocumentWritten("users/{uid}/daily/{date}", async (event) => {
  await scoreForUserDate(event.params.uid, event.params.date);
});
