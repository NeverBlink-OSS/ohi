"""The release the version of a reference namespace/name@version requests. A published version
can be fixed in revisions, {original}-revision-{id}:

* 1.0 requests the latest revision of 1.0, or 1.0 itself when it has none
* 1.0-revision-2026-12-31 requests exactly that revision
* 1.0-no-revision requests the 1.0 version without its revisions
"""

from __future__ import annotations

from typing import NamedTuple

# What separates a revision's original version from its id
REVISION_SEPARATOR = "-revision-"
# What requests a version without its revisions
NO_REVISION_SUFFIX = "-no-revision"


class VersionTag(NamedTuple):
    # The version first published
    original: str
    # The version to use as it is, or None for the latest revision of original
    exact: str | None = None


def parse_version_tag(version: str) -> VersionTag:
    if version.endswith(NO_REVISION_SUFFIX) and len(version) > len(NO_REVISION_SUFFIX):
        original = version[:-len(NO_REVISION_SUFFIX)]
        return VersionTag(original, original)
    at = version.find(REVISION_SEPARATOR)
    return VersionTag(version[:at], version) if at > 0 else VersionTag(version)
