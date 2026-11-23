#!/usr/bin/env bash
# Run from the repository root with Java and Python 3.12 or later available and the release tags
# fetched.
# Fails when the contract schemas in model/ohi/v2 break compatibility with the baseline release
# or the latest full release.
set -euo pipefail

baseline=v0.18.7

temporary=$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/ohi-contract.XXXXXX")
trap 'rm -rf "$temporary"' EXIT

python -m venv "$temporary/python"
"$temporary/python/bin/python" -m pip install --quiet jsoncompat==0.6.0
"$temporary/python/bin/python" -m unittest discover -s cli/contract

mkdir "$temporary/current"
bash cli/contract/schemas.sh model/ohi/v2 "$temporary/current"

# Tags with a - are pre-releases.
latest=$(git describe --tags --abbrev=0 --match 'v[0-9]*' --exclude '*-*')
for release in $(printf '%s\n' "$baseline" "$latest" | sort -u); do
  mkdir "$temporary/$release"
  git archive "$release" model/ohi | tar -x -C "$temporary/$release"
  # Releases before the move keep the contract schemas in model/ohi.
  released="$temporary/$release/model/ohi/v2"
  [ -d "$released" ] || released="$temporary/$release/model/ohi"
  bash cli/contract/schemas.sh "$released" "$temporary/$release"
  "$temporary/python/bin/python" cli/contract/check.py "$release" "$temporary/$release" "$temporary/current"
done
