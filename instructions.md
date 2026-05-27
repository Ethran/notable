## Project Overview

Notable is an Android note-taking app for Onyx BOOX e-ink tablets. Performance and correctness on e-ink hardware are the primary constraints shaping every design decision.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt, Room, OpenGL canvas
**Source:** `app/src/main/java/com/ethran/notable` — read `docs/file-structure.md`
**Key Docs:** `editor-data-flow.md`, `editor-state-and-view.md`, `database-structure.md`, `result-and-error-handling.md`, `webdav-sync-technical.md`
**Schemas:** `app/schemas/` — Room migration snapshots live here

---

## Running Tests

From the project root:

```bash
./gradlew test                        # unit tests
./gradlew connectedAndroidTest        # instrumented (requires device or emulator)
./gradlew test connectedAndroidTest   # both
```

If you hit a JDK path error, ask the user for their `JAVA_HOME` rather than assuming a path.

---

## What to Test

You have discretion over which specific cases to write — prioritize whatever has the highest regression risk given the code you're working in. As a baseline, make sure every area below has meaningful coverage before considering a feature complete:

**Editor behavior** — strokes, erasing, undo/redo, selection, page switching. Fast/repeated actions (rapid undo, quick page flips) are especially prone to state corruption.

**Persistence** — saving and loading notes, page ordering, metadata. Test interrupted flows (app backgrounded mid-save) and partial repository failures.

**Room migrations** — every schema change must ship with a migration test verifying data preservation and correct defaults. Use the snapshots in `app/schemas/`.

**Import/Export** — round-trip correctness for all supported formats. Include malformed and edge-case inputs.

**Sync** — conflict resolution logic, retry/backoff behavior, and partial failure recovery.

**Thread safety** — confirm that background I/O never touches the main or render thread.

**Compose UI** — only where it validates behavior that unit tests can't reach: navigation arguments, recomposition correctness (`remember`/`derivedStateOf`), and critical user flows.
