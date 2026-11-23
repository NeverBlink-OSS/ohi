#!/usr/bin/env python3
"""Fail when the JSON Schemas of the contract break compatibility with those of a client release.

    check.py <release> <released directory> <current directory>

Each directory holds package.schema.json and search.schema.json, as schemas.sh writes them.
"""

import copy
import json
from pathlib import Path
import sys

from jsoncompat import analyze_compat

ANY_TEXT = {"type": "string"}

# Per schema file, opens the places where released clients skip a value they do not know instead
# of refusing the record.
SKIPPED = {
    "package": lambda classes: classes["Artifact"]["properties"].update(format=ANY_TEXT, origin=ANY_TEXT),
    "search": lambda classes: classes["SearchHit"]["properties"]["formats"].update(items=ANY_TEXT),
}


def _format_as_pattern(node):
    # A property can itself be named format. Its value is then a schema, not text.
    if isinstance(node.get("format"), str):
        node.setdefault("allOf", []).append({"pattern": node.pop("format")})
    return node


def _comparable(schema):
    """The schema as JSON text for jsoncompat, which ignores `format`. Its `assert_formats` option
    leaves even unchanged schemas unproven. Each format becomes a pattern, which it does compare,
    so a date that becomes plain text is reported."""
    return json.dumps(json.loads(json.dumps(schema), object_hook=_format_as_pattern))


def breaks(file, released, current):
    """Returns one message per direction in which the `current` JSON Schema of the schema file
    `file` breaks compatibility with the `released` one."""
    as_released_client = copy.deepcopy(released)
    SKIPPED[file](as_released_client["$defs"])
    directions = [
        (as_released_client, "serializer", "Released clients may refuse data that this schema allows"),
        (released, "deserializer", "Clients built from this schema may refuse data that the release allows"),
    ]
    messages = []
    for old, role, message in directions:
        result = analyze_compat(_comparable(old), _comparable(current), role)
        if result["status"] != "compatible":
            messages.append(f"{message}. jsoncompat: {result['reason']}" if "reason" in result else message)
    return messages


if __name__ == "__main__":
    release, *directories = sys.argv[1:]
    failed = False
    for file in SKIPPED:
        released, current = (
            json.loads(Path(directory, f"{file}.schema.json").read_text(encoding="utf-8"))
            for directory in directories
        )
        messages = breaks(file, released, current)
        print(f"{file}: {'not compatible' if messages else 'compatible'} with {release}")
        for message in messages:
            print(f"  {message}")
        failed = failed or bool(messages)
    sys.exit(failed)
