#!/usr/bin/env bash
#
# Validate Play Store metadata under fastlane/metadata/android against Google Play's
# per-locale character limits.
#
# Usage:
#   tools/check_store_metadata.sh            # listing text only (title, descriptions)
#   tools/check_store_metadata.sh <code>     # also require changelogs/<code>.txt everywhere
#
# <code> is the app's versionCode, not the versionName — fastlane names release-note files
# after the versionCode (changelogs/7.txt), which is also what the Play API keys them by.
#
# Every limit Play enforces is per locale and counted in characters, so this checks each
# locale's own file. A translation that grew past the limit is the normal failure mode; the
# English source being short proves nothing about the others.
#
# Exits non-zero and prints every problem it found (not just the first), so one run tells you
# the full list of files to fix.

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
META="$ROOT/fastlane/metadata/android"

# Coil's launch locales (§12.1) — must stay in step with app/build.gradle.kts localeFilters
# and app/src/main/res/values-*.
LOCALES=(en-US de-DE es-ES fr-FR nl-NL)

VERSION_CODE="${1:-}"

# Play's limits, in characters.
LIMIT_TITLE=30
LIMIT_SHORT=80
LIMIT_FULL=4000
LIMIT_CHANGELOG=500

failures=0
warnings=0

fail() { printf '  FAIL  %s\n' "$1"; failures=$((failures + 1)); }
warn() { printf '  warn  %s\n' "$1"; warnings=$((warnings + 1)); }

# Character count of a file's content, ignoring trailing newlines.
#
# Deliberately NOT `wc -m`: that only decodes multi-byte characters when the locale is a UTF-8
# one, and silently counts bytes otherwise — in Git Bash on Windows `wc -m` reports 4 for
# "für". Instead: characters = bytes - UTF-8 continuation bytes (0x80-0xBF), which is exact for
# valid UTF-8 and needs no locale at all. Internal newlines count, as they do in Play.
count_chars() {
  local content bytes cont
  content="$(cat "$1")"
  bytes=$(printf '%s' "$content" | LC_ALL=C wc -c | tr -d '[:space:]')
  cont=$(printf '%s' "$content" | LC_ALL=C tr -dc '\200-\277' | LC_ALL=C wc -c | tr -d '[:space:]')
  echo $((bytes - cont))
}

# check_file <path> <limit> <required|optional> <label>
check_file() {
  local path="$1" limit="$2" requirement="$3" label="$4"
  local rel="${path#"$ROOT/"}"

  if [ ! -f "$path" ]; then
    if [ "$requirement" = required ]; then
      fail "$rel — missing (required)"
    else
      warn "$rel — not written yet"
    fi
    return
  fi

  local n
  n=$(count_chars "$path")

  if [ "$n" -eq 0 ]; then
    fail "$rel — empty; Play would publish a blank $label"
    return
  fi

  if [ "$n" -gt "$limit" ]; then
    fail "$rel — $n chars, over the $limit limit by $((n - limit))"
  else
    printf '  ok    %-52s %4d/%d\n' "$rel" "$n" "$limit"
  fi
}

if [ ! -d "$META" ]; then
  echo "No metadata directory at $META" >&2
  exit 1
fi

echo "Checking Play listing metadata in fastlane/metadata/android"
if [ -n "$VERSION_CODE" ]; then
  if ! [[ "$VERSION_CODE" =~ ^[0-9]+$ ]]; then
    echo "Error: version code must be an integer, got '$VERSION_CODE'" >&2
    exit 2
  fi
  echo "Requiring release notes for versionCode $VERSION_CODE in every locale"
fi
echo

for locale in "${LOCALES[@]}"; do
  echo "$locale"
  dir="$META/$locale"

  if [ ! -d "$dir" ]; then
    fail "fastlane/metadata/android/$locale — locale directory missing entirely"
    echo
    continue
  fi

  check_file "$dir/title.txt"             "$LIMIT_TITLE" required "app name"
  check_file "$dir/short_description.txt" "$LIMIT_SHORT" required "short description"
  check_file "$dir/full_description.txt"  "$LIMIT_FULL"  optional "full description"

  if [ -n "$VERSION_CODE" ]; then
    check_file "$dir/changelogs/$VERSION_CODE.txt" "$LIMIT_CHANGELOG" required "release note"
  fi

  echo
done

if [ "$failures" -gt 0 ]; then
  echo "$failures problem(s) found."
  exit 1
fi

if [ "$warnings" -gt 0 ]; then
  echo "All limits OK ($warnings warning(s))."
else
  echo "All limits OK."
fi
