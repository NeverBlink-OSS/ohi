#!/usr/bin/env bash
# Run from the repository root with Java and Python available. Takes about eleven minutes.
# Fails when api.ohi.dev stops working for the clients of the baseline release, or serves a
# search hit or publication that does not match the contract schemas in model/ohi/v2.
set -euo pipefail

baseline=0.18.6

temporary=$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/ohi-production.XXXXXX")
trap 'rm -rf "$temporary"' EXIT

bash cli/contract/schemas.sh model/ohi/v2 "$temporary"

# Without LINKML_SCALA_MODIFY_PROFILE the installer offers to put the temporary directory on the PATH.
LINKML_SCALA_VERSION="v$baseline" LINKML_SCALA_INSTALL_DIR="$temporary" LINKML_SCALA_MODIFY_PROFILE=no \
  bash cli/install.sh
python -m venv "$temporary/python"
# jsonschema checks dates and URLs only with the packages of format-nongpl installed.
"$temporary/python/bin/python" -m pip install --quiet "ohi-sdk==$baseline" 'jsonschema[format-nongpl]==4.26.0'
"$temporary/python/bin/python" cli/check-production.py "$temporary/ohi" "$temporary"
