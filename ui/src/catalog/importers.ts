// The importers the playground can run, which turn another format into a LinkML schema, shared by
// the UI and the worker in the same way as the generators (see targets.ts).
import type { LoadResult } from "../linkml";
import type { Importer, OptionValues } from "./types.js";
import { converterOptions } from "./options.js";
import { EXAMPLE_OSSIE, EXAMPLE_OWL } from "./examples.js";

export const IMPORTERS: Importer[] = [
  {
    id: "ossie",
    label: "Apache Ossie",
    docs: "importers/ossie/",
    inputLabel: "Ossie ontology (YAML or JSON)",
    reverses: ["ossie"],
    lang: (o) => (o.format === "json" ? "json" : "yaml"),
    example: EXAMPLE_OSSIE,
    ohiFormats: [{ format: "ossie-yaml", label: "Ossie", name: "Ossie" }],
    options: [
      { key: "schema_id", type: "text", label: "Schema id", placeholder: "https://… (optional)" },
      { key: "full_uris", type: "checkbox", label: "Full URIs", title: "Write URIs in full, instead of as CURIEs" },
      { key: "format", type: "select", label: "Format", choices: ["yaml", "json"], default: "yaml" },
    ],
    call: (api, input, o) => serialized(api.loadFromOssie(input, converterOptions(forLoading(o))), o),
  },
  {
    id: "owl",
    label: "OWL 2 / RDFS",
    docs: "importers/owl/",
    inputLabel: "OWL 2 or RDFS ontology (Turtle)",
    reverses: ["owl", "rdfs"],
    inputLang: "turtle",
    lang: (o) => (o.format === "json" ? "json" : "yaml"),
    example: EXAMPLE_OWL,
    // The importer reads Turtle only, so the RDF/XML files on ohi.dev are not on offer.
    ohiFormats: [
      { format: "owl-turtle", label: "OWL", name: "OWL (Turtle)" },
      { format: "rdfs-turtle", label: "RDFS", name: "RDFS (Turtle)" },
    ],
    options: [
      {
        key: "list_not_imported",
        type: "checkbox",
        label: "Not imported",
        title: "List what could not be imported, in comments at the top",
        showIf: (o) => o.format !== "json",
      },
      { key: "full_uris", type: "checkbox", label: "Full URIs", title: "Write URIs in full, instead of as CURIEs" },
      { key: "format", type: "select", label: "Format", choices: ["yaml", "json"], default: "yaml" },
    ],
    call: (api, input, o) => {
      // Not an option of the importer, which always returns the warnings.
      const { list_not_imported, ...options } = o;
      const loaded = api.loadFromOwl(input, converterOptions(forLoading(options)));
      const schema = serialized(loaded, options);
      if (!list_not_imported || o.format === "json") return schema;
      const warnings = loaded.notImported?.warnings ?? [];
      return warnings.map((w) => `# Not imported: ${w}\n`).join("") + schema;
    },
  },
];

/** The options that only shape the text of a schema, which `serialize` takes and loading does not. */
const TEXT_OPTIONS = ["format", "full_uris"];

function forLoading(o: OptionValues): OptionValues {
  return Object.fromEntries(Object.entries(o).filter(([key]) => !TEXT_OPTIONS.includes(key)));
}

/** The text of a schema a loader made, or the report of why it could not be loaded. */
function serialized(loaded: LoadResult, o: OptionValues): string {
  if (!loaded.view) throw new Error(JSON.stringify(loaded.report, null, 2));
  const text = Object.fromEntries(Object.entries(o).filter(([key]) => TEXT_OPTIONS.includes(key)));
  return loaded.view.serialize(converterOptions(text));
}

export function importerById(id: string): Importer | undefined {
  return IMPORTERS.find((i) => i.id === id);
}
