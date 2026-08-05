# Play Store listing metadata

Google Play *store listing* text, one directory per locale, in the layout `fastlane supply`
and Triple-T's gradle-play-publisher both read natively. Keeping it here means listing copy is
reviewed in PRs like any other translated string instead of being retyped into the Play Console.

Locales are Coil's five launch locales, matching the app's translated resources
(`app/src/main/res/values-*`) and `localeFilters` in `app/build.gradle.kts`: `en-US`, `de-DE`,
`es-ES`, `fr-FR`, `nl-NL`.

| File | Play limit | Status |
|---|---|---|
| `title.txt` | 30 characters | present |
| `short_description.txt` | 80 characters | present |
| `full_description.txt` | 4000 characters | not written yet |
| `changelogs/{versionCode}.txt` | 500 characters | written per release by `/release` |

## Release notes are named after the versionCode

`changelogs/7.txt` is the What's New text for **versionCode 7** — not version 0.7.0. That is how
fastlane and the Play API key release notes, and it is the easy thing to get wrong: a file named
after the versionName, or one left at the previous versionCode, is silently never picked up and
the release ships with empty release notes.

All five locales are required for every release. There is no English fallback for store text, so a
missing locale means a blank What's New in that language rather than an English one. `google-play.yml`
enforces this and fails the deploy rather than shipping a gap.

## Screenshots

`en-US/images/` holds the listing screenshots, generated from the app itself rather than taken
by hand:

```bash
./gradlew :app:recordRoborazziDebug --tests '*StoreAssetTest'
```

That renders `phoneScreenshots`, `sevenInchScreenshots` and `tenInchScreenshots` from
`StoreAssetTest` (see AGENTS.md, "Store assets"), strips the alpha channel Play rejects, and
copies the phone set to the website. `check_store_metadata.sh` validates them the same way it
validates the text — format, alpha, dimensions, aspect ratio and count — so a screenshot that
Play would refuse fails here first.

Only English is generated: the other four translations are unreviewed drafts, and Play falls
back to the default language's images for a locale that has none.

## What does *not* live here

**The feature graphic.** Would go in `<locale>/images/featureGraphic.png` (1024×500); not
written yet.

## What CI does with these files

`google-play.yml` reads `changelogs/{versionCode}.txt` for every locale, validates it, and copies
it into the flat `whatsnew-<locale>` naming `r0adkll/upload-google-play` expects.

That action does **not** upload listing text — title, short description and full description are
still copied into the Play Console by hand. These files are the canonical source; the Console is
the copy. Closing that gap means adding a `fastlane supply` step or moving to
gradle-play-publisher, either of which would read this tree as-is.

## Checking character limits

```sh
tools/check_store_metadata.sh        # listing text
tools/check_store_metadata.sh 7      # also require release notes for versionCode 7
```

Play enforces every limit per locale, in characters. Do not hand-count with `wc -m`: it only
decodes multi-byte characters when the shell locale is UTF-8 and counts bytes otherwise, so in
Git Bash on Windows `für` measures 4 and every accented locale looks longer than it is. The script
counts bytes minus UTF-8 continuation bytes, which is exact regardless of locale.

## Translation status

The four non-English locales are **drafts**, not reviewed by fluent speakers — same caveat as the
header comments in `app/src/main/res/values-*/strings.xml`. Per `CONTRIBUTING`, a locale ships only
after a human has read it. Unlike in-app strings, there is no English fallback here: an unreviewed
listing is what users see in the store, so review matters more, not less.

Rendering notes for the current text:

- "coil" stays lowercase and "Phoniebox" stays untranslated in every locale — both are proper
  names, Phoniebox being the upstream project's.
- The wry sense of English "proper" (*a real one, as it should be*) is carried by `richtige` (de),
  `en toda regla` (es), `véritable` (fr) and `volwaardige` (nl). A literal "correct/appropriate"
  rendering would lose the tone.
- de/nl take a definite article before the device noun (`für die`, `voor de`) where dropping it
  reads clipped; fr/es treat Phoniebox as a bare product name, which also sidesteps guessing its
  grammatical gender in French.
