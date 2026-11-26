# Agent skills

The **ohi** plugin teaches coding agents to use the `ohi` CLI. It has two skills, and the agent loads
each one only when a task needs it:

- [ohi](skills/ohi/SKILL.md) finds, inspects and downloads ontologies from ohi.dev, and records them
  in a project's `ohi.yaml` and `ohi.lock.json`.
- [linkml](skills/linkml/SKILL.md) authors, validates, imports and generates LinkML schemas with
  `ohi linkml`.

The plugin contains only instructions. [Install the ohi CLI](https://ohi.dev/docs/getting-started/install/)
separately. The skills require `ohi` 0.18.7 or newer.

## Install

To install from a local checkout, replace `NeverBlink-OSS/ohi` with the checkout's absolute path.

### Claude Code

```sh
claude plugin marketplace add NeverBlink-OSS/ohi
claude plugin install ohi@ohi
```

### Codex

```sh
codex plugin marketplace add NeverBlink-OSS/ohi
codex plugin add ohi@ohi
```

### Updates

| Application | Refresh the marketplace | Update the installed plugin |
| --- | --- | --- |
| Claude Code | `claude plugin marketplace update ohi` | `claude plugin update ohi@ohi` |
| Codex | `codex plugin marketplace upgrade ohi` | Included in the marketplace upgrade |

Start a new session after updating. For a local checkout marketplace, remove and reinstall the
plugin after editing its files.

### Moving from the `linkml-scala` marketplace

Earlier releases published the marketplace as `linkml-scala`, with separate `ohi` and `linkml`
plugins. Both skills are now in the `ohi` plugin. Remove the old marketplace with the plugin you
installed from it, then install as above. For example, in Claude Code:

```sh
claude plugin uninstall linkml@linkml-scala
claude plugin marketplace remove linkml-scala
```

In Codex, use `codex plugin remove linkml@linkml-scala` and `codex plugin marketplace remove linkml-scala`.
If you installed `ohi@linkml-scala`, use that name instead.

### Manual installation

Without a plugin, copy both skill folders, including their reference files, to the application's
skills directory. The ohi skill links to the LinkML skill's installation guide. For example, for all
Codex projects:

```sh
git clone --depth=1 https://github.com/NeverBlink-OSS/ohi ~/src/ohi
mkdir -p ~/.agents/skills
cp -r ~/src/ohi/.agents/skills/{linkml,ohi} ~/.agents/skills/
```

For Claude Code, use `~/.claude/skills/`. For a single project, use `.agents/skills/` for Codex or
`.claude/skills/` for Claude Code in that project. Replace whole folders when updating. Use either
manual installation or the plugin, not both, to avoid duplicate skills.

### Other applications

For other applications supporting [Agent Skills](https://agentskills.io/specification), copy the
folders to their documented skills directory.

## Using the skills

For ontologies, give the agent search terms, an ontology name or a URL, the format you need and,
for project changes, the project directory. For example, ask it to find a provenance ontology and
download it as `owl-turtle`, or to add DCAT to the project in `./catalog`.

For schemas, describe the model or point the agent at a schema, sample data or an ontology to
import. The LinkML skill's topic files hold detailed guidance that the agent reads when needed.

## Maintenance

Edit skills in `.agents/skills/`. Regenerate these LinkML references from their sources:

| File | Source |
| --- | --- |
| `900-metaslots.tsv` | LinkML metamodel bundled with the `metamodel` module |
| `200-limitations.md` | `website/src/content/docs/implementation-differences.md` |
| `210-slot-usage-inheritance.md` | `website/src/content/docs/slot-usage-inheritance.md` |
| `220-import-maps.md` | `website/src/content/docs/import-maps.md` |
| `230-curies.md` | `website/src/content/docs/curies.md` |
| `240-langstring.md` | `website/src/content/docs/langstring.md` |
| `910-examples.md` | `tests/resources/models/` |

```sh
./mill agents.generate
./mill agents.test
```

`./mill regenerate` also runs `agents.generate`. The tests check that the generated files are up to date, the skills' structure and links, and that every schema example validates. They also check that both marketplaces ship every skill. Review nested ohi reference links separately.

The ohi skill is written by hand. When ohi commands, options or JSON output change, update it along with
`ohi/docs/src/content/docs/reference/cli.md`.
