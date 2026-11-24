---
title: API Stability and Compatibility
description: Long-term API stability and compatibility for ohi SDKs and the CLI.
---

## API stability

From version 0.18.7 onward, you can pin your ohi SDK or CLI release. We maintain compatibility with
those releases for a long time, so changes to [ohi.dev](https://ohi.dev) do not require an SDK upgrade.

If we need to make a breaking API change, we will introduce a new API version alongside the one
your installed release uses.

## Published files

A file on ohi.dev does not change after it is published. So a project with a committed
[`ohi.lock.json`](/docs/reference/project-files/#ohilockjson) gets the same files every time.

## New formats and details

Non-breaking API changes preserve compatibility with existing clients. They include adding new
[file formats](/docs/reference/cli/#ontology-references-and-formats) or optional metadata fields
while keeping existing formats and fields available.

When reading responses from ohi.dev, older clients ignore additional metadata fields and skip files
in formats they do not recognize. For example, if ohi.dev adds a new format, ohi 0.18.7 does not list those files, and
`ohi get w3c/dcat@3 --format owl-turtle` still downloads the OWL Turtle file.

## Upgrading ohi

A newer version of ohi can change its commands, options and SDK functions. If your scripts or code
depend on them, stay on one version of ohi and read the
[release notes](https://github.com/NeverBlink-OSS/ohi/releases) before you upgrade.
