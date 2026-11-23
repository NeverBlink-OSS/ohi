---
title: Compatibility
description: Compatibility between ohi versions and ohi.dev.
---

ohi 0.18.7 and later keep working as [ohi.dev](https://ohi.dev) changes. You upgrade when you want
something new.

## Published files

A file on ohi.dev does not change after it is published. So a project with a committed
[`ohi.lock.json`](/docs/reference/project-files/#ohilockjson) gets the same files every time.

## New formats and details

ohi.dev adds file formats and details about ontologies over time. An older version of ohi ignores
what it does not know and keeps working as before.

For example, if ohi.dev adds a new format, ohi 0.18.7 does not list those files, and
`ohi get w3c/dcat@3 --format owl-turtle` still downloads the same file.

## Upgrading ohi

A newer version of ohi can change its commands, options and SDK functions. If your scripts or code
depend on them, stay on one version of ohi and read the
[release notes](https://github.com/NeverBlink-OSS/ohi/releases) before you upgrade.
