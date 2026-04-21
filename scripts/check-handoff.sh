#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT"

GRADLE="./gradlew"
if [[ ! -x "$GRADLE" ]]; then
  if [[ -f "$GRADLE" ]]; then
    chmod +x "$GRADLE"
  else
    echo "gradlew is missing in repository root" >&2
    exit 1
  fi
fi

TASKS_OUTPUT="$($GRADLE tasks --all --no-daemon --console=plain)"
if [[ "$TASKS_OUTPUT" != *"ktlintCheck"* ]]; then
  echo "ktlintCheck task is missing" >&2
  exit 1
fi

if [[ "$TASKS_OUTPUT" != *"detekt"* ]]; then
  echo "detekt task is missing" >&2
  exit 1
fi

$GRADLE clean :wrapper:assemble :wrapper:test ktlintCheck detekt --no-daemon --console=plain

$GRADLE :wrapper:publishToMavenLocal --no-daemon --console=plain

$GRADLE :sample:assemble --no-daemon --console=plain

VERSION_NAME="$(grep -E '^VERSION_NAME=' gradle.properties | head -n1 | cut -d'=' -f2-)"
if [[ -z "$VERSION_NAME" ]]; then
  echo "VERSION_NAME not found in gradle.properties" >&2
  exit 1
fi

if [[ ! "$VERSION_NAME" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$ ]]; then
  echo "VERSION_NAME must follow semantic format, got '$VERSION_NAME'" >&2
  exit 1
fi

if [[ "${GITHUB_REF_TYPE:-}" == "tag" ]]; then
  if [[ ! "${GITHUB_REF_NAME:-}" =~ ^v[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$ ]]; then
    echo "Tag '${GITHUB_REF_NAME:-}' does not match required release tag format vX.Y.Z[-suffix]" >&2
    exit 1
  fi
fi

if ! grep -qiE '^##[[:space:]]+Usage\b' README.md; then
  echo "README.md must contain a '## Usage' section" >&2
  exit 1
fi

echo "check-handoff passed"
