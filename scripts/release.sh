#!/usr/bin/env bash
# Publishes a release of Telepad.
#
#   scripts/release.sh 2.1.0              checks everything, and asks before each step that cannot be undone
#   scripts/release.sh 2.1.0-alpha.1      a pre-release: the app and server still say 2.1.0, only the tag differs
#   scripts/release.sh 2.1.0 --dry-run    shows what it would do, changes nothing
#   scripts/release.sh 2.1.0 --yes        does not ask (only once you have read this script)
#   scripts/release.sh 2.1.0 --skip-checks   leaves out the local tests (CI runs them anyway)
#
# In order, it: checks the branch, the version numbers and the changelog; runs the tests; pushes
# main; waits for CI; creates a signed tag and pushes it, which starts the Release workflow; waits
# for that; and then checks that the download links work and that the APK is signed with the same
# key as the previous release (a different key would stop phones from updating in place).
#
# Run scripts/bump-version.sh first when the versions are not yet set, and write the changelog.
# Needs: git (with your signing key), gh (logged in as the owner), curl. Optional: cargo, the
# Android SDK, apksigner, vercel.
set -euo pipefail

say()  { printf '\033[36m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[33mwarning:\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[31merror:\033[0m %s\n' "$*" >&2; exit 1; }

full="" dry=0 yes=0 checks=1
for arg in "$@"; do
  case "$arg" in
    --dry-run) dry=1 ;;
    --yes|-y) yes=1 ;;
    --skip-checks) checks=0 ;;
    -h|--help) sed -n '2,/^[^#]/p' "$0" | sed '$d' | sed 's/^# \{0,1\}//'; exit 0 ;;
    -*) die "unknown option $arg" ;;
    *) [ -z "$full" ] || die "give one version only"; full="$arg" ;;
  esac
done
[[ "$full" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.]+)?$ ]] || die "give a version like 2.1.0 or 2.1.0-alpha.1 (see --help)"
version="${full%%-*}"     # the number the app, the server and the changelog carry
prerelease=0
[ "$version" = "$full" ] || prerelease=1
tag="v$full"

run() { printf '    $ %s\n' "$*"; [ "$dry" = 1 ] || "$@"; }
ask() {
  [ "$dry" = 1 ] || [ "$yes" = 1 ] && return 0
  read -r -p "$1 [y/N] " reply
  [[ "$reply" =~ ^[Yy]$ ]] || die "stopped; nothing further was done"
}

cd "$(git rev-parse --show-toplevel)"
[ "$dry" = 0 ] || say "Dry run: nothing will be changed"

# --- Who and where ------------------------------------------------------------------------
repo="$(gh repo view --json nameWithOwner --jq .nameWithOwner)"
login="$(gh api user --jq .login)"
[ "$login" = "${repo%%/*}" ] || die "gh is logged in as '$login', but $repo belongs to '${repo%%/*}'. Run: gh auth switch --user ${repo%%/*}"
[ "$(git branch --show-current)" = main ] || die "releases are made from main (you are on '$(git branch --show-current)')"
[ -z "$(git status --porcelain)" ] || die "the working tree has uncommitted changes; commit or stash them first"
git fetch origin --tags --quiet
git merge-base --is-ancestor origin/main HEAD || die "origin/main has commits that you do not have; pull first"
git rev-parse -q --verify "refs/tags/$tag" > /dev/null && die "the tag $tag already exists here"
git ls-remote --exit-code --tags origin "refs/tags/$tag" > /dev/null 2>&1 && die "the tag $tag already exists on origin"
previous_tag="$(gh release list --repo "$repo" --limit 1 --json tagName --jq '.[0].tagName // empty')"
say "Releasing $tag of $repo as $login (previous release: ${previous_tag:-none})"

# --- Versions and changelog ---------------------------------------------------------------
cargo_version="$(sed -nE 's/^version = "([^"]+)".*/\1/p' Cargo.toml | head -n 1)"
app_version="$(sed -nE 's/^[[:space:]]*versionName = "([^"]+)".*/\1/p' android/app/build.gradle.kts | head -n 1)"
[ "$cargo_version" = "$version" ] || die "Cargo.toml says $cargo_version, not $version (scripts/bump-version.sh $version)"
[ "$app_version" = "$version" ] || die "android/app/build.gradle.kts says $app_version, not $version (scripts/bump-version.sh $version)"
heading="$(grep -m 1 "^## \[$version\]" CHANGELOG.md || true)"
[ -n "$heading" ] || die "CHANGELOG.md has no '## [$version]' section"
if [[ "$heading" == *Unreleased* ]]; then
  today="$(date +%F)"
  say "The changelog still says Unreleased"
  ask "Date it $today and commit that?"
  if [ "$dry" = 0 ]; then
    perl -pi -e "s/^## \[\Q$version\E\] - Unreleased/## [$version] - $today/" CHANGELOG.md
    git add CHANGELOG.md
    git commit -q -m "docs: date the $version changelog"
  else
    echo "    (would set the heading to: ## [$version] - $today)"
  fi
fi

# --- Local checks -------------------------------------------------------------------------
if [ "$checks" = 1 ]; then
  say "Checking the desktop server"
  run cargo fmt --all -- --check
  run cargo clippy --workspace --all-targets --locked -- -D warnings
  run cargo test --workspace --locked
  if [ -n "${ANDROID_HOME:-}${ANDROID_SDK_ROOT:-}" ] || [ -f android/local.properties ]; then
    # Gradle 8.10 runs on Java 17 to 23. On a newer default (27, say) it stops with only "What went
    # wrong: 27", so say what is wrong instead. CI runs the same checks, and this script waits for it.
    java_major="$("${JAVA_HOME:+$JAVA_HOME/bin/}java" -version 2>&1 | sed -nE '1s/^[^"]*"([0-9]+).*/\1/p' || true)"
    if [ -z "$java_major" ]; then
      warn "no Java found (set JAVA_HOME to a JDK 17), so the Android checks are left to CI"
    elif [ "$java_major" -lt 17 ] || [ "$java_major" -gt 23 ]; then
      warn "the Android build cannot run on Java $java_major (Gradle 8.10 needs 17 to 23). Set JAVA_HOME to a JDK 17 to check it here; for now the Android checks are left to CI"
    else
      say "Checking the Android app"
      (cd android && run ./gradlew testDebugUnitTest lintDebug)
    fi
  else
    warn "no Android SDK found (set ANDROID_HOME), so the Android checks are left to CI"
  fi
fi

# --- Push main and wait for CI ------------------------------------------------------------
ahead="$(git rev-list --count origin/main..HEAD)"
if [ "$ahead" != 0 ]; then
  say "$ahead commit(s) are not on origin/main yet"
  git --no-pager log --oneline origin/main..HEAD | sed 's/^/    /'
  ask "Push them to origin/main?"
  run git push origin main
fi
if [ "$dry" = 0 ]; then
  sha="$(git rev-parse HEAD)"
  say "Waiting for CI on ${sha:0:7}"
  ci_run=""
  for _ in $(seq 1 30); do
    ci_run="$(gh run list --workflow CI --commit "$sha" --json databaseId --jq '.[0].databaseId // empty' 2> /dev/null || true)"
    [ -z "$ci_run" ] || break
    sleep 4
  done
  if [ -z "$ci_run" ]; then
    warn "no CI run was found for this commit (CI only runs when code changes)"
    ask "Release without a CI run for this commit?"
  else
    gh run watch "$ci_run" --exit-status || die "CI failed: https://github.com/$repo/actions/runs/$ci_run. Fix it, push, and run this again."
  fi
else
  say "(would wait for CI to pass on the pushed commit)"
fi

# --- Tag, which starts the Release workflow -----------------------------------------------
say "Ready to tag $tag. The Release workflow builds the app and the servers, signs the APK, and publishes."
ask "Create the signed tag $tag and push it?"
run git tag -s "$tag" -m "Telepad $full"
run git push origin "$tag"

if [ "$dry" = 1 ]; then
  say "Dry run finished. After the tag, the script waits for the Release workflow and checks:"
  echo "    the download links, and that the APK is signed with the same key as ${previous_tag:-the previous release}"
  exit 0
fi

say "Waiting for the Release workflow"
release_run=""
for _ in $(seq 1 30); do
  release_run="$(gh run list --workflow Release --branch "$tag" --json databaseId --jq '.[0].databaseId // empty' 2> /dev/null || true)"
  [ -z "$release_run" ] || break
  sleep 4
done
[ -n "$release_run" ] || die "the Release workflow did not start; look at https://github.com/$repo/actions"
gh run watch "$release_run" --exit-status || die "the Release workflow failed: https://github.com/$repo/actions/runs/$release_run"

# --- Check what was published -------------------------------------------------------------
say "Checking the download links"
# GitHub's "latest" skips pre-releases, so a pre-release is checked by its own tag.
if [ "$prerelease" = 1 ]; then
  download_base="https://github.com/$repo/releases/download/$tag"
else
  download_base="https://github.com/$repo/releases/latest/download"
fi
check_links() {
  local failed=0 name code
  for name in telepad-android.apk telepad-server-windows-x86_64.exe telepad-server-windows-x86_64.zip \
              telepad-server-linux-x86_64.tar.gz telepad-server-macos-universal.tar.gz SHA256SUMS; do
    code="$(curl -s -o /dev/null -w '%{http_code}' -L "$download_base/$name")"
    printf '    %-42s %s\n' "$name" "$code"
    [ "$code" = 200 ] || failed=1
  done
  return "$failed"
}
links_ok=0
for attempt in 1 2 3 4 5 6; do
  if check_links; then links_ok=1; break; fi
  [ "$attempt" = 6 ] || { echo "    (not all there yet; GitHub can take a minute. Trying again.)"; sleep 10; }
done
[ "$links_ok" = 1 ] || die "some download links do not work. Check https://github.com/$repo/releases/tag/$tag"

apksigner="$(ls "${ANDROID_HOME:-$HOME/Android/Sdk}"/build-tools/*/apksigner 2> /dev/null | sort -V | tail -n 1 || true)"
if [ -n "$apksigner" ] && command -v java > /dev/null; then
  say "Checking the APK's signing key"
  work="$(mktemp -d)"
  cleanup() { if [ -n "${work:-}" ] && [ -d "$work" ]; then rm -r -- "$work"; fi; }
  trap cleanup EXIT
  gh release download "$tag" --repo "$repo" --pattern 'telepad-android.apk' --dir "$work"
  new_key="$("$apksigner" verify --print-certs "$work/telepad-android.apk" | sed -nE 's/^Signer #1 certificate SHA-256 digest: //p')"
  echo "    $tag is signed with $new_key"
  old_name=""
  if [ -n "$previous_tag" ]; then
    old_name="$(gh release view "$previous_tag" --repo "$repo" --json assets --jq '[.assets[].name | select(endswith(".apk") and (ascii_downcase | contains("debug") | not))][0] // empty')"
  fi
  if [ -n "$old_name" ]; then
    gh release download "$previous_tag" --repo "$repo" --pattern "$old_name" --dir "$work/previous"
    old_key="$("$apksigner" verify --print-certs "$work/previous/$old_name" | sed -nE 's/^Signer #1 certificate SHA-256 digest: //p')"
    if [ "$old_key" = "$new_key" ]; then
      echo "    the same key as $previous_tag: phones can update in place"
    else
      warn "$previous_tag was signed with $old_key. Phones that have it installed will refuse this update."
    fi
  else
    warn "could not find an APK in ${previous_tag:-an earlier release} to compare the signing key with"
  fi
else
  warn "apksigner or java not found, so the APK's signing key was not checked"
fi

say "Done: https://github.com/$repo/releases/tag/$tag"
if [ "$prerelease" = 1 ]; then
  echo "This is a pre-release, so point the website's download buttons at it. Pushing deploys the website:"
  echo "    website/tools/set-release.sh $tag && git commit -am 'docs(website): download the $tag pre-release' && git push"
else
  echo "The website's download buttons now work."
fi
