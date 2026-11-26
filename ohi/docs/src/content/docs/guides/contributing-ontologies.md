---
title: Contribute an ontology
description: Prepare an ontology release and submit it to the ohi index.
---

Add an ontology release through a pull request in the
[ohi index](https://github.com/NeverBlink-OSS/ohi-index).

## Choose a release

Use the version given on the ontology's official website or in its source repository.
If no version is given, ask in an issue.

Check the [index](https://github.com/NeverBlink-OSS/ohi-index/tree/main/ontologies) for that
ontology and version. If it is already listed, report corrections in an issue and link to
its folder.

Check that the license permits redistribution. Include the license text and required
copyright notices. If no license is given, ask the publisher for permission.

## Prepare the files

Fork the index repository and create a branch. Put each release in its own folder at
`ontologies/<namespace>/<name>/<version>/`.

The example uses [Obographs 0.0.1](https://github.com/biodatamodels/obograph), a LinkML
schema already in the index:

```text
ontologies/biodatamodels/obograph/0.0.1/
├── ohi-package.yaml
├── obograph.yaml
├── LICENSE
└── README.md
```

The folder path has three parts:

- `namespace` groups entries in the index, here `biodatamodels`.
- `name` identifies the ontology within that group, here `obograph`.
- `version` is the publisher's version, here `0.0.1`.

For another version of an indexed ontology, reuse its namespace and name. Ask in an issue
if you are unsure which namespace to use for a new ontology.

Download [obograph.yaml](https://raw.githubusercontent.com/biodatamodels/obograph/5e37965de4affe91dec05928cf5abacb77a05a12/model/schema/obograph.yaml)
and save the [CC0 license text](https://creativecommons.org/publicdomain/zero/1.0/legalcode.txt)
as `LICENSE`.

In `README.md`, link to the source release or commit and describe any changes to the upstream
files. ohi publishes every file in the folder except `ohi-package.yaml`. Keep working notes
and temporary files elsewhere.

## Write the manifest

Create `ohi-package.yaml` in the release folder:

```yaml
namespace: biodatamodels
name: obograph
version: "0.0.1"
language: linkml
syntax: yaml
roots: [obograph.yaml]
licenses: ["CC0-1.0"]
licenseFiles: [LICENSE]
dependencies: []
title: "Obographs LinkML model"
description: "A schema for ontology graphs with nodes, edges and metadata."
keywords: ["ontology graphs", "linkml"]
tags: [knowledge-representation]
authors:
  - name: "Chris Mungall"
    link: "https://mungall.dev"
  - name: "Sierra Taylor Moxon"
    link: "https://github.com/sierra-moxon"
publishers:
  - name: "BioDataModels"
    link: "https://github.com/biodatamodels"
originalSources: ["https://github.com/biodatamodels/obograph"]
```

Match `namespace`, `name` and `version` to the folder path. Quote `version` to keep it as
text. `roots` must name exactly one ontology file.

Set `language` and `syntax` for the source file:

| | `language` | `syntax` |
| --- | --- | --- |
| LinkML | `linkml` | `yaml` |
| Apache Ossie | `ossie` | `yaml` |
| OWL | `owl` | `turtle` or `rdfxml` |
| RDF Schema | `rdfs` | `turtle` |

Use [SPDX identifiers](https://spdx.org/licenses/) in `licenses` where available. List full
license texts in `licenseFiles` and separate copyright notices in `copyrightFiles`, using
paths relative to the release folder.

Pick the ontology's thematic areas in `tags` from the [tag list](https://api.ohi.dev/files/tags_v1.json),
by their ids. Unlike `keywords`, which can be anything, a tag must be in the list. Unknown,
deprecated or repeated ids are refused. `tags` is optional.

List imported ontologies in `dependencies` as `namespace/name@version`. Each must already
be published on ohi. Use `[]` when none are needed. The built-in `linkml:types` import does
not need an entry.

Credit the creators in `authors` and the publisher in `publishers`. If you are neither an
author nor a publisher, you must add yourself or your organization under `maintainers`.
Include contact details and a GitHub profile URL containing your username:

```yaml
maintainers:
  - name: "Your name or organization"
    contact: "you@example.org"
    link: "https://github.com/your-username"
```

## Use remote files

Use `sources` to reference ontology or license files by URL instead of committing them.
For `obograph.yaml`, add this entry to `ohi-package.yaml`:

```yaml
sources:
  - name: obograph.yaml
    url: https://github.com/biodatamodels/obograph/blob/5e37965de4affe91dec05928cf5abacb77a05a12/model/schema/obograph.yaml
    sha256: "97fc065721c761fb7f36fd800c00fda0cdff1b6a3734304ef5883cd6c19c5b5a"
    size: 3389
```

With the above entry, commit `ohi-package.yaml`, `LICENSE` and `README.md` from the example.
ohi downloads `obograph.yaml` from the URL, so leave that file out of the pull request.
Keep `roots: [obograph.yaml]` to match the source's `name`.

Pin the URL to a release or commit. GitHub file links are supported. Other URLs must use
HTTPS and return the file without redirects. Each source filename must be unique across
`sources` and files in the release folder. It may contain letters, digits, `.`, `_` or `-`
and must start with a letter or digit.

Download the file and calculate its `sha256` hash and `size` in bytes:

```sh
shasum -a 256 obograph.yaml
wc -c < obograph.yaml
```

ohi checks both values when it fetches the file.

## Open a pull request

Open a pull request against the index repository's `main` branch with the ontology name,
version and source link. Update the same branch to address review comments or failed checks.

After merging, check that the release and its downloads are available on
[ohi.dev](https://ohi.dev/). Report missing releases or failed downloads in the pull request.
