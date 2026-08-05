#!/usr/bin/env bash
#
# Validate Play Store metadata under fastlane/metadata/android against Google Play's
# per-locale character limits.
#
# Usage:
#   tools/check_store_metadata.sh            # listing text only (title, descriptions)
#   tools/check_store_metadata.sh <code>     # also require changelogs/<code>.txt everywhere
#
# Checked in executable (mode 100755) and invoked by path everywhere — CI, /release, by hand.
# Keep the mode: a plain `git add` of a new tool here will not set it, and a workflow step that
# runs it by path fails with exit 126 rather than with anything that names the real problem.
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

# Play's limits for listing images, in pixels and bytes. The ratio rule is the one that
# actually bites: a screenshot from a modern 20:9 phone is 2.2:1 and gets refused.
IMAGE_MIN_PX=320
IMAGE_MAX_PX=3840
IMAGE_MAX_RATIO=2
IMAGE_MAX_BYTES=$((8 * 1024 * 1024))

# Play requires at least two screenshots in a set for the listing to publish. A folder with one
# image in it is therefore a folder that stops a release, which is worth catching here.
IMAGE_MIN_PER_SET=2

# The image folder names fastlane supply uses. Anything else under images/ is flagged rather
# than ignored: a typo like "phonescreenshots" would simply never be uploaded.
IMAGE_SETS=(phoneScreenshots sevenInchScreenshots tenInchScreenshots tvScreenshots wearScreenshots)

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

# Reads a PNG's header: prints "width height colourtype", or nothing if the file is not a PNG.
#
# Done by hand rather than with ImageMagick or Python, because this script's whole point is to
# run anywhere without setup — the CI runner and a developer's shell both have od and awk.
# Byte 16 begins the IHDR data: width (4), height (4), bit depth (1), colour type (1).
png_header() {
  local signature
  signature=$(od -An -tx1 -N8 "$1" | tr -d ' \n')
  [ "$signature" = "89504e470d0a1a0a" ] || return 1
  od -An -tu1 -j16 -N10 "$1" |
    awk '{ print ($1*16777216)+($2*65536)+($3*256)+$4, ($5*16777216)+($6*65536)+($7*256)+$8, $10 }'
}

# check_image <path>
#
# Colour type 4 (grey+alpha) and 6 (RGBA) carry an alpha channel, which Play refuses outright:
# "JPEG or 24-bit PNG (no alpha)". That is the failure a generated screenshot hits first, since
# every renderer writes RGBA by default.
check_image() {
  local path="$1"
  local rel="${path#"$ROOT/"}"
  local bytes width height colour long short

  bytes=$(LC_ALL=C wc -c < "$path" | tr -d '[:space:]')
  if [ "$bytes" -gt "$IMAGE_MAX_BYTES" ]; then
    fail "$rel — $((bytes / 1024)) KB, over Play's 8 MB limit"
    return
  fi

  case "$path" in
    *.png) ;;
    *.jpg | *.jpeg)
      # Play accepts JPEG, and nothing in this repo generates one. Rather than grow a second
      # header parser for a format we do not produce, say plainly what was not checked.
      warn "$rel — JPEG; size checked, dimensions and colour depth not"
      return
      ;;
    *)
      fail "$rel — not a PNG or JPEG; Play accepts nothing else"
      return
      ;;
  esac

  local header
  if ! header=$(png_header "$path"); then
    fail "$rel — named .png but does not start with a PNG signature"
    return
  fi

  read -r width height colour <<< "$header"

  if [ "$colour" = 4 ] || [ "$colour" = 6 ]; then
    fail "$rel — has an alpha channel; Play needs 24-bit PNG with no alpha"
    return
  fi

  short=$width
  long=$height
  if [ "$width" -gt "$height" ]; then
    short=$height
    long=$width
  fi

  if [ "$short" -lt "$IMAGE_MIN_PX" ] || [ "$long" -gt "$IMAGE_MAX_PX" ]; then
    fail "$rel — ${width}x${height}; every side must be between $IMAGE_MIN_PX and $IMAGE_MAX_PX px"
    return
  fi

  if [ "$long" -gt $((short * IMAGE_MAX_RATIO)) ]; then
    fail "$rel — ${width}x${height}; the long side may not exceed twice the short one"
    return
  fi

  printf '  ok    %-52s %5dx%-5d\n' "$rel" "$width" "$height"
}

# check_images <locale directory>
check_images() {
  local dir="$1/images"
  [ -d "$dir" ] || return 0

  local set_dir count entry
  for set_name in "${IMAGE_SETS[@]}"; do
    set_dir="$dir/$set_name"
    [ -d "$set_dir" ] || continue

    count=0
    for entry in "$set_dir"/*; do
      [ -f "$entry" ] || continue
      check_image "$entry"
      count=$((count + 1))
    done

    if [ "$count" -lt "$IMAGE_MIN_PER_SET" ]; then
      fail "${set_dir#"$ROOT/"} — $count image(s); Play needs at least $IMAGE_MIN_PER_SET to publish"
    fi
  done

  # Anything else in images/ is either a typo or an asset type this check does not know about.
  # Both are worth saying out loud, because Play would silently ignore the folder.
  for entry in "$dir"/*; do
    [ -e "$entry" ] || continue
    local name
    name="$(basename "$entry")"
    case " ${IMAGE_SETS[*]} " in
      *" $name "*) ;;
      *) warn "${entry#"$ROOT/"} — not one of Play's image folders; nothing will upload it" ;;
    esac
  done
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

  # Screenshots are optional per locale — Play falls back to the default language's images —
  # so a locale without an images/ folder is silently fine. What is checked is the ones that
  # are there, whether generated by StoreAssetTest or dropped in by hand.
  check_images "$dir"

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
