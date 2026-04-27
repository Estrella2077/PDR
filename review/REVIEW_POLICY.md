# Review Maintenance Policy

Last updated: 2026-04-22 17:03:44

## Rule

After each substantive conversation turn, update the `review/` directory before replying to the user.

This includes:

- code changes
- behavior changes
- analysis conclusions
- project-status changes
- user workflow instructions such as "update review after every conversation"

## Minimum update scope

1. Record the latest conclusion or behavior change.
2. Record which source files were touched.
3. Record verification status such as build/test/manual validation.
4. Record any remaining risks or next steps.

## Preferred files

- Put turn-specific outcomes into `review/LATEST_UPDATE.md`.
- Keep PDF extraction and supporting notes in `review/pdf_extract/`.
- If a future turn needs a broader rewrite of summary docs, sync `CURRENT_STATUS.md`, `CONTEXT.md`, `KEY_FILES.md`, and `NEXT_STEPS.md` as needed.

## Current standing instruction

This project should treat review updates as part of the definition of done for each conversation.
