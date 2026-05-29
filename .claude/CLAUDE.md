# CLAUDE.md

Project rules for Paginator. Auto-loaded by Claude Code.

## Project

**Paginator** — Kotlin Multiplatform pagination library (Android, iOS, JVM, Desktop, JS, Wasm).
Pure-Kotlin alternative to Jetpack Paging 3: cursor & offset pagination, bidirectional scroll,
bookmarks, page caching, element CRUD, infinite scroll, prefetch, Flow-based UI state.
This is a **published public library** — API quality, backward compatibility, and docs matter more
than speed.

**Modules:** `paginator-core` (base) · `paginator-{offset,cursor}` (strategies) ·
`paginator-view-{common,offset,cursor}` (RecyclerView) ·
`paginator-compose-{common,offset,cursor}` (Compose) ·
`paginator-bom` (BOM) · `:app` (Android demo, not published).

**Layout:** `docs/` (numbered topic `.md`) · `articles/{en,ru}` · tests in each module's
`src/commonTest`.

## Workflow

**Before a task**

- Read relevant `docs/` (always `docs/1. core-concepts.md` + topic files); reuse existing
  concepts/terms, don't reinvent them.
- Baseline: `./gradlew allTests`. Pre-existing failures aren't yours — report them.

**After a task**

- Add/update tests in the module's `src/commonTest`; cover new behavior and add a regression test
  for each bug fix.
- All tests green: `./gradlew allTests`. Never disable/skip tests to get green — fix the cause.
- Demo compiles: `./gradlew :app:assembleDebug`. If an API change breaks the demo, update the demo
  too.
- Sync docs to code: `docs/` and **both** `articles/en` and `articles/ru` when affected.

## Checklist

`docs/` read → baseline `allTests` → implement → tests written → `allTests` green →
`:app:assembleDebug` → `docs/` + `articles/{en,ru}` updated
