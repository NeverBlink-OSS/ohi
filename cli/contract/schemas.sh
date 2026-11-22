#!/usr/bin/env bash
# Run from the repository root with Java available.
# Writes the JSON Schemas of what the hub serves to directory $2, from the contract schemas in
# directory $1.
set -euo pipefail

./mill --no-server cli.ohi.jvm.run linkml to json-schema --to "$2/package.schema.json" "$1/package.yaml"
# The hub serves search hits. The root of search.yaml is the page of hits that an SDK returns.
./mill --no-server cli.ohi.jvm.run linkml to json-schema --tree-root SearchHit \
  --to "$2/search.schema.json" "$1/search.yaml"
