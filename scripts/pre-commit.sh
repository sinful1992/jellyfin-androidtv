#!/bin/sh
# Pre-commit gate. Install with: cp scripts/pre-commit.sh .git/hooks/pre-commit
# A commit that changes app/ must also stage CHANGELOG.md with a line for that change.
STAGED=$(git diff --cached --name-only)
APP_STAGED=$(echo "$STAGED" | grep -c '^app/')
CHANGELOG_STAGED=$(echo "$STAGED" | grep -c '^CHANGELOG\.md$')
if [ "$APP_STAGED" -gt 0 ] && [ "$CHANGELOG_STAGED" -eq 0 ]; then
  echo "ERROR: app/ changed but CHANGELOG.md is not staged."
  echo "Add a line under '## Unreleased' (or the new hero heading) and stage CHANGELOG.md."
  exit 1
fi
