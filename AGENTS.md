# AGENTS.md

Guidelines for AI coding agents (and human contributors) working in this repository. These rules apply to every change, big or small.

## Project Overview

- **App**: Swanirvor-23 — an offline-first Android savings ledger for a legal professionals' collective.
- **Stack**: Kotlin, Jetpack Compose (Material 3), Room (SQLite), Supabase (Auth, PostgREST, Storage), OkHttp, Gradle (Kotlin DSL).
- **Build**: `./gradlew assembleDebug` / `./gradlew build`; typecheck with `./gradlew compileDebugKotlin` or `./gradlew build`.

## Non-Negotiable Rules

1. **Responsive mobile design.** Every screen must adapt to phones and tablets: use `dp`/`sp` (never raw pixels), respect safe areas/insets, support portrait and landscape where reasonable, and avoid fixed sizes that overflow small screens. Prefer Compose `Modifier` layout (weight, wrap, fill) over hardcoded dimensions.

2. **Power efficiency is a first-class requirement.** Lawyers use this app all day on a single charge. Never add polling loops (`while(true)` + `delay`), unbounded background work, or long-running network calls. Use lifecycle-aware APIs (`viewModelScope`, `Lifecycle`), `WorkManager` for deferrable work, `AlarmManager` only with correct permissions, and cache/batch network and DB calls. Any sync must be event-driven or scheduled — never a fixed-interval loop.

3. **Free tools only.** No paid modules, APIs, SDKs, or services. Use open-source libraries and free tiers (e.g., Supabase free tier). Do not add a dependency, service, or asset that requires payment or a paid license. Verify a library's license/free tier before adding it.

4. **Industry-standard structure and naming.** Keep packages under `com.example` (data/model, data/local, data/repository, ui/view, ui/viewmodel, ui/notification, ui/theme, util) and name files after the single concept they contain (`SavingsViewModel.kt`, `AuthScreen.kt`, `Constants.kt`). Follow the existing layout; don't invent new top-level folders.

5. **Clear file names.** A file's name must describe its one responsibility. No `Utils.kt`, `Misc.kt`, or numbered files like `Screen2.kt`.

6. **Short, precise comments before code.** Write a brief comment above each non-obvious block explaining *why*. When you later edit that block, **keep the existing comment** — do not delete it — and, if the behavior changed, update the comment to reflect the new behavior.

7. **Stay modular — no giant files.** Don't cram unrelated logic into one file. Split screens, helpers, and data access into separate files with appropriate names so no file grows to thousands of lines. (The existing `MainScreen.kt` monolith is the exception being refactored, not the pattern to copy.)

8. **Only change what you were asked to change.** Do not refactor, reformat, rename, or "fix" code outside the scope of the current instruction. Leave untouched files and sections exactly as they are.

9. **Never guess.** If a requirement, UI detail, data rule, or edge case is unclear or conflicting, stop and ask the user. Batch questions into one list.

10. **Scope.** Only touch files/sections the current task instructs you to. Never implement future tasks early.

11. **Maintain the version history on every edit.** Every change gets a snippet in the version history file. The project currently keeps this in `versionHistory.txt` (root), which `app/build.gradle.kts` parses — it reads the `Current Version:` line to set `versionName`. Keep the history in the single file the build reads (`versionHistory.txt`, or `versionHistory.md` once migrated — never maintain two divergent history files; keep the build's file reference in sync).

   Format — add a new line at the top and bump the version:

   ```txt
   Current Version: 0.0.43

   Version: 0.0.43 - <short description of the change>
   Version: 0.0.42 - Removed the redundant "Security" section...
   ```

   The last version in the file **is** the app's version — update it as part of every change.

## Working Conventions

- **Read before editing.** Inspect the surrounding code and the project's existing patterns before changing anything. Match existing style, naming, and architecture.
- **Verify the library is already used** before introducing a new dependency; prefer what's already in `app/build.gradle.kts` / `gradle/libs.versions.toml`.
- **Kotlin/Compose correctness.** Keep blocking work (network, DB) off the main thread (`Dispatchers.IO`, `viewModelScope`). Prefer `StateFlow`/`collectAsStateWithLifecycle` over polling state. Handle configuration changes without leaking resources.
- **Security.** Never commit secrets, API keys, or tokens. Credentials belong in config (`.env` / `BuildConfig`), not hardcoded in source. Don't log tokens, passwords, or PII. Don't store plaintext passwords.
- **Minimal, focused diffs.** Make the fewest changes that satisfy the request. Don't stage unrelated files (`git add -A`).
- **Verify non-trivial changes.** Run the typecheck and relevant tests before finishing. Report what you ran and its result.
- **Don't run destructive or irreversible commands** (force-push, resets, deploys, deleting production data) unless explicitly asked.
- **Don't commit or open a PR** unless the user asks you to.

## Definition of Done

- The requested change is implemented and scoped to the instruction (Rules 8, 10).
- Comments are present/updated per Rule 6 and existing comments were not deleted.
- New code is in appropriately named files/packages (Rules 4, 5, 7).
- The change compiles and relevant checks pass.
- A version-history entry was added and the version bumped (Rule 11).
