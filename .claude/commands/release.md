---
description: Bump the app version (major/minor/patch or explicit semver), update CHANGELOG.md and What's New files, and commit the release.
allowed-tools: Bash(git status:*), Bash(git pull:*), Bash(git diff:*), Bash(git add:*), Bash(git commit:*), Bash(git push:*), Read, Edit, Bash, AskUserQuestion, TodoWrite, TodoRead
model: haiku
---

You are preparing a new release for the Coil Android app. Your job is to determine the new version number, update `app/build.gradle.kts`, restructure `CHANGELOG.md`, generate What's New files, and commit the result.

## Step 0 — Check readiness

Coil is pre-implementation until Phase 1 (module skeleton) lands — see `AGENTS.md`. If `app/build.gradle.kts` does not exist yet, stop and tell the user this command cuts an actual app release and needs the `:app` module first; there is nothing to version yet.

## Input

The user may have provided an argument: `$ARGUMENTS`

Interpret `$ARGUMENTS` as follows:
- `major`, `minor`, or `patch` (case-insensitive) → bump that semver component
- A full semver string like `1.0.4` or `2.0.0` → use it exactly
- Empty / blank → analyse the changelog and suggest a bump type (see below)
- Anything else → it is unusual; flag it and ask for confirmation or correction

## Step 1 — Read current version

Read `app/build.gradle.kts` and extract:
- `versionName` (e.g. `"0.1.0"`)
- `versionCode` (integer)

## Step 2 — Determine target version

**If argument is `major` / `minor` / `patch`:**
Compute the new semver by incrementing the appropriate component (reset lower components to 0).

**If argument is a valid semver (`X.Y.Z` with all three numeric parts):**
Use it as-is. Warn (but don't block) if the new version is less than or equal to the current one.

**If argument is empty:**
Read the `## [Unreleased]` section of `CHANGELOG.md`. Based on the entries:
- Any `### Added` → at minimum a `minor` bump
- Only `### Fixed` or `### Changed` (no new features) → `patch`
- Breaking change indicated (rare) → `major`

Formulate a suggestion with a one-sentence rationale.

**If argument is unusual** (e.g. only two parts like `1.2`, has leading zeros, non-numeric, etc.):
Do not proceed. Ask the user to confirm or correct.

## Step 3 — Confirm with user

Before making any changes, use **AskUserQuestion** to confirm. Show:
- Current version
- Proposed new version
- New versionCode (current + 1)

Single question, two options: "Proceed" and "Cancel / change". If the user cancels or provides a correction, re-evaluate from Step 2 with their input, then confirm again.

## Step 4 — Apply changes

**4a. Create What's New files**

Create `docs/whatsnew/X.Y.Z-{LOCALE}` files for Coil's launch locales:

- `en-US` (source language — write this one first, the others are translations of it)
- `de-DE`
- `fr-FR`
- `es-ES`
- `nl-NL`

Each file should contain a short user-facing summary of the release (**max 300 characters** to leave margin for translations, which can expand the text). Base the content on the `## [Unreleased]` section of `CHANGELOG.md`, but write it in plain language for end users — not a technical log. Do not copy changelog bullet points verbatim.

Only include changes that are visible or relevant to the user of the app itself. Exclude anything related to CI/CD workflows, GitHub Actions, GitHub Pages, the website, internal tooling, or other infrastructure — users don't see these.

**Translation quality:** per `AGENTS.md` and `README.md`, unreviewed machine translation is not acceptable for anything a user reads. Draft each locale's text yourself, but flag clearly to the user that the `de-DE`/`fr-FR`/`es-ES`/`nl-NL` drafts need a fluent-speaker review pass before the release actually ships — same standard as `res/values-<locale>/strings.xml`.

**Translation note:** Keep English concise (max 300 chars) as a starting point, but the 300/500 numbers are guidance for the *English draft* only, not a guarantee for the rest. Translation length varies a lot by language (Romance/Germanic languages tend to expand, CJK languages tend to compress) — a translation that started from an in-limit English draft can still end up over the limit.

**Mandatory per-locale check — do this for every locale, not just English:** after writing (or translating) each `docs/whatsnew/X.Y.Z-{LOCALE}` file, check that specific file's character count (e.g. `wc -m`, not `wc -c`, since `wc -c` undercounts multi-byte UTF-8 content). No locale file may exceed 500 characters — Google Play's hard "what's new" limit is per-locale, not per-release. If any file is over, re-trim that locale's text (summarize/combine bullets) and re-check — do not assume it's fine because the English source was short.

**4b. Update `app/build.gradle.kts`**

Replace the `versionCode` and `versionName` lines with the new values. Use Edit — do not rewrite the whole file.

**4c. Update `CHANGELOG.md`**

Get today's date via: `date +%Y-%m-%d`

Replace the line `## [Unreleased]` (at the top of the Unreleased section) with:

```
## [Unreleased]

## [X.Y.Z] - YYYY-MM-DD
```

This preserves an empty Unreleased section for future work and stamps the release with today's date.

## Step 5 — Commit

Run:
```
git add app/build.gradle.kts CHANGELOG.md docs/whatsnew/
git commit -m "chore(release): bump version to X.Y.Z"
```

Use `AskUserQuestion` to ask if he wishes to push. If so run `git push`. Note: `main` is branch-protected (PR required, enforced for admins) — push to a branch and open a PR rather than pushing `main` directly.
