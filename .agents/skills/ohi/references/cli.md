# CLI behavior and recovery

## Search results

Search uses ohi.dev and returns one match per ontology, at its latest version. `--all-releases`
searches every release instead. Quote multiword queries, and use `'*'` to browse, for example with
`--filter-by namespace:=w3c`.

Each search returns one page in the catalog's order, five matches by default. ohi does not rerank
matches or fetch further pages, so request them with `--page` or `--per-page`. `search_cutoff: true`
means the matches or the total may be incomplete. An empty page from a successful search is
different from a failed request.

To search another catalog, supply both `--search-url` and `--query-by`. Supplying only one is an
error.

## Release metadata

`inspect` accepts a reference, `namespace/name[@version]`, or a release URL. Without `@version`, it
selects the latest version. For a release URL, check that the returned `base_url` equals it before
choosing a file.

Before selecting a file, check its `format`, `origin` and `roots`, and the release's `licenses`.
A format combines a language and a syntax, such as `owl-turtle`. Retrieve the file to read its
definitions.

ohi serves files that were prepared when the release was published. To convert local edits, use
`ohi linkml`. A conversion can leave out constraints that its target format cannot express.

## Retrieve a selected file

`get` and `add` resolve references through ohi.dev with an optional `--format`. Use the exact
namespace, name and version values from search or inspect. Supported formats are `owl-turtle`,
`owl-rdfxml`, `rdfs-turtle`, `linkml-yaml`, `ossie-yaml` and `shacl-turtle`.

Without `--format`, ohi reads `default_format` from `ohi.yaml`, or uses `linkml-yaml` without a
declaration. If the release lacks the preferred format, it falls back to `linkml-yaml`. An explicit
`--format` requires that format, without changing the project default. ohi does not look in older
releases when a format is missing.

A version can have revisions that correct it, such as `w3c/prov-o@2013-revision-2026-12-01`. A
plain version selects its latest revision. Name a revision to select exactly that one, or add
`-no-revision`, such as `w3c/prov-o@2013-no-revision`, to select the version as first published.

The URL forms are `get <file-url>` and `add <release-url> --artifact <file-url>`, without
`--format`. A URL-based `add` requires the catalog to resolve the release's reference and the file's
format to the same URL pair. Use `get` for files that a reference and format cannot select
uniquely.

ohi checks a file URL against its release's metadata and verifies the advertised SHA-256 before
saving. Matching existing regular files are verified and reused without downloading. A target with
different contents, or one that is not a regular file, causes an error and is never overwritten.

Files are saved as `<root>/<namespace>/<name>/<version>/<name>.<format-suffix>`, such as
`.ohi/w3c/dcat/3/dcat.linkml.yaml`. Use the absolute `path` from the JSON output. See
[downloaded files](https://ohi.dev/docs/reference/project-files/#downloaded-files) for naming and
Git-ignore behavior.

`get` uses `download_dir` from the current directory's `ohi.yaml`, or `.ohi` without a
declaration. `--to` overrides that root, resolving relative paths from the current directory. `get`
reads the declaration only when it needs the default format or directory, so supplying both
`--format` and `--to` skips it. A malformed declaration that `get` needs, or a failed inspection,
produces an error. `get` never searches parent directories or changes project files.

## Synchronize downloads

`ohi sync [--project <directory>]` installs all files from `ohi.lock.json`, using its exact URLs and
SHA-256 checksums. It updates the lock only when the lock is missing or the dependencies in
`ohi.yaml` changed. A matching lock needs no catalog requests. Existing files are verified and
reused, and completed downloads remain after a failure.

With only a lock, sync downloads into `.ohi/` and creates no declaration. Add `--init` to create
`ohi.yaml` from the lock's direct selections, keeping their versions and formats, with
`default_format: linkml-yaml` and `download_dir: .ohi`. Existing YAML is preserved. If neither file
exists, use `ohi init` or `ohi add`. Downloads require lock version 2.

`--locked` fails with `LOCK_OUTDATED` instead of updating a missing or outdated lock. `--frozen`
installs the lock as it is, without comparing it to the declaration, and fails with `LOCK_OUTDATED`
when the lock is missing. The two can't be combined. `ohi lock --check` fails with `LOCK_OUTDATED`
in the same cases as `sync --locked`, without catalog requests or writes.

## Revisions in a lock

The lock keeps the revision it selected for each version. `sync`, `lock` and adding other
ontologies never move a locked version to a newer revision. `ohi lock --upgrade` resolves every
dependency again to its latest revision. Run `ohi sync` afterwards to download the new files.
`--upgrade` can't be combined with `--check`.

## Dependencies

A release's `dependencies` list release URLs in `base_url`. The list is complete and flat. Inspect
those releases, choose files and download them with `get`, or [sync the lock](#synchronize-downloads)
when the project has one. ohi does not derive download URLs from namespace prefixes or configure an
application's loader.

## Project files

`add`, `sync` and `lock` use `--project`, defaulting to the current directory, and never search
parent directories. Relative project paths start at the current directory. `add` and `sync`
install all locked files into the project's `download_dir` and have no `--to` option.

`init [directory] [--default-format <format>] [--to <directory>]` sets the format and the
project-relative download directory for a new declaration. The defaults are `linkml-yaml` and
`.ohi`. It keeps an existing valid declaration and creates no lock or download directory. `add`
requires an existing project directory, validates its files and creates a missing declaration with
those defaults.

Edit [`ohi.yaml`](https://ohi.dev/docs/reference/project-files/#ohiyaml) to change the defaults,
the download directory or the dependencies. It requires `format_version: 1`, `default_format`,
`download_dir` and `dependencies`, a list of `ontology`/`format` pairs. Use `dependencies: []`
for an empty project.

Keep unrelated dependencies. Adding through `add` rewrites YAML comments and formatting. Adding an
existing selection again keeps `ohi.yaml` unchanged, rebuilds the lock and installs its files.

After editing dependencies, run `sync` to update the lock and install its files, or `lock` to
update the lock without downloading. Both keep the YAML and pinned versions. Let the CLI write
`ohi.lock.json`, and never edit it by hand.

When building a lock, ohi resolves each declared reference in its recorded format, without
fallback, then inspects each release's complete dependency list. Each dependency must offer exactly
one file in the same format as the release that depends on it. Missing or ambiguous matches fail
before any file is written. An empty declaration produces an empty lock and no downloads. A lock
alone confirms neither that its files were downloaded nor that releases work together.

Keep `ohi.yaml` and `ohi.lock.json` in version control, and keep files you write yourself outside
the download directory. See the [project guide](https://ohi.dev/docs/guides/projects/) for changing
directories and importing downloaded LinkML schemas.

## Failures and recovery

Commands write results to stdout and failures to stderr. Network requests do not follow redirects
or retry. Report HTTP and metadata errors as errors, never as empty search results. For a redirect,
obtain the final URL.

Project JSON results and errors report `declaration_created`, `declaration_changed`, `lock_changed`
and completed downloads in `files`. Writes happen in this order: declaration, lock, downloads.
Earlier writes remain after a later failure. The error message ends with the command to run after
fixing the cause:

| Failure stage | Recovery after fixing the cause |
| --- | --- |
| Before any project file is written | Rerun the command |
| Writing `ohi.yaml`, `ohi.lock.json` or the ignore file | Rerun the original command with the same project and selection arguments |
| A project download | Run the reported `ohi sync --project <directory>` |
| Standalone `get` | Rerun the command |

If ohi refuses an existing target, choose another destination, or move the file elsewhere with the
user's agreement, before retrying. If cleanup failed, deal with the specific incomplete path the
error reports. Do not assume that a failed download undid changes to project files.
