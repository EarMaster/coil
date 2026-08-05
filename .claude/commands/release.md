---
description: Bump the app version (major/minor/patch or explicit semver), update CHANGELOG.md and the per-locale Play Store release notes, and commit the release.
allowed-tools: Bash(git status:*), Bash(git pull:*), Bash(git diff:*), Bash(git add:*), Bash(git commit:*), Bash(git push:*), Read, Edit, Bash, AskUserQuestion, TodoWrite, TodoRead
model: haiku
---

You are preparing a new release for the Coil Android app. Your job is to determine the new version number, update `app/build.gradle.kts`, restructure `CHANGELOG.md`, generate the per-locale Play Store release notes under `fastlane/metadata/android/`, verify their character limits, and commit the result.

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

**4a. Create release-note (What's New) files**

Release notes live in the fastlane metadata tree, one file per locale, named after the **new versionCode** — not the versionName:

```
fastlane/metadata/android/{LOCALE}/changelogs/{NEW_VERSION_CODE}.txt
```

So bumping to versionCode 7 means creating `fastlane/metadata/android/en-US/changelogs/7.txt` and its four siblings. Getting this wrong is the most likely mistake in this step: a file named `0.2.0.txt` or one using the *old* versionCode will simply never be picked up, and the release ships with no notes.

**All five launch locales are mandatory.** There is no English fallback for store text — a locale with no file gets a blank What's New in the Play Store for that language. `google-play.yml` fails the deploy if any locale is missing a file for the versionCode being released, so an incomplete set blocks the release rather than shipping quietly:

- `en-US` (source language — write this one first, the others are translations of it)
- `de-DE`
- `fr-FR`
- `es-ES`
- `nl-NL`

Each file contains a short user-facing summary of the release. Aim for **max 300 characters in the English draft** to leave headroom for translation expansion; the hard limit is 500 per locale (see the check below). Base the content on the `## [Unreleased]` section of `CHANGELOG.md`, but write it in plain language for end users — not a technical log. Do not copy changelog bullet points verbatim.

Only include changes that are visible or relevant to the user of the app itself. Exclude anything related to CI/CD workflows, GitHub Actions, GitHub Pages, the website, internal tooling, or other infrastructure — users don't see these.

**Translation quality:** per `AGENTS.md` and `README.md`, unreviewed machine translation is not acceptable for anything a user reads. Draft each locale's text yourself, but flag clearly to the user that the `de-DE`/`fr-FR`/`es-ES`/`nl-NL` drafts need a fluent-speaker review pass before the release actually ships — same standard as `res/values-<locale>/strings.xml`. Store text deserves *more* care than in-app strings, not less: in-app strings fall back to English when missing, store text does not.

**4b. Verify the character limits**

Google Play's 500-character What's New limit is per locale and counted in characters. Translation length varies a lot by language (Romance/Germanic expand, CJK compress), so an in-limit English draft guarantees nothing about the rest.

Run the validator with the **new** versionCode:

```
tools/check_store_metadata.sh {NEW_VERSION_CODE}
```

(Invoke it through `bash` — the repo is developed on Windows with `core.filemode=false`, so the script's executable bit is not recorded in git.)

It checks all five locales for a present, non-empty, in-limit file and exits non-zero listing every problem. If a locale is over, re-trim *that locale's* text (summarize or combine points) and re-run until it passes. Do not skip this because the English source was short, and do not hand-count instead: `wc -m` counts bytes rather than characters unless the shell locale is UTF-8, so on Windows it reports 4 for `für` and makes accented locales look over-limit. The script counts correctly and is the only measurement to trust here.

Treat a non-zero exit as blocking — the same check runs in CI and will fail the deploy.

**4c. Update `app/build.gradle.kts`**

Replace the `versionCode` and `versionName` lines with the new values. Use Edit — do not rewrite the whole file.

**4d. Update `CHANGELOG.md`**

Get today's date via: `date +%Y-%m-%d`

Replace the line `## [Unreleased]` (at the top of the Unreleased section) with:

```
## [Unreleased]

## [X.Y.Z] - YYYY-MM-DD
```

This preserves an empty Unreleased section for future work and stamps the release with today's date.

## Step 5 — Check the screenshot goldens

A release commit is the last chance to catch a stale golden before CI fails on it. The screenshot
tests compare the UI against committed PNGs in `app/src/testDebug/screenshots/`, so any change to a
Compose screen — a new row, a reworded string, a spacing tweak — makes the matching golden wrong.
Nothing in an ordinary build catches this: `./gradlew test` only checks that every screen still
composes, so a stale golden passes locally right up until `ci.yml` runs `verifyRoborazziDebug`.

Work out whether that is likely for this release — did it touch the UI or any user-visible string?

```
git diff --stat $(git describe --tags --abbrev=0)..HEAD -- 'app/src/main/kotlin/**/ui/**' 'app/src/main/res/values/strings.xml' 'app/src/testDebug/screenshots/'
```

If UI or strings changed but the `screenshots/` folder did not, tell the user the goldens are
probably stale and confirm it:

```
./gradlew :app:verifyRoborazziDebug --tests '*ScreenshotTest'
```

On failure, look at the `_compare` images in `app/build/outputs/roborazzi/` and check the diff is
the intended change rather than a regression, then accept the new look:

```
./gradlew :app:recordRoborazziDebug --tests '*ScreenshotTest'
```

The `--tests` filter is not optional, and recording locally is legitimate — see AGENTS.md's
"Screenshot tests" for both points. Include the updated PNGs in the release commit.

## Step 6 — Commit

Run:
```
git add app/build.gradle.kts CHANGELOG.md fastlane/metadata/android/
git commit -m "chore(release): bump version to X.Y.Z"
```

Use `AskUserQuestion` to ask if he wishes to push. If so run `git push`. Note: `main` is branch-protected (PR required, enforced for admins) — push to a branch and open a PR rather than pushing `main` directly.
