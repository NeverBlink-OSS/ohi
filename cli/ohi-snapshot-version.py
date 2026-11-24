"""Print the snapshot versions of the ohi SDKs for the checked out commit.

Run from the repository root. Prints `python=...` and `npm=...` lines, ready to append to
$GITHUB_OUTPUT, or nothing when the commit is a release itself.

The versions sort after the last release and before the next one, and grow with each commit:

  last tag     commits  Python (PEP 440)  npm (semver)
  v0.18.7      7        0.18.8.dev7       0.18.8-snapshot.7.g249e1f3b
  v0.19.0-rc1  3        0.19.0rc2.dev3    0.19.0-rc1.snapshot.3.g249e1f3b

PyPI rejects local versions (`+...`), so the Python version has no commit hash. The commit count
keeps it unique, as long as main has a linear history.
"""

import re
import subprocess
import sys

DESCRIBE = re.compile(r"v(.+)-(\d+)-g([0-9a-f]+)")
TAG = re.compile(r"(\d+)\.(\d+)\.(\d+)(?:-(a|alpha|b|beta|rc|RC)\.?(\d+))?")
MARKERS = {"a": "a", "alpha": "a", "b": "b", "beta": "b", "rc": "rc"}


def git(*args: str) -> str:
    return subprocess.run(["git", *args], check=True, capture_output=True, text=True).stdout.strip()


def tag_key(tag: str) -> tuple:
    match = TAG.fullmatch(tag)
    major, minor, patch, kind, number = match.groups()
    # A release sorts after its pre-releases
    return int(major), int(minor), int(patch), kind is None, MARKERS.get((kind or "").lower(), ""), int(number or 0)


def snapshot_versions(tag: str, commits: int, commit_hash: str) -> tuple[str, str]:
    match = TAG.fullmatch(tag)
    if not match:
        raise SystemExit(f"Cannot make a snapshot version from the tag v{tag}.")
    major, minor, patch, kind, number = match.groups()
    if kind is None:
        next_release = f"{major}.{minor}.{int(patch) + 1}"
        return f"{next_release}.dev{commits}", f"{next_release}-snapshot.{commits}.g{commit_hash}"
    release = f"{major}.{minor}.{patch}"
    pre_release = tag.split("-", 1)[1]
    marker = MARKERS[kind.lower()]
    return (f"{release}{marker}{int(number) + 1}.dev{commits}",
            f"{release}-{pre_release}.snapshot.{commits}.g{commit_hash}")


def main() -> None:
    described = git("describe", "--tags", "--long", "--abbrev=8", "--match", "v*")
    match = DESCRIBE.fullmatch(described)
    if not match:
        raise SystemExit(f"Unexpected output from git describe: {described}")
    tag, commits, commit_hash = match.group(1), int(match.group(2)), match.group(3)
    if commits == 0:
        return  # A release commit, published by release.yml
    # Like GitVersionPublishModule: when several tags point at the commit, take the newest
    tags = git("tag", "--points-at", f"HEAD~{commits}").splitlines()
    releases = [t[1:] for t in tags if t.startswith("v") and TAG.fullmatch(t[1:])]
    if releases:
        tag = max(releases, key=tag_key)
    python, npm = snapshot_versions(tag, commits, commit_hash)
    print(f"python={python}")
    print(f"npm={npm}")


if __name__ == "__main__":
    if len(sys.argv) != 1:
        raise SystemExit("Takes no arguments.")
    main()
