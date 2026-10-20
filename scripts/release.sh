#!/usr/bin/env bash
# Publishes a release: bumps the version tag, builds and checks the signed
# APK, pushes the tag and attaches the APK and latest.json to a GitHub
# release, where the app looks for updates.
#
#   scripts/release.sh            # 0.1.0 -> 0.1.1
#   scripts/release.sh minor      # 0.1.1 -> 0.2.0
#   scripts/release.sh major      # 0.2.0 -> 1.0.0
#   scripts/release.sh --dry-run  # everything except pushing and publishing
#
# The version lives only in git tags; app/build.gradle.kts reads it from there.
#
# Local settings go in scripts/release.env (not committed), sourced if present.
# While the repository is private, the app needs a read-only token to see its
# releases; release.env can provide it, e.g.
#   export WPLAYER_UPDATE_TOKEN=$(your-secret-store get wplayer-update-token)
set -euo pipefail
cd "$(dirname "$0")/.."

bump=patch
dry_run=false
for arg in "$@"; do
    case $arg in
        patch | minor | major) bump=$arg ;;
        --dry-run) dry_run=true ;;
        *) echo "usage: $0 [patch|minor|major] [--dry-run]" >&2; exit 2 ;;
    esac
done

fail() { echo "release: $*" >&2; exit 1; }

# shellcheck source=/dev/null
[[ -f scripts/release.env ]] && source scripts/release.env

[[ -z $(git status --porcelain) ]] || fail "commit or stash your changes first"
[[ $(git rev-parse --abbrev-ref HEAD) == main ]] || fail "release from the main branch"
[[ -f keystore.properties ]] || fail "keystore.properties is missing; releases must carry the release signature"
command -v gh >/dev/null || fail "the GitHub CLI (gh) is required"
if [[ $(gh repo view --json visibility -q .visibility) == PRIVATE && -z ${WPLAYER_UPDATE_TOKEN:-} ]]; then
    fail "the repository is private, so the app needs WPLAYER_UPDATE_TOKEN to see releases (see scripts/release.env)"
fi
export WPLAYER_UPDATE_TOKEN=${WPLAYER_UPDATE_TOKEN:-}

git fetch --tags --quiet
last=$(git describe --tags --match 'v[0-9]*.[0-9]*.[0-9]*' --abbrev=0 2>/dev/null || true)
if [[ -z $last ]]; then
    next=0.1.0
else
    [[ -n $(git log --oneline "$last..HEAD") ]] || fail "nothing to release since $last"
    IFS=. read -r major minor patch <<<"${last#v}"
    case $bump in
        major) next=$((major + 1)).0.0 ;;
        minor) next=$major.$((minor + 1)).0 ;;
        patch) next=$major.$minor.$((patch + 1)) ;;
    esac
fi
tag=v$next
echo "release: ${last:-(first)} -> $tag"

# The tag must exist before the build so the APK carries its version.
published=false
git tag -a "$tag" -m "Wplayer $next"
# A tag that never reached GitHub is removed again, so a failed run can simply be retried.
cleanup() { [[ $published == true ]] || git tag -d "$tag" >/dev/null; }
trap cleanup EXIT

source ./env.sh
./gradlew --console=plain :app:testDebugUnitTest :app:lintRelease :app:assembleRelease

apk=app/build/outputs/apk/release/app-release.apk
build_tools=$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)
# Captured first: `tool | grep -q` would fail under pipefail when grep stops reading early.
badging=$("$build_tools/aapt2" dump badging "$apk")
[[ $badging == *"versionName='$next'"* ]] || fail "the APK does not carry version $next"
certs=$("$build_tools/apksigner" verify --print-certs "$apk")
[[ $certs == *"CN=Wplayer"* ]] || fail "the APK is not signed with the personal key"

mkdir -p build/release
apk_name=wplayer-$tag.apk
out=build/release/$apk_name
cp "$apk" "$out"

if [[ -n $last ]]; then
    notes="## Changes since $last"$'\n\n'$(git log --no-merges --pretty='- %s' "$last..$tag")
else
    notes="First release."
fi

BADGING=$badging python3 - "$next" "$apk_name" "$(shasum -a 256 "$out" | cut -d' ' -f1)" "$notes" >build/release/latest.json <<'PY'
import json, os, re, sys
name, apk, sha, notes = sys.argv[1:]
code = int(re.search(r"\bversionCode='(\d+)'", os.environ["BADGING"]).group(1))
print(json.dumps({"versionName": name, "versionCode": code, "apk": apk, "sha256": sha, "notes": notes}, ensure_ascii=False, indent=2))
PY

if [[ $dry_run == true ]]; then
    echo "release: dry run, built $out; tag $tag not pushed"
    echo "$notes"
    exit 0
fi

git push origin "$tag"
gh release create "$tag" "$out" build/release/latest.json --title "Wplayer $next" --notes "$notes"
published=true

echo "release: published $tag"
