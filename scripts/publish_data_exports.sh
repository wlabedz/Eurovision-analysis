#!/usr/bin/env bash
# Usage: publish_data_exports.sh <source-dir> [branch] [target-folder]
# Replaces <target-folder> on the branch and pushes only when the data changed.
set -euo pipefail

SOURCE_DIR="$(cd "$1" && pwd)"
BRANCH="${2:-data-exports}"
TARGET_FOLDER="${3:-data}"
WORKTREE="$(mktemp -d)"
trap 'git worktree remove --force "$WORKTREE" >/dev/null 2>&1 || true; rm -rf "$WORKTREE"' EXIT

if git ls-remote --exit-code --heads origin "$BRANCH" >/dev/null; then
  git fetch --depth=1 origin "+refs/heads/$BRANCH:refs/remotes/origin/$BRANCH"
  git worktree add --force -B "$BRANCH" "$WORKTREE" "refs/remotes/origin/$BRANCH"
else
  git worktree add --force --orphan -B "$BRANCH" "$WORKTREE"
fi

rm -rf "${WORKTREE:?}/$TARGET_FOLDER"
mkdir -p "$WORKTREE/$TARGET_FOLDER"
cp -R "$SOURCE_DIR"/. "$WORKTREE/$TARGET_FOLDER"/

git -C "$WORKTREE" add -A -- "$TARGET_FOLDER"
if git -C "$WORKTREE" diff --cached --quiet; then
  echo "No data changes to publish."
  exit 0
fi

git -C "$WORKTREE" commit -m "Update Eurovision data export"
git -C "$WORKTREE" push origin "HEAD:refs/heads/$BRANCH"
