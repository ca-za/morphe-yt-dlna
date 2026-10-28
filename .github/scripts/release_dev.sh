#!/usr/bin/env bash
# Releases the dev branch as v0.1.<n>-alpha: every release counts the last number up
# (v0.1.0-alpha, v0.1.1-alpha, ...). Like semantic-release before, only feat, fix, perf and
# bump commits make a release. Writes "released" and "tag" to $GITHUB_OUTPUT.
set -euo pipefail

repo="${GITHUB_REPOSITORY:?}"
output="${GITHUB_OUTPUT:-/dev/null}"

last=$(git tag --list 'v0.1.*-alpha' --sort=-v:refname | head -n 1)
if [ -z "$last" ]; then
  next=0
  range="HEAD"
else
  n="${last#v0.1.}"
  n="${n%-alpha}"
  next=$((n + 1))
  range="$last..HEAD"
fi
version="0.1.${next}-alpha"
tag="v${version}"

# Commits since the last release, without the release commits themselves.
commits=$(git log --no-merges --format='%H%x09%s' "$range" | grep -vE $'\tchore: Release ' || true)
relevant=$(printf '%s\n' "$commits" | grep -E $'\t(feat|fix|perf|bump)(\\([^)]*\\))?!?: ' || true)
if [ -n "$last" ] && [ -z "$relevant" ]; then
  echo "No feat, fix, perf or bump commit since $last: no release."
  echo "released=false" >> "$output"
  exit 0
fi

# Release notes, grouped like the conventional commits preset.
date=$(date -u +%Y-%m-%d)
notes=$(mktemp)
if [ -n "$last" ]; then
  echo "## [${version}](https://github.com/${repo}/compare/${last}...${tag}) (${date})" > "$notes"
else
  echo "## ${version} (${date})" > "$notes"
  printf '\nFirst release with the new version numbering (formerly 1.0.0-alpha.1 to 1.0.0-alpha.9).\n' >> "$notes"
fi
section() {
  local type="$1" title="$2" lines
  lines=$(printf '%s\n' "$relevant" | grep -E $'\t'"${type}"'(\([^)]*\))?!?: ' || true)
  [ -z "$lines" ] && return 0
  printf '\n### %s\n\n' "$title" >> "$notes"
  while IFS=$'\t' read -r hash subject; do
    [ -z "$hash" ] && continue
    text=$(printf '%s' "$subject" | sed -E 's/^[a-z]+(\([^)]*\))?!?: //')
    printf '* %s ([%s](https://github.com/%s/commit/%s))\n' "$text" "${hash:0:7}" "$repo" "$hash" >> "$notes"
  done <<< "$lines"
}
if [ -n "$last" ]; then
  section feat "✨ New Features"
  section fix "🐛 Bug Fixes"
  section perf "🔧 Improvements"
  section bump "🚀 Updated App Support"
fi
echo "Releasing ${tag}:"
cat "$notes"

# Version, build, generated files.
sed -i "s/^version = .*/version = ${version}/" gradle.properties
./gradlew :patches:buildAndroid generatePatchesList --no-daemon
jq --arg v "$version" '.version=$v' patches-list.json > patches-list.json.tmp && mv patches-list.json.tmp patches-list.json
python3 .github/scripts/generate_patches_readme.py "$repo" "${GITHUB_REF_NAME:-dev}" patches-list.json README.md
jq -n --arg created "$(date -u +%Y-%m-%dT%H:%M:%S)" --rawfile description "$notes" \
  --arg url "https://github.com/${repo}/releases/download/${tag}/patches-${version}.mpp" --arg v "$version" \
  '{created_at: $created, description: ($description | rtrimstr("\n")), download_url: $url,
    signature_download_url: "", version: $v}' > patches-bundle.json
{ cat "$notes"; echo; [ -f CHANGELOG.md ] && cat CHANGELOG.md; } > CHANGELOG.md.tmp && mv CHANGELOG.md.tmp CHANGELOG.md

asset="patches/build/libs/patches-${version}.mpp"
test -f "$asset"

git config user.name "github-actions[bot]"
git config user.email "41898282+github-actions[bot]@users.noreply.github.com"
git add CHANGELOG.md gradle.properties patches-bundle.json patches-list.json README.md
git commit -q -m "chore: Release ${tag} [skip ci]" -m "$(cat "$notes")"
git tag "$tag"
git push origin "HEAD:${GITHUB_REF_NAME:-dev}" "$tag"
gh release create "$tag" --prerelease --verify-tag --title "$tag" --notes-file "$notes" "$asset"

echo "released=true" >> "$output"
echo "tag=${tag}" >> "$output"
