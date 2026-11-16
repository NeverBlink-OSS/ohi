---
title: Contribute an ontology
description: Prepare an ontology release and submit it to the ohi index.
---

Add an ontology release through a pull request in the
[ohi index](https://github.com/NeverBlink-OSS/ohi-index).

## Choose a release

Check the [index](https://github.com/NeverBlink-OSS/ohi-index/tree/main/ontologies) for an
existing entry. For a new version, reuse its namespace and name and use the upstream version.
Report errors in existing releases in an issue linking to the release. If you are unsure
about the version or namespace, ask in an issue.

Use an official source whose license permits redistribution. Include the license text and
required copyright notices. If no license is given, ask the publisher for permission.

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
| OWL | `owl` | `turtle`, `rdfxml`, `ntriples` or `jsonld` |
| RDF Schema | `rdfs` | `turtle`, `rdfxml`, `ntriples` or `jsonld` |

ohi does not generate other formats from JSON-LD sources.

Use [SPDX identifiers](https://spdx.org/licenses/) in `licenses` where available. List full
license texts in `licenseFiles` and separate copyright notices in `copyrightFiles`, using
paths relative to the release folder.

List imported ontologies in `dependencies` as `namespace/name@version`. Each must already
be published on ohi. Use `[]` when none are needed. The built-in `linkml:types` import needs
no entry.

Credit the creators in `authors` and the upstream publisher in `publishers`. If you are
neither, add your contact details under `maintainers`:

```yaml
maintainers:
  - name: "Your name or organization"
    contact: "you@example.org"
```

## Use remote files

To use a remote ontology or license file, add it to `sources`:

```yaml
sources:
  - name: obograph.yaml
    url: https://github.com/biodatamodels/obograph/blob/5e37965de4affe91dec05928cf5abacb77a05a12/model/schema/obograph.yaml
    sha256: "97fc065721c761fb7f36fd800c00fda0cdff1b6a3734304ef5883cd6c19c5b5a"
    size: 3389
```

Pin the URL to a release or commit. GitHub file links are supported. Other URLs must use
HTTPS and return the file without redirects. Source filenames may contain letters, digits,
`.`, `_` or `-` and must start with a letter or digit.

Download the file and calculate its `sha256` hash and `size` in bytes:

```sh
shasum -a 256 obograph.yaml
wc -c < obograph.yaml
```

ohi checks both values when downloading. Keep `roots: [obograph.yaml]`, but remove the local
`obograph.yaml` before submitting. Local files and remote sources must have distinct names.

## Open a pull request

Open a pull request against the index repository's `main` branch with the ontology name,
version and source link. Update the same branch to address review comments or failed checks.

After merging, check that the release and its downloads are available on
[ohi.dev](https://ohi.dev/). Report missing releases or failed downloads in the pull request.
