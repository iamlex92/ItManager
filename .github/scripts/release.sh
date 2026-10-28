#!/usr/bin/env bash
# Puts the given files on the GitHub release of the pushed v* tag. The release is created on first use as a
# normal (not pre-release) latest release, titled with the version and described by its CHANGELOG.md section.
# The Android and server workflows both call it; whichever gets there first creates the release.
set -euo pipefail
tag="${GITHUB_REF_NAME:?run on a v* tag}"
version="${tag#v}"
root="$(git rev-parse --show-toplevel)"

# The tag must match what the app and the server say they are, or the release would lie about its version.
app_version=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' "$root/app/build.gradle.kts")
server_version=$(sed -n 's/^version = "\(.*\)"/\1/p' "$root/server/pyproject.toml")
if [ "$app_version" != "$version" ] || [ "$server_version" != "$version" ]; then
  echo "::error::Tag $tag, but the app is $app_version and the server is $server_version. Bump both before tagging."
  exit 1
fi

notes=$(awk -v v="$version" '$0 == "## " v { on = 1; next } /^## / && on { exit } on' "$root/CHANGELOG.md")
if [ -z "${notes//[[:space:]]/}" ]; then
  echo "::error::CHANGELOG.md has no section «## $version»."
  exit 1
fi

if ! gh release view "$tag" >/dev/null 2>&1; then
  # The other workflow may create it a moment earlier: then just upload to it.
  gh release create "$tag" --title "ItManager $version" --notes "$notes" --verify-tag --latest \
    || gh release view "$tag" >/dev/null
fi
gh release upload "$tag" "$@" --clobber
